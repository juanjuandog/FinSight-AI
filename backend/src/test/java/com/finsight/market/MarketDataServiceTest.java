package com.finsight.market;

import com.finsight.domain.repository.CompanyRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MarketDataServiceTest {

    private final MarketDataClient marketDataClient = mock(MarketDataClient.class);
    private final EastmoneyMarketHistoryClient marketHistoryClient = mock(EastmoneyMarketHistoryClient.class);
    private final CompanyRepository companyRepository = mock(CompanyRepository.class);
    private final MarketDataService service = new MarketDataService(
            marketDataClient,
            marketHistoryClient,
            companyRepository,
            new ExchangeResolver(),
            new MarketDataCache()
    );

    @Test
    void providerFailureReturnsUnavailableWithoutSyntheticCandles() {
        when(marketHistoryClient.daily("600519", 120))
                .thenThrow(new IllegalStateException("provider offline"));

        MarketHistoryResponse response = service.history("600519", 120, false);

        assertThat(response.candles()).isEmpty();
        assertThat(response.available()).isFalse();
        assertThat(response.simulated()).isFalse();
        assertThat(response.source()).isEqualTo("EASTMONEY_HISTORY");
        assertThat(response.fetchedAt()).isNotNull();
        assertThat(response.error()).isEqualTo("历史行情暂不可用");
        verifyNoInteractions(marketDataClient);
    }

    @Test
    void explicitDemoModeKeepsDeterministicFallbackButMarksItAsSimulated() {
        when(marketHistoryClient.daily("600519", 120))
                .thenThrow(new IllegalStateException("provider offline"));
        when(marketDataClient.quote("600519")).thenReturn(quote());

        MarketHistoryResponse response = service.history("600519", 120, true);

        assertThat(response.candles()).hasSize(120);
        assertThat(response.available()).isTrue();
        assertThat(response.simulated()).isTrue();
        assertThat(response.source()).isEqualTo("LOCAL_DEMO");
        assertThat(response.fetchedAt()).isNotNull();
        assertThat(response.error()).isNull();
        verify(marketDataClient).quote("600519");
    }

    @Test
    void successfulHistoryIsCachedWithItsFetchTimestamp() {
        List<MarketCandle> candles = List.of(candle());
        when(marketHistoryClient.daily("600519", 20)).thenReturn(candles);

        MarketHistoryResponse first = service.history("600519", 1, false);
        MarketHistoryResponse second = service.history("600519", 1, false);

        assertThat(first.candles()).containsExactly(candle());
        assertThat(second).isEqualTo(first);
        assertThat(first.source()).isEqualTo("EASTMONEY_HISTORY");
        assertThat(first.available()).isTrue();
        verify(marketHistoryClient).daily("600519", 20);
    }

    private MarketQuote quote() {
        return new MarketQuote(
                "600519", "SH", "贵州茅台",
                new BigDecimal("1600"), new BigDecimal("1580"),
                new BigDecimal("1590"), new BigDecimal("1610"), new BigDecimal("1575"),
                new BigDecimal("20"), new BigDecimal("1.27"),
                LocalDate.now(), LocalTime.NOON, "EASTMONEY_QUOTE", true, null
        );
    }

    private MarketCandle candle() {
        return new MarketCandle(
                LocalDate.of(2026, 8, 20),
                new BigDecimal("10"), new BigDecimal("11"),
                new BigDecimal("12"), new BigDecimal("9"),
                100L, new BigDecimal("1100"), new BigDecimal("30"),
                new BigDecimal("10"), new BigDecimal("1"), BigDecimal.ZERO
        );
    }
}
