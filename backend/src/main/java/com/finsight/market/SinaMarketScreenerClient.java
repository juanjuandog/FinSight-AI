package com.finsight.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Public Sina market snapshot used when the primary Eastmoney endpoint is unavailable. */
@Component
public class SinaMarketScreenerClient {
    private static final Logger log = LoggerFactory.getLogger(SinaMarketScreenerClient.class);
    private static final String API = "https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/Market_Center.getHQNodeData";
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 60;

    private final ObjectMapper objectMapper;
    private final ExchangeResolver exchangeResolver;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public SinaMarketScreenerClient(ObjectMapper objectMapper, ExchangeResolver exchangeResolver) {
        this.objectMapper = objectMapper;
        this.exchangeResolver = exchangeResolver;
    }

    public List<MarketScreenerRow> aShareSnapshot() {
        List<MarketScreenerRow> rows = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            JsonNode data = page(page);
            if (!data.isArray() || data.isEmpty()) {
                break;
            }
            for (JsonNode item : data) {
                String symbol = text(item, "code");
                String name = text(item, "name");
                if (symbol == null || name == null || !exchangeResolver.isSupportedAStockCode(symbol)) {
                    continue;
                }
                BigDecimal settlement = decimal(item, "settlement");
                BigDecimal high = decimal(item, "high");
                BigDecimal low = decimal(item, "low");
                BigDecimal amplitude = settlement.signum() == 0
                        ? BigDecimal.ZERO
                        : high.subtract(low).abs().multiply(BigDecimal.valueOf(100))
                        .divide(settlement, 4, java.math.RoundingMode.HALF_UP);
                rows.add(new MarketScreenerRow(
                        exchangeResolver.normalizeSymbol(symbol), name, exchangeResolver.exchangeOf(symbol), "待分类",
                        decimal(item, "trade"), decimal(item, "changepercent"), decimal(item, "amount"),
                        decimal(item, "turnoverratio"), amplitude, decimal(item, "per"), decimal(item, "pb")
                ));
            }
            if (data.size() < PAGE_SIZE) {
                break;
            }
        }
        if (rows.isEmpty()) {
            throw new IllegalStateException("Sina market screener returned no supported A-share quotes");
        }
        log.info("Sina market snapshot collected {} A-share rows", rows.size());
        return rows;
    }

    private JsonNode page(int page) {
        URI uri = UriComponentsBuilder.fromUriString(API)
                .queryParam("page", page).queryParam("num", PAGE_SIZE)
                .queryParam("sort", "symbol").queryParam("asc", 1)
                .queryParam("node", "hs_a").queryParam("symbol", "")
                .queryParam("_s_r_a", "init").build(true).toUri();
        HttpRequest request = HttpRequest.newBuilder().uri(uri).timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json,text/plain,*/*")
                .header("User-Agent", "Mozilla/5.0 FinSight")
                .header("Referer", "https://finance.sina.com.cn/").GET().build();
        try {
            String body;
            try {
                body = objectMapper.writeValueAsString(objectMapper.readTree(
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body()));
            } catch (IOException | InterruptedException ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                body = readWithCurl(uri, ex);
            }
            return objectMapper.readTree(body);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to fetch Sina market screener page " + page, ex);
        }
    }

    private String readWithCurl(URI uri, Exception cause) throws IOException {
        Process process = new ProcessBuilder("curl", "-fsSL", "--connect-timeout", "3", "--max-time", "8",
                "-A", "Mozilla/5.0 FinSight", "-H", "Referer: https://finance.sina.com.cn/", uri.toString())
                .redirectErrorStream(true).start();
        try {
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(1, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Sina curl fallback timed out", cause);
            }
            if (process.exitValue() != 0) {
                throw new IOException("Sina curl fallback failed: " + output, cause);
            }
            return output;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Sina curl fallback interrupted", ex);
        }
    }

    private String text(JsonNode item, String field) {
        String value = item.path(field).asText("").trim();
        return value.isEmpty() || "-".equals(value) ? null : value;
    }

    private BigDecimal decimal(JsonNode item, String field) {
        String value = text(item, field);
        if (value == null) return BigDecimal.ZERO;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ignored) {
            return BigDecimal.ZERO;
        }
    }
}
