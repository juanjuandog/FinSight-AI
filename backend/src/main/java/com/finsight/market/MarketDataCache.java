package com.finsight.market;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Lightweight Caffeine-backed cache for market data so the chatty Eastmoney/Sina clients
 * do not stampede the upstream APIs during demos and backfill runs.
 */
@Component
public class MarketDataCache {
    private final Cache<String, MarketQuote> quoteCache;
    private final Cache<String, java.util.List<MarketCandle>> historyCache;

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

    public Optional<java.util.List<MarketCandle>> getHistory(String symbol, int limit) {
        return Optional.ofNullable(historyCache.getIfPresent(historyKey(symbol, limit)));
    }

    public void putHistory(String symbol, int limit, java.util.List<MarketCandle> candles) {
        historyCache.put(historyKey(symbol, limit), candles);
    }

    public void invalidate(String symbol) {
        quoteCache.invalidate(symbol);
        historyCache.asMap().keySet().removeIf(k -> k.startsWith(symbol + ":"));
    }

    private static String historyKey(String symbol, int limit) {
        return symbol + ":" + limit;
    }
}
