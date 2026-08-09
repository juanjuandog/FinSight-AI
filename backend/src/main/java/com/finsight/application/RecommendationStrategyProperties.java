package com.finsight.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "finsight.recommendations.strategy")
public record RecommendationStrategyProperties(
        @DefaultValue("market-score-v1") String version,
        @DefaultValue Eligibility eligibility,
        @DefaultValue Weights weights,
        @DefaultValue Trend trend,
        @DefaultValue Valuation valuation,
        @DefaultValue Risk risk
) {
    private static final BigDecimal EXPECTED_WEIGHT_TOTAL = BigDecimal.ONE;
    private static final BigDecimal WEIGHT_TOLERANCE = new BigDecimal("0.0001");

    public RecommendationStrategyProperties {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("recommendation strategy version must not be blank");
        }
        BigDecimal total = weights.trend()
                .add(weights.liquidity())
                .add(weights.valuation());
        if (total.subtract(EXPECTED_WEIGHT_TOTAL).abs().compareTo(WEIGHT_TOLERANCE) > 0) {
            throw new IllegalArgumentException("recommendation strategy weights must add up to 1.0");
        }
    }

    public static RecommendationStrategyProperties defaults() {
        return new RecommendationStrategyProperties(
                "market-score-v1",
                new Eligibility(new BigDecimal("2"), new BigDecimal("80000000"), new BigDecimal("-9.5")),
                new Weights(new BigDecimal("0.48"), new BigDecimal("0.24"), new BigDecimal("0.28")),
                new Trend(new BigDecimal("52"), new BigDecimal("7"), new BigDecimal("5"), new BigDecimal("2.2")),
                new Valuation(
                        new BigDecimal("68"), new BigDecimal("45"), new BigDecimal("80"),
                        new BigDecimal("12"), new BigDecimal("3"), new BigDecimal("12"),
                        new BigDecimal("8"), new BigDecimal("8"), new BigDecimal("10")
                ),
                new Risk(new BigDecimal("7"), new BigDecimal("1.4"), new BigDecimal("7"), new BigDecimal("2.2"))
        );
    }

    public record Eligibility(
            @DefaultValue("2") BigDecimal minimumPrice,
            @DefaultValue("80000000") BigDecimal minimumAmount,
            @DefaultValue("-9.5") BigDecimal minimumChangePercent
    ) { }

    public record Weights(
            @DefaultValue("0.48") BigDecimal trend,
            @DefaultValue("0.24") BigDecimal liquidity,
            @DefaultValue("0.28") BigDecimal valuation
    ) { }

    public record Trend(
            @DefaultValue("52") BigDecimal baseScore,
            @DefaultValue("7") BigDecimal changeMultiplier,
            @DefaultValue("5") BigDecimal amplitudeThreshold,
            @DefaultValue("2.2") BigDecimal amplitudePenaltyMultiplier
    ) { }

    public record Valuation(
            @DefaultValue("68") BigDecimal baseScore,
            @DefaultValue("45") BigDecimal preferredPeMax,
            @DefaultValue("80") BigDecimal acceptablePeMax,
            @DefaultValue("12") BigDecimal preferredPeBonus,
            @DefaultValue("3") BigDecimal acceptablePeBonus,
            @DefaultValue("12") BigDecimal pePenalty,
            @DefaultValue("8") BigDecimal preferredPbMax,
            @DefaultValue("8") BigDecimal preferredPbBonus,
            @DefaultValue("10") BigDecimal pbPenalty
    ) { }

    public record Risk(
            @DefaultValue("7") BigDecimal amplitudeThreshold,
            @DefaultValue("1.4") BigDecimal amplitudePenaltyMultiplier,
            @DefaultValue("7") BigDecimal absoluteChangeThreshold,
            @DefaultValue("2.2") BigDecimal changePenaltyMultiplier
    ) { }
}
