package com.finsight.application;

import com.finsight.market.EastmoneyMarketScreenerClient;
import com.finsight.market.MarketScreenerRow;
import com.finsight.market.SinaMarketScreenerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Produces one cached, market-wide ranking per trading day. */
@Service
public class DailyRecommendationService {
    private static final Logger log = LoggerFactory.getLogger(DailyRecommendationService.class);
    private final EastmoneyMarketScreenerClient screenerClient;
    private final SinaMarketScreenerClient fallbackScreenerClient;
    private final ZoneId zone;
    private final int resultLimit;
    private final RecommendationStrategyProperties strategy;
    private volatile DailyRecommendations latest;

    public DailyRecommendationService(
            EastmoneyMarketScreenerClient screenerClient,
            SinaMarketScreenerClient fallbackScreenerClient,
            RecommendationStrategyProperties strategy,
            @Value("${finsight.scheduler.zone:Asia/Shanghai}") String zone,
            @Value("${finsight.recommendations.result-limit:24}") int resultLimit
    ) {
        this.screenerClient = screenerClient;
        this.fallbackScreenerClient = fallbackScreenerClient;
        this.strategy = strategy;
        this.zone = ZoneId.of(zone);
        this.resultLimit = Math.max(5, Math.min(resultLimit, 100));
    }

    public DailyRecommendations today() {
        DailyRecommendations current = latest;
        if (current != null && current.tradeDate().equals(LocalDate.now(zone))) {
            return current;
        }
        return refresh();
    }

    public synchronized DailyRecommendations refresh() {
        List<MarketScreenerRow> market;
        String source;
        try {
            market = screenerClient.aShareSnapshot();
            source = "eastmoney-public-snapshot";
        } catch (RuntimeException primaryFailure) {
            log.warn("Eastmoney market snapshot unavailable; switching to Sina fallback", primaryFailure);
            market = fallbackScreenerClient.aShareSnapshot();
            source = "sina-public-snapshot-fallback";
        }
        BigDecimal maximumAmount = market.stream().map(MarketScreenerRow::amount).max(Comparator.naturalOrder()).orElse(BigDecimal.ONE);
        List<ScoredCandidate> candidates = new ArrayList<>();
        for (MarketScreenerRow row : market) {
            if (!eligible(row)) continue;
            candidates.add(score(row, maximumAmount));
        }
        List<RecommendationItem> items = candidates.stream()
                .sorted(Comparator.comparing(ScoredCandidate::score).reversed()
                        .thenComparing(candidate -> candidate.row().symbol()))
                .limit(resultLimit)
                .map(candidate -> new RecommendationItem(
                        candidate.row().symbol(), candidate.row().name(), candidate.row().exchange(), candidate.row().industry(),
                        candidate.score(), candidate.trendScore(), candidate.qualityScore(), candidate.liquidityScore(), candidate.riskPenalty(),
                        candidate.row().changePercent()))
                .toList();
        if (items.isEmpty()) throw new IllegalStateException("market screener produced no eligible candidates");
        latest = new DailyRecommendations(
                LocalDate.now(zone), OffsetDateTime.now(zone), market.size(), source, strategy.version(), items
        );
        return latest;
    }

    private boolean eligible(MarketScreenerRow row) {
        RecommendationStrategyProperties.Eligibility eligibility = strategy.eligibility();
        return row.price().compareTo(eligibility.minimumPrice()) >= 0
                && row.amount().compareTo(eligibility.minimumAmount()) >= 0
                && !row.name().contains("ST") && !row.name().contains("退")
                && row.changePercent().compareTo(eligibility.minimumChangePercent()) > 0;
    }

    private ScoredCandidate score(MarketScreenerRow row, BigDecimal maximumAmount) {
        RecommendationStrategyProperties.Trend trendRules = strategy.trend();
        RecommendationStrategyProperties.Risk riskRules = strategy.risk();
        RecommendationStrategyProperties.Weights weights = strategy.weights();
        double change = row.changePercent().doubleValue();
        double amplitude = row.amplitude().doubleValue();
        double trend = clamp(
                trendRules.baseScore().doubleValue()
                        + change * trendRules.changeMultiplier().doubleValue()
                        - Math.max(0, amplitude - trendRules.amplitudeThreshold().doubleValue())
                        * trendRules.amplitudePenaltyMultiplier().doubleValue()
        );
        double liquidity = maximumAmount.signum() == 0 ? 0 : clamp(Math.log1p(row.amount().doubleValue()) / Math.log1p(maximumAmount.doubleValue()) * 100);
        double valuation = valuationScore(row.pe(), row.pb());
        double risk = clamp(
                Math.max(0, amplitude - riskRules.amplitudeThreshold().doubleValue())
                        * riskRules.amplitudePenaltyMultiplier().doubleValue()
                        + Math.max(0, Math.abs(change) - riskRules.absoluteChangeThreshold().doubleValue())
                        * riskRules.changePenaltyMultiplier().doubleValue()
        );
        BigDecimal total = BigDecimal.valueOf(clamp(
                        trend * weights.trend().doubleValue()
                                + liquidity * weights.liquidity().doubleValue()
                                + valuation * weights.valuation().doubleValue()
                                - risk
                ))
                .setScale(1, RoundingMode.HALF_UP);
        return new ScoredCandidate(row, total, round(trend), round(valuation), round(liquidity), round(risk));
    }

    private double valuationScore(BigDecimal pe, BigDecimal pb) {
        RecommendationStrategyProperties.Valuation rules = strategy.valuation();
        double score = rules.baseScore().doubleValue();
        if (pe.signum() > 0) {
            score += pe.compareTo(rules.preferredPeMax()) <= 0
                    ? rules.preferredPeBonus().doubleValue()
                    : pe.compareTo(rules.acceptablePeMax()) <= 0
                    ? rules.acceptablePeBonus().doubleValue()
                    : -rules.pePenalty().doubleValue();
        }
        if (pb.signum() > 0) {
            score += pb.compareTo(rules.preferredPbMax()) <= 0
                    ? rules.preferredPbBonus().doubleValue()
                    : -rules.pbPenalty().doubleValue();
        }
        return clamp(score);
    }

    private BigDecimal round(double value) { return BigDecimal.valueOf(clamp(value)).setScale(0, RoundingMode.HALF_UP); }
    private double clamp(double value) { return Math.max(0, Math.min(100, value)); }

    private record ScoredCandidate(MarketScreenerRow row, BigDecimal score, BigDecimal trendScore, BigDecimal qualityScore, BigDecimal liquidityScore, BigDecimal riskPenalty) { }

    public record DailyRecommendations(LocalDate tradeDate, OffsetDateTime scannedAt, int universeSize, String source, String strategyVersion, List<RecommendationItem> items) { }
    public record RecommendationItem(String symbol, String name, String exchange, String industry, BigDecimal score, BigDecimal trendScore, BigDecimal qualityScore, BigDecimal liquidityScore, BigDecimal riskPenalty, BigDecimal changePercent) { }
}
