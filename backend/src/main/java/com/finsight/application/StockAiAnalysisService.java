package com.finsight.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsight.domain.model.Company;
import com.finsight.domain.model.EvidenceChunk;
import com.finsight.domain.model.FinancialDocument;
import com.finsight.domain.model.FinancialMetric;
import com.finsight.domain.model.RiskSignal;
import com.finsight.domain.model.StockAnalysisReport;
import com.finsight.domain.repository.CompanyRepository;
import com.finsight.domain.repository.DocumentRepository;
import com.finsight.domain.repository.MetricRepository;
import com.finsight.domain.repository.StockAnalysisReportRepository;
import com.finsight.market.ExchangeResolver;
import com.finsight.market.MarketDataService;
import com.finsight.market.MarketQuote;
import com.finsight.rag.EvidenceRetriever;
import com.finsight.workflow.WorkflowLease;
import com.finsight.workflow.WorkflowLeaseService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class StockAiAnalysisService {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final CompanyRepository companyRepository;
    private final MetricRepository metricRepository;
    private final DocumentRepository documentRepository;
    private final MarketDataService marketDataService;
    private final ExchangeResolver exchangeResolver;
    private final StockUniverseService stockUniverseService;
    private final EvidenceRetriever evidenceRetriever;
    private final StockAnalysisReportRepository reportRepository;
    private final StockAnalysisCache analysisCache;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final Duration analysisCacheTtl;
    private final WorkflowLeaseService leaseService;

    public StockAiAnalysisService(
            CompanyRepository companyRepository,
            MetricRepository metricRepository,
            DocumentRepository documentRepository,
            MarketDataService marketDataService,
            ExchangeResolver exchangeResolver,
            StockUniverseService stockUniverseService,
            EvidenceRetriever evidenceRetriever,
            StockAnalysisReportRepository reportRepository,
            StockAnalysisCache analysisCache,
            ObjectMapper objectMapper,
            WebClient.Builder builder,
            WorkflowLeaseService leaseService,
            @Value("${finsight.ai-service-url:http://localhost:8001}") String aiServiceUrl,
            @Value("${finsight.cache.analysis-ttl:PT6H}") Duration analysisCacheTtl
    ) {
        this.companyRepository = companyRepository;
        this.metricRepository = metricRepository;
        this.documentRepository = documentRepository;
        this.marketDataService = marketDataService;
        this.exchangeResolver = exchangeResolver;
        this.stockUniverseService = stockUniverseService;
        this.evidenceRetriever = evidenceRetriever;
        this.reportRepository = reportRepository;
        this.analysisCache = analysisCache;
        this.objectMapper = objectMapper;
        this.webClient = builder.baseUrl(trimTrailingSlash(aiServiceUrl)).build();
        this.leaseService = leaseService;
        this.analysisCacheTtl = analysisCacheTtl;
    }

    public StockAiAnalysisResponse analyze(String symbol) {
        String normalized = exchangeResolver.normalizeSymbol(symbol);
        Company company = companyRepository.findBySymbol(normalized)
                .orElseGet(() -> stockUniverseService.resolveAStock(normalized));
        MarketQuote quote = marketDataService.quote(normalized);
        List<FinancialMetric> metrics = metricRepository.findMetrics(normalized).stream()
                .sorted(Comparator.comparing(FinancialMetric::fiscalYear).reversed())
                .limit(24)
                .toList();
        List<RiskSignal> risks = metricRepository.findRiskSignals(normalized).stream()
                .sorted(Comparator.comparing(RiskSignal::detectedAt).reversed())
                .limit(12)
                .toList();
        List<EvidencePayload> evidence = evidence(normalized, company.name());
        StockAiAnalysisRequest request = new StockAiAnalysisRequest(
                company,
                quote,
                metrics,
                risks,
                evidence
        );

        String contextHash = contextHash(request);
        String dataSnapshotHash = contextHash;
        String cacheKey = normalized + ":" + dataSnapshotHash;
        Optional<StockAiAnalysisResponse> cached = analysisCache.get(cacheKey)
                .map(StockAiAnalysisResponse::withCacheHit);
        if (cached.isPresent()) {
            return cached.get();
        }
        Optional<StockAiAnalysisResponse> latest = reportRepository.findLatest(normalized)
                .filter(report -> report.contextHash().equals(contextHash))
                .map(this::fromReport)
                .map(StockAiAnalysisResponse::withCacheHit);
        if (latest.isPresent()) {
            analysisCache.put(cacheKey, latest.get(), analysisCacheTtl);
            return latest.get();
        }

        String leaseKey = "stock-analysis:" + cacheKey;
        Optional<WorkflowLease> lease = leaseService.tryAcquire(leaseKey, Duration.ofSeconds(90));
        if (lease.isEmpty()) {
            return awaitConcurrentResult(cacheKey, normalized, contextHash);
        }
        try {
            Optional<StockAiAnalysisResponse> secondCheck = analysisCache.get(cacheKey)
                    .or(() -> reportRepository.findLatest(normalized)
                            .filter(report -> report.contextHash().equals(contextHash))
                            .map(this::fromReport))
                    .map(StockAiAnalysisResponse::withCacheHit);
            if (secondCheck.isPresent()) {
                return secondCheck.get();
            }
            StockAiAnalysisResponse response = callAiOrFallback(request, company, quote, metrics, risks, evidence);
            return persistAndCache(normalized, contextHash, dataSnapshotHash, cacheKey, response);
        } finally {
            leaseService.release(lease.get());
        }
    }

    private StockAiAnalysisResponse callAiOrFallback(
            StockAiAnalysisRequest request,
            Company company,
            MarketQuote quote,
            List<FinancialMetric> metrics,
            List<RiskSignal> risks,
            List<EvidencePayload> evidence
    ) {
        StockAiAnalysisResponse response = null;
        try {
            response = webClient.post()
                    .uri("/analyze-stock")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(StockAiAnalysisResponse.class)
                    .block(TIMEOUT);
        } catch (RuntimeException ignored) {
            // Keep the UI usable when the local Ollama sidecar is not running.
        }
        return response == null || response.summary() == null || response.summary().isBlank()
                ? fallback(company, quote, metrics, risks, evidence)
                : response;
    }

    private StockAiAnalysisResponse awaitConcurrentResult(
            String cacheKey,
            String symbol,
            String contextHash
    ) {
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            Optional<StockAiAnalysisResponse> completed = analysisCache.get(cacheKey)
                    .or(() -> reportRepository.findLatest(symbol)
                            .filter(report -> report.contextHash().equals(contextHash))
                            .map(this::fromReport))
                    .map(StockAiAnalysisResponse::withCacheHit);
            if (completed.isPresent()) {
                return completed.get();
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for stock analysis", ex);
            }
        }
        throw new IllegalStateException("Stock analysis is already running for " + symbol);
    }

    public Optional<StockAiAnalysisResponse> latest(String symbol) {
        String normalized = exchangeResolver.normalizeSymbol(symbol);
        return reportRepository.findLatest(normalized).map(this::fromReport);
    }

    public List<StockAiAnalysisResponse> history(String symbol, int limit) {
        String normalized = exchangeResolver.normalizeSymbol(symbol);
        return reportRepository.findByCompanySymbol(normalized, Math.min(Math.max(limit, 1), 50)).stream()
                .map(this::fromReport)
                .toList();
    }

    private List<EvidencePayload> evidence(String symbol, String companyName) {
        List<EvidencePayload> ragEvidence = evidenceRetriever.retrieve(
                        companyName + " 投资价值、财务质量、现金流和主要风险",
                        Map.of("companySymbol", symbol, "requiresMetrics", true)
                ).stream()
                .limit(8)
                .map(this::evidencePayload)
                .toList();
        if (!ragEvidence.isEmpty()) {
            return ragEvidence;
        }
        return documentRepository.findByCompanySymbol(symbol).stream()
                .sorted(Comparator.comparing(FinancialDocument::publishedAt).reversed())
                .limit(8)
                .map(this::evidencePayload)
                .toList();
    }

    private EvidencePayload evidencePayload(EvidenceChunk chunk) {
        String text = chunk.text() == null ? "" : chunk.text();
        if (text.length() > 420) {
            text = text.substring(0, 420);
        }
        return new EvidencePayload(
                chunk.documentId(),
                chunk.title(),
                chunk.documentType().name(),
                chunk.publishedAt() == null ? null : chunk.publishedAt().toString(),
                chunk.section(),
                text
        );
    }

    private EvidencePayload evidencePayload(FinancialDocument document) {
        String text = document.content() == null ? "" : document.content();
        if (text.length() > 360) {
            text = text.substring(0, 360);
        }
        return new EvidencePayload(
                document.id(),
                document.title(),
                document.type().name(),
                document.publishedAt() == null ? null : document.publishedAt().toString(),
                String.valueOf(document.metadata().getOrDefault("section", "公开资料")),
                text
        );
    }

    private StockAiAnalysisResponse persistAndCache(
            String symbol,
            String contextHash,
            String dataSnapshotHash,
            String cacheKey,
            StockAiAnalysisResponse response
    ) {
        Instant generatedAt = Instant.now();
        String reportId = UUID.randomUUID().toString();
        int reportVersion = reportRepository.nextVersion(symbol);
        StockAiAnalysisResponse enriched = response.withPersistence(
                reportId,
                generatedAt,
                false,
                dataSnapshotHash,
                reportVersion
        );
        reportRepository.save(new StockAnalysisReport(
                reportId,
                symbol,
                safe(enriched.rating(), "中性"),
                safe(enriched.summary(), "暂无分析摘要"),
                safeList(enriched.positivePoints()),
                safeList(enriched.riskPoints()),
                enriched.confidence(),
                safeList(enriched.citations()),
                safe(enriched.model(), "unknown"),
                safe(enriched.source(), "unknown"),
                enriched.aiGenerated(),
                contextHash,
                dataSnapshotHash,
                reportVersion,
                generatedAt
        ));
        analysisCache.put(cacheKey, enriched, analysisCacheTtl);
        return enriched;
    }

    private StockAiAnalysisResponse fromReport(StockAnalysisReport report) {
        return new StockAiAnalysisResponse(
                report.rating(),
                report.summary(),
                report.positivePoints(),
                report.riskPoints(),
                report.confidence(),
                report.citations(),
                report.model(),
                report.source(),
                report.aiGenerated(),
                report.id(),
                report.generatedAt(),
                false,
                report.dataSnapshotHash(),
                report.reportVersion()
        );
    }

    private StockAiAnalysisResponse fallback(
            Company company,
            MarketQuote quote,
            List<FinancialMetric> metrics,
            List<RiskSignal> risks,
            List<EvidencePayload> evidence
    ) {
        int warningCount = risks.size();
        BigDecimal roe = metric(metrics, "ROE");
        BigDecimal ocf = metric(metrics, "OCF_NET_PROFIT");
        if (roe != null && roe.compareTo(BigDecimal.valueOf(0.10)) < 0) {
            warningCount++;
        }
        if (ocf != null && ocf.compareTo(BigDecimal.valueOf(0.80)) < 0) {
            warningCount++;
        }
        if (quote.changePercent().compareTo(BigDecimal.valueOf(-1)) < 0) {
            warningCount++;
        }
        String rating = warningCount >= 3 ? "谨慎" : warningCount >= 1 ? "中性" : "积极";
        int confidence = Math.max(62, Math.min(88, 78 - warningCount * 4 + (quote.realtime() ? 4 : 0)));
        List<String> positives = metrics.stream()
                .filter(metric -> List.of("ROE", "REVENUE_YOY", "OCF_NET_PROFIT").contains(metric.code()))
                .limit(3)
                .map(metric -> metric.name() + "为 " + metric.value())
                .toList();
        List<String> riskPoints = risks.stream()
                .map(RiskSignal::title)
                .limit(4)
                .toList();
        return new StockAiAnalysisResponse(
                rating,
                company.name() + "当前评级为" + rating + "。系统结合行情、财务指标、风险规则和公开证据生成该结论，仅作信息整理与风险提示。",
                positives.isEmpty() ? List.of("等待更多财务指标沉淀后可形成更充分的优势判断") : positives,
                riskPoints.isEmpty() ? List.of("暂未触发明显风险规则，但仍需关注后续公告、行业景气度和估值波动") : riskPoints,
                confidence,
                evidence.stream().map(EvidencePayload::title).limit(5).toList(),
                "rule-fallback",
                "fallback-rule",
                false,
                null,
                null,
                false,
                null,
                0
        );
    }

    private String contextHash(StockAiAnalysisRequest request) {
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("symbol", request.company().symbol());
        fingerprint.put("company", request.company().name());
        fingerprint.put("quotePrice", request.quote().currentPrice());
        fingerprint.put("quoteChange", request.quote().changePercent());
        fingerprint.put("quoteDate", request.quote().tradeDate());
        fingerprint.put("quoteRealtime", request.quote().realtime());
        fingerprint.put("metrics", request.metrics().stream()
                .map(metric -> metric.code() + ":" + metric.fiscalYear() + ":" + metric.value())
                .toList());
        fingerprint.put("risks", request.risks().stream()
                .map(risk -> risk.code() + ":" + risk.detectedAt() + ":" + risk.severity())
                .toList());
        fingerprint.put("evidence", request.evidence().stream()
                .map(item -> item.documentId() + ":" + item.title() + ":" + item.section())
                .toList());
        try {
            return sha256(objectMapper.writeValueAsString(fingerprint));
        } catch (JsonProcessingException ex) {
            return sha256(fingerprint.toString());
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : digest) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", ex);
        }
    }

    private BigDecimal metric(List<FinancialMetric> metrics, String code) {
        return metrics.stream()
                .filter(metric -> code.equals(metric.code()))
                .findFirst()
                .map(FinancialMetric::value)
                .orElse(null);
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:8001";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private record StockAiAnalysisRequest(
            Company company,
            MarketQuote quote,
            List<FinancialMetric> metrics,
            List<RiskSignal> risks,
            List<EvidencePayload> evidence
    ) {
    }

    public record EvidencePayload(
            String documentId,
            String title,
            String documentType,
            String publishedAt,
            String section,
            String text
    ) {
    }

    public record StockAiAnalysisResponse(
            String rating,
            String summary,
            List<String> positivePoints,
            List<String> riskPoints,
            int confidence,
            List<String> citations,
            String model,
            String source,
            boolean aiGenerated,
            String reportId,
            Instant generatedAt,
            boolean cacheHit,
            String dataSnapshotHash,
            int reportVersion
    ) {
        public StockAiAnalysisResponse withPersistence(String reportId, Instant generatedAt, boolean cacheHit) {
            return withPersistence(reportId, generatedAt, cacheHit, dataSnapshotHash, reportVersion);
        }

        public StockAiAnalysisResponse withPersistence(
                String reportId,
                Instant generatedAt,
                boolean cacheHit,
                String dataSnapshotHash,
                int reportVersion
        ) {
            return new StockAiAnalysisResponse(
                    rating,
                    summary,
                    positivePoints,
                    riskPoints,
                    confidence,
                    citations,
                    model,
                    source,
                    aiGenerated,
                    reportId,
                    generatedAt,
                    cacheHit,
                    dataSnapshotHash,
                    reportVersion
            );
        }

        public StockAiAnalysisResponse withCacheHit() {
            return withPersistence(reportId, generatedAt, true);
        }
    }
}
