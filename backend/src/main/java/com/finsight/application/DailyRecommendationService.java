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
    private volatile DailyRecommendations latest;

    public DailyRecommendationService(
            EastmoneyMarketScreenerClient screenerClient,
            SinaMarketScreenerClient fallbackScreenerClient,
            @Value("${finsight.scheduler.zone:Asia/Shanghai}") String zone,
            @Value("${finsight.recommendations.result-limit:24}") int resultLimit
    ) {
        this.screenerClient = screenerClient;
        this.fallbackScreenerClient = fallbackScreenerClient;
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
        latest = new DailyRecommendations(LocalDate.now(zone), OffsetDateTime.now(zone), market.size(), source, items);
        return latest;
    }

    private boolean eligible(MarketScreenerRow row) {
        return row.price().compareTo(BigDecimal.valueOf(2)) >= 0
                && row.amount().compareTo(BigDecimal.valueOf(80_000_000L)) >= 0
                && !row.name().contains("ST") && !row.name().contains("退")
                && row.changePercent().compareTo(BigDecimal.valueOf(-9.5)) > 0;
    }

    private ScoredCandidate score(MarketScreenerRow row, BigDecimal maximumAmount) {
        double change = row.changePercent().doubleValue();
        double amplitude = row.amplitude().doubleValue();
        double trend = clamp(52 + change * 7 - Math.max(0, amplitude - 5) * 2.2);
        double liquidity = maximumAmount.signum() == 0 ? 0 : clamp(Math.log1p(row.amount().doubleValue()) / Math.log1p(maximumAmount.doubleValue()) * 100);
        double valuation = valuationScore(row.pe(), row.pb());
        double risk = clamp(Math.max(0, amplitude - 7) * 1.4 + Math.max(0, Math.abs(change) - 7) * 2.2);
        BigDecimal total = BigDecimal.valueOf(clamp(trend * .48 + liquidity * .24 + valuation * .28 - risk))
                .setScale(1, RoundingMode.HALF_UP);
        return new ScoredCandidate(row, total, round(trend), round(valuation), round(liquidity), round(risk));
    }

    private double valuationScore(BigDecimal pe, BigDecimal pb) {
        double score = 68;
        if (pe.signum() > 0) score += pe.doubleValue() <= 45 ? 12 : pe.doubleValue() <= 80 ? 3 : -12;
        if (pb.signum() > 0) score += pb.doubleValue() <= 8 ? 8 : -10;
        return clamp(score);
    }

    private BigDecimal round(double value) { return BigDecimal.valueOf(clamp(value)).setScale(0, RoundingMode.HALF_UP); }
    private double clamp(double value) { return Math.max(0, Math.min(100, value)); }

    private record ScoredCandidate(MarketScreenerRow row, BigDecimal score, BigDecimal trendScore, BigDecimal qualityScore, BigDecimal liquidityScore, BigDecimal riskPenalty) { }

    public record DailyRecommendations(LocalDate tradeDate, OffsetDateTime scannedAt, int universeSize, String source, List<RecommendationItem> items) { }
    public record RecommendationItem(String symbol, String name, String exchange, String industry, BigDecimal score, BigDecimal trendScore, BigDecimal qualityScore, BigDecimal liquidityScore, BigDecimal riskPenalty, BigDecimal changePercent) { }
}
