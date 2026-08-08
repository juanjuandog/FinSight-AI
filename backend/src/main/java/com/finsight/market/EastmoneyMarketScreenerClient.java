package com.finsight.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Reads the public A-share market snapshot in pages. This is deliberately a
 * market-wide endpoint: the daily recommendation job must never derive its
 * candidates from a fixed list of hand-picked stocks.
 */
@Component
public class EastmoneyMarketScreenerClient {
    private static final Logger log = LoggerFactory.getLogger(EastmoneyMarketScreenerClient.class);
    private static final String API = "https://push2.eastmoney.com/api/qt/clist/get";
    private static final int PAGE_SIZE = 500;
    private static final int MAX_PAGES = 40;
    private static final int PAGE_ATTEMPTS = 2;
    private static final String A_SHARE_FILTER = "m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23,m:0+t:81,m:0+t:7,m:0+t:64,m:0+t:50";
    private static final String FIELDS = "f2,f3,f6,f7,f8,f9,f12,f13,f14,f20,f21,f23,f100";

    private final ObjectMapper objectMapper;
    private final ExchangeResolver exchangeResolver;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public EastmoneyMarketScreenerClient(ObjectMapper objectMapper, ExchangeResolver exchangeResolver) {
        this.objectMapper = objectMapper;
        this.exchangeResolver = exchangeResolver;
    }

    public List<MarketScreenerRow> aShareSnapshot() {
        List<MarketScreenerRow> rows = new ArrayList<>();
        int total = Integer.MAX_VALUE;
        for (int page = 1; page <= MAX_PAGES && rows.size() < total; page++) {
            JsonNode data;
            try {
                data = pageWithRetry(page);
            } catch (IllegalStateException ex) {
                if (rows.isEmpty()) {
                    throw ex;
                }
                // A transient error from a later page must not discard the market
                // snapshot already collected for today's recommendation.
                log.warn("Market snapshot stopped at page {} after collecting {} rows; using the partial snapshot", page, rows.size(), ex);
                break;
            }
            total = data.path("total").asInt(total);
            JsonNode diff = data.path("diff");
            if (!diff.isArray() || diff.isEmpty()) {
                break;
            }
            for (JsonNode item : diff) {
                String symbol = text(item, "f12");
                String name = text(item, "f14");
                if (symbol == null || name == null || !exchangeResolver.isSupportedAStockCode(symbol)) {
                    continue;
                }
                rows.add(new MarketScreenerRow(
                        exchangeResolver.normalizeSymbol(symbol),
                        name,
                        exchangeResolver.exchangeOf(symbol),
                        text(item, "f100") == null ? "待分类" : text(item, "f100"),
                        decimal(item, "f2"), decimal(item, "f3"), decimal(item, "f6"),
                        decimal(item, "f8"), decimal(item, "f7"), decimal(item, "f9"), decimal(item, "f23")
                ));
            }
        }
        if (rows.isEmpty()) {
            throw new IllegalStateException("market screener returned no supported A-share quotes");
        }
        return rows;
    }

    private JsonNode pageWithRetry(int page) {
        IllegalStateException failure = null;
        for (int attempt = 1; attempt <= PAGE_ATTEMPTS; attempt++) {
            try {
                return page(page);
            } catch (IllegalStateException ex) {
                failure = ex;
                if (attempt < PAGE_ATTEMPTS) {
                    log.info("Market snapshot page {} failed (attempt {}/{}); retrying", page, attempt, PAGE_ATTEMPTS);
                    try {
                        Thread.sleep(250L * attempt);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("market screener retry interrupted", interrupted);
                    }
                }
            }
        }
        throw failure == null ? new IllegalStateException("market screener page failed") : failure;
    }

    private JsonNode page(int page) {
        URI uri = UriComponentsBuilder.fromUriString(API)
                .queryParam("pn", page).queryParam("pz", PAGE_SIZE).queryParam("po", 1).queryParam("np", 1)
                .queryParam("ut", "bd1d9ddb04089700cf9c27f6f7426281").queryParam("fltt", 2).queryParam("invt", 2)
                .queryParam("fid", "f6").queryParam("fs", A_SHARE_FILTER).queryParam("fields", FIELDS)
                .build(true).toUri();
        HttpRequest request = HttpRequest.newBuilder().uri(uri).timeout(Duration.ofSeconds(6))
                .header("User-Agent", "Mozilla/5.0 FinSight").header("Referer", "https://quote.eastmoney.com/").GET().build();
        try {
            return parseResponse(httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body());
        } catch (IOException ex) {
            try {
                return parseResponse(readWithCurl(uri, ex));
            } catch (IOException fallback) {
                throw new IllegalStateException("failed to fetch market screener", fallback);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("market screener request interrupted", ex);
        }
    }

    private JsonNode parseResponse(String body) throws IOException {
        JsonNode json = objectMapper.readTree(body);
        if (json.path("rc").asInt(-1) != 0) {
            throw new IllegalStateException("market screener rejected request: rc=" + json.path("rc").asText());
        }
        return json.path("data");
    }

    private String readWithCurl(URI uri, IOException cause) throws IOException {
        Process process = new ProcessBuilder("curl", "-fsSL", "--connect-timeout", "3", "--max-time", "6", "-A", "Mozilla/5.0 FinSight",
                "-H", "Referer: https://quote.eastmoney.com/", uri.toString()).redirectErrorStream(true).start();
        try {
            // Read while curl is running. Waiting before consuming stdout can
            // deadlock once a 500-row JSON page fills the process pipe buffer.
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(1, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("market screener curl fallback timed out", cause);
            }
            if (process.exitValue() != 0) {
                throw new IOException("market screener curl fallback failed: " + output, cause);
            }
            return output;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("market screener curl fallback interrupted", ex);
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
