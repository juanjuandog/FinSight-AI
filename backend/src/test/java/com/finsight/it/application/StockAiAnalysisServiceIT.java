package com.finsight.it.application;

import com.finsight.application.StockAiAnalysisService;
import com.finsight.domain.model.Company;
import com.finsight.domain.model.FinancialMetric;
import com.finsight.domain.model.RiskSignal;
import com.finsight.domain.repository.CompanyRepository;
import com.finsight.domain.repository.MetricRepository;
import com.finsight.it.AbstractPostgresRedisRabbitIT;
import com.finsight.market.MarketDataService;
import com.finsight.market.MarketQuote;
import com.finsight.rag.EvidenceRetriever;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Year;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class StockAiAnalysisServiceIT extends AbstractPostgresRedisRabbitIT {
    private static final AtomicBoolean FAIL_SIDECAR = new AtomicBoolean();
    private static final AtomicInteger SIDECAR_CALLS = new AtomicInteger();
    private static final HttpServer SIDECAR = startSidecar();

    @Autowired
    StockAiAnalysisService analysisService;

    @Autowired
    CompanyRepository companyRepository;

    @Autowired
    MetricRepository metricRepository;

    @MockBean
    MarketDataService marketDataService;

    @MockBean
    EvidenceRetriever evidenceRetriever;

    @DynamicPropertySource
    static void sidecarProperties(DynamicPropertyRegistry registry) {
        registry.add("finsight.ai-service-url", () -> "http://127.0.0.1:" + SIDECAR.getAddress().getPort());
    }

    @BeforeEach
    void configureResearchInputs() {
        FAIL_SIDECAR.set(false);
        SIDECAR_CALLS.set(0);
        when(evidenceRetriever.retrieve(anyString(), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(List.of());
        when(marketDataService.quote(anyString())).thenAnswer(invocation -> quote(invocation.getArgument(0)));
    }

    @Test
    void successfulSidecarResponseIsVersionedAndPersisted() {
        seedCompany("600519", "贵州茅台");

        StockAiAnalysisService.StockAiAnalysisResponse response = analysisService.analyze("600519");

        assertThat(response.aiGenerated()).isTrue();
        assertThat(response.model()).isEqualTo("integration-sidecar");
        assertThat(response.reportId()).isNotBlank();
        assertThat(response.reportVersion()).isEqualTo(1);
        assertThat(response.dataSnapshotHash()).hasSize(64);
        assertThat(response.guidance()).isNotNull();
        assertThat(analysisService.latest("600519")).contains(response);
        assertThat(analysisService.history("600519", 10)).hasSize(1);
    }

    @Test
    void identicalAnalysisUsesRedisCacheWithoutCallingSidecarTwice() {
        seedCompany("000001", "平安银行");

        StockAiAnalysisService.StockAiAnalysisResponse first = analysisService.analyze("000001");
        StockAiAnalysisService.StockAiAnalysisResponse second = analysisService.analyze("000001");

        assertThat(first.cacheHit()).isFalse();
        assertThat(second.cacheHit()).isTrue();
        assertThat(second.reportId()).isEqualTo(first.reportId());
        assertThat(SIDECAR_CALLS).hasValue(1);
        assertThat(analysisService.history("000001", 0)).hasSize(1);
    }

    @Test
    void unreachableSidecarFallsBackAndStillPersistsResearch() {
        seedCompany("601318", "中国平安");
        metricRepository.saveMetric(new FinancialMetric(
                "601318", Year.of(2025), "ROE", "净资产收益率", new BigDecimal("0.16"), "it"
        ));
        metricRepository.saveMetric(new FinancialMetric(
                "601318", Year.of(2025), "OCF_NET_PROFIT", "现金流质量", new BigDecimal("0.92"), "it"
        ));
        metricRepository.saveRiskSignal(new RiskSignal(
                "risk-it", "601318", "VOLATILITY", "波动风险", "波动率升高", 2, LocalDate.now()
        ));
        FAIL_SIDECAR.set(true);

        StockAiAnalysisService.StockAiAnalysisResponse response = analysisService.analyze("601318");

        assertThat(response.aiGenerated()).isFalse();
        assertThat(response.model()).isEqualTo("rule-fallback");
        assertThat(response.source()).isEqualTo("fallback-rule");
        assertThat(response.positivePoints()).isNotEmpty();
        assertThat(response.riskPoints()).contains("波动风险");
        assertThat(response.reportVersion()).isEqualTo(1);
    }

    private void seedCompany(String symbol, String name) {
        companyRepository.save(new Company(symbol, name, "SH", "金融"));
    }

    private MarketQuote quote(String symbol) {
        return new MarketQuote(
                symbol,
                "SH",
                symbol,
                new BigDecimal("100.00"),
                new BigDecimal("99.00"),
                new BigDecimal("99.50"),
                new BigDecimal("101.00"),
                new BigDecimal("98.50"),
                BigDecimal.ONE,
                new BigDecimal("1.01"),
                LocalDate.now(),
                LocalTime.NOON,
                "integration",
                true,
                null
        );
    }

    private static HttpServer startSidecar() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/analyze-stock", StockAiAnalysisServiceIT::respond);
            server.start();
            return server;
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        SIDECAR_CALLS.incrementAndGet();
        exchange.getRequestBody().readAllBytes();
        int status = FAIL_SIDECAR.get() ? 503 : 200;
        String json = FAIL_SIDECAR.get()
                ? "{\"error\":\"sidecar unavailable\"}"
                : "{\"rating\":\"优先研究\",\"summary\":\"集成测试分析摘要\","
                + "\"positivePoints\":[\"现金流稳定\"],\"riskPoints\":[\"估值波动\"],"
                + "\"confidence\":82,\"citations\":[],\"model\":\"integration-sidecar\","
                + "\"source\":\"ai-sidecar\",\"aiGenerated\":true}";
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
