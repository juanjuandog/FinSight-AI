package com.finsight.market;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Lightweight Caffeine-backed cache for market data so the chatty Eastmoney/Sina clients
 * do not stampede the upstream APIs during demos and backfill runs.
 */
@Component
public class MarketDataCache {
    private final Cache<String, MarketQuote> quoteCache;
    private final Cache<String, MarketHistoryResponse> historyCache;

    public MarketDataCache() {
        this.quoteCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(1))
                .maximumSize(2_000)
                .build();
        this.historyCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(5))
                .maximumSize(1_000)
                .build();
    }

    public Optional<MarketQuote> getQuote(String symbol) {
        return Optional.ofNullable(quoteCache.getIfPresent(symbol));
    }

    public void putQuote(String symbol, MarketQuote quote) {
        quoteCache.put(symbol, quote);
    }

    /**
     * Backwards-compatible list view for callers that only need real-mode candles.
     */
    public Optional<List<MarketCandle>> getHistory(String symbol, int limit) {
        return getHistoryResponse(symbol, limit, false).map(MarketHistoryResponse::candles);
    }

    /**
     * Backwards-compatible real-mode write used by lightweight cache tests and older callers.
     */
    public void putHistory(String symbol, int limit, List<MarketCandle> candles) {
        putHistoryResponse(symbol, limit, false, new MarketHistoryResponse(
                candles,
                "EASTMONEY_HISTORY",
                Instant.now(),
                false,
                candles != null && !candles.isEmpty(),
                null
        ));
    }

    public Optional<MarketHistoryResponse> getHistoryResponse(String symbol, int limit, boolean demo) {
        return Optional.ofNullable(historyCache.getIfPresent(historyKey(symbol, limit, demo)));
    }

    public void putHistoryResponse(String symbol, int limit, boolean demo, MarketHistoryResponse response) {
        historyCache.put(historyKey(symbol, limit, demo), response);
    }

    public void invalidate(String symbol) {
        quoteCache.invalidate(symbol);
        historyCache.asMap().keySet().removeIf(k -> k.startsWith(symbol + ":"));
    }

    private static String historyKey(String symbol, int limit, boolean demo) {
        return symbol + ":" + limit + ":" + demo;
    }
}
