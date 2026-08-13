package com.finsight.market;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarketDataCacheTest {

    @Test
    void storesQuoteAndHistorySeparately() {
        MarketDataCache cache = new MarketDataCache();
        MarketQuote quote = new MarketQuote(
                "600519", "SH", "贵州茅台", BigDecimal.TEN, BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, LocalDate.now(), LocalTime.now(), "REALTIME", true, null
        );
        cache.putQuote("600519", quote);
        assertThat(cache.getQuote("600519")).contains(quote);

        List<MarketCandle> candles = List.of();
        cache.putHistory("600519", 60, candles);
        assertThat(cache.getHistory("600519", 60)).contains(candles);
        assertThat(cache.getHistory("600519", 120)).isEmpty();
    }

    @Test
    void invalidationClearsBothBuckets() {
        MarketDataCache cache = new MarketDataCache();
        cache.putQuote("600519", new MarketQuote(
                "600519", "SH", "贵州茅台", BigDecimal.ONE, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, LocalDate.now(), LocalTime.now(), "REALTIME", true, null
        ));
        cache.putHistory("600519", 60, List.of());

        cache.invalidate("600519");

        assertThat(cache.getQuote("600519")).isEmpty();
        assertThat(cache.getHistory("600519", 60)).isEmpty();
    }
}
