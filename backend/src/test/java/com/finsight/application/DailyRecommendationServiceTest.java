package com.finsight.application;

import com.finsight.market.EastmoneyMarketScreenerClient;
import com.finsight.market.MarketScreenerRow;
import com.finsight.market.SinaMarketScreenerClient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DailyRecommendationServiceTest {

    @Test
    void returnsConfiguredStrategyVersionAndAppliesConfiguredEligibility() {
        EastmoneyMarketScreenerClient primary = mock(EastmoneyMarketScreenerClient.class);
        SinaMarketScreenerClient fallback = mock(SinaMarketScreenerClient.class);
        when(primary.aShareSnapshot()).thenReturn(List.of(
                row("600001", "低成交候选", "100000000"),
                row("600002", "有效候选", "200000000")
        ));
        RecommendationStrategyProperties defaults = RecommendationStrategyProperties.defaults();
        RecommendationStrategyProperties strategy = new RecommendationStrategyProperties(
                "market-score-v2-test",
                new RecommendationStrategyProperties.Eligibility(
                        defaults.eligibility().minimumPrice(),
                        new BigDecimal("150000000"),
                        defaults.eligibility().minimumChangePercent()
                ),
                defaults.weights(),
                defaults.trend(),
                defaults.valuation(),
                defaults.risk()
        );

        DailyRecommendationService service = new DailyRecommendationService(
                primary, fallback, strategy, "Asia/Shanghai", 24
        );
        DailyRecommendationService.DailyRecommendations result = service.refresh();

        assertEquals("market-score-v2-test", result.strategyVersion());
        assertEquals(1, result.items().size());
        assertEquals("600002", result.items().get(0).symbol());
    }

    @Test
    void rejectsWeightsThatDoNotAddUpToOne() {
        RecommendationStrategyProperties defaults = RecommendationStrategyProperties.defaults();

        assertThrows(IllegalArgumentException.class, () -> new RecommendationStrategyProperties(
                "invalid-strategy",
                defaults.eligibility(),
                new RecommendationStrategyProperties.Weights(
                        new BigDecimal("0.5"), new BigDecimal("0.3"), new BigDecimal("0.3")
                ),
                defaults.trend(),
                defaults.valuation(),
                defaults.risk()
        ));
    }

    private MarketScreenerRow row(String symbol, String name, String amount) {
        return new MarketScreenerRow(
                symbol,
                name,
                "SH",
                "测试行业",
                new BigDecimal("10"),
                new BigDecimal("2"),
                new BigDecimal(amount),
                new BigDecimal("2"),
                new BigDecimal("4"),
                new BigDecimal("20"),
                new BigDecimal("3")
        );
    }
}
