package com.finsight.market;

import java.time.Instant;
import java.util.List;

/**
 * A provenance-aware response for historical market data.
 *
 * <p>The response deliberately keeps an unavailable result distinct from a
 * successful result containing locally generated demo candles.  Callers can
 * therefore decide whether it is safe to render or persist the data without
 * guessing from an empty list or a hidden fallback.</p>
 */
public record MarketHistoryResponse(
        List<MarketCandle> candles,
        String source,
        Instant fetchedAt,
        boolean simulated,
        boolean available,
        String error
) {
    public MarketHistoryResponse {
        candles = candles == null ? List.of() : List.copyOf(candles);
    }

    public static MarketHistoryResponse live(List<MarketCandle> candles, Instant fetchedAt) {
        return new MarketHistoryResponse(
                candles,
                "EASTMONEY_HISTORY",
                fetchedAt,
                false,
                true,
                null
        );
    }

    public static MarketHistoryResponse demo(List<MarketCandle> candles, Instant fetchedAt) {
        return new MarketHistoryResponse(
                candles,
                "LOCAL_DEMO",
                fetchedAt,
                true,
                true,
                null
        );
    }

    public static MarketHistoryResponse unavailable(String source, Instant fetchedAt, String error) {
        return new MarketHistoryResponse(
                List.of(),
                source,
                fetchedAt,
                false,
                false,
                error
        );
    }
}
