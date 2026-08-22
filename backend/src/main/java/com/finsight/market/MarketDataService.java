package com.finsight.market;

import com.finsight.domain.model.Company;
import com.finsight.domain.repository.CompanyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class MarketDataService {
    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);
    private static final String HISTORY_SOURCE = "EASTMONEY_HISTORY";
    private static final String HISTORY_UNAVAILABLE = "历史行情暂不可用";

    private final MarketDataClient marketDataClient;
    private final EastmoneyMarketHistoryClient marketHistoryClient;
    private final CompanyRepository companyRepository;
    private final ExchangeResolver exchangeResolver;
    private final MarketDataCache cache;

    public MarketDataService(
            MarketDataClient marketDataClient,
            EastmoneyMarketHistoryClient marketHistoryClient,
            CompanyRepository companyRepository,
            ExchangeResolver exchangeResolver,
            MarketDataCache cache
    ) {
        this.marketDataClient = marketDataClient;
        this.marketHistoryClient = marketHistoryClient;
        this.companyRepository = companyRepository;
        this.exchangeResolver = exchangeResolver;
        this.cache = cache;
    }

    public MarketQuote quote(String symbol) {
        String normalized = exchangeResolver.normalizeSymbol(symbol);
        return cache.getQuote(normalized).orElseGet(() -> fetchQuote(normalized));
    }

    private MarketQuote fetchQuote(String normalized) {
        try {
            MarketQuote quote = marketDataClient.quote(normalized);
            companyRepository.save(new Company(
                    quote.symbol(),
                    quote.name(),
                    quote.exchange(),
                    companyRepository.findBySymbol(normalized).map(Company::industry).orElse("待分类")
            ));
            cache.putQuote(normalized, quote);
            return quote;
        } catch (RuntimeException ex) {
            MarketQuote fallback = fallbackQuote(normalized, ex.getMessage());
            cache.putQuote(normalized, fallback);
            return fallback;
        }
    }

    /**
     * Returns real historical data by default.  Synthetic candles are only generated when the
     * caller explicitly opts into demo mode.
     */
    public List<MarketCandle> history(String symbol, int limit) {
        return history(symbol, limit, false).candles();
    }

    public MarketHistoryResponse history(String symbol, int limit, boolean demo) {
        String normalized = exchangeResolver.normalizeSymbol(symbol);
        int bounded = Math.min(Math.max(limit, 20), 260);
        return cache.getHistoryResponse(normalized, bounded, demo)
                .orElseGet(() -> fetchHistory(normalized, bounded, demo));
    }

    private MarketHistoryResponse fetchHistory(String normalized, int bounded, boolean demo) {
        try {
            List<MarketCandle> candles = marketHistoryClient.daily(normalized, bounded);
            if (candles != null && !candles.isEmpty()) {
                MarketHistoryResponse response = MarketHistoryResponse.live(candles, Instant.now());
                cache.putHistoryResponse(normalized, bounded, demo, response);
                return response;
            }
        } catch (RuntimeException ex) {
            log.warn("Historical market data unavailable for {}: {}", normalized, ex.getMessage());
        }

        if (demo) {
            MarketHistoryResponse response = MarketHistoryResponse.demo(fallbackHistory(normalized, bounded), Instant.now());
            cache.putHistoryResponse(normalized, bounded, true, response);
            return response;
        }

        // Do not cache an unavailable response: a retry should be able to observe a recovered provider.
        return MarketHistoryResponse.unavailable(HISTORY_SOURCE, Instant.now(), HISTORY_UNAVAILABLE);
    }

    private MarketQuote fallbackQuote(String symbol, String reason) {
        Company company = companyRepository.findBySymbol(symbol)
                .orElse(new Company(symbol, "股票 " + symbol, exchangeResolver.exchangeOf(symbol), "待分类"));
        companyRepository.save(company);
        return new MarketQuote(
                symbol,
                company.exchange() == null || company.exchange().isBlank() ? exchangeResolver.exchangeOf(symbol) : company.exchange(),
                company.name(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                LocalDate.now(),
                LocalTime.now().withNano(0),
                "FALLBACK",
                false,
                "实时行情暂不可用，已降级到本地分析数据：" + reason
        );
    }

    private List<MarketCandle> fallbackHistory(String symbol, int limit) {
        BigDecimal base = quote(symbol).currentPrice();
        if (base.compareTo(BigDecimal.ZERO) <= 0) {
            base = BigDecimal.valueOf(80 + Math.abs(symbol.hashCode() % 120));
        }
        List<MarketCandle> candles = new ArrayList<>();
        LocalDate cursor = LocalDate.now().minusDays(limit + 30L);
        BigDecimal previous = base.multiply(BigDecimal.valueOf(0.96));
        double momentum = 0;
        while (candles.size() < limit) {
            cursor = cursor.plusDays(1);
            if (cursor.getDayOfWeek().getValue() >= 6) {
                continue;
            }
            int index = candles.size();
            double dailyNoise = deterministicNoise(symbol, index, 0) * 0.018;
            double openGap = deterministicNoise(symbol, index, 1) * 0.006;
            double intradayRange = 0.006 + Math.abs(deterministicNoise(symbol, index, 2)) * 0.016;
            double eventShock = index % 37 == 0 ? deterministicNoise(symbol, index, 3) * 0.035 : 0;
            double meanReversion = base.subtract(previous).doubleValue() / Math.max(base.doubleValue(), 1) * 0.018;
            momentum = momentum * 0.62 + dailyNoise * 0.38;
            double closeReturn = Math.max(-0.07, Math.min(0.07, momentum + meanReversion + eventShock));
            BigDecimal open = previous.multiply(BigDecimal.valueOf(1 + openGap)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal close = previous.multiply(BigDecimal.valueOf(1 + closeReturn)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal high = open.max(close).multiply(BigDecimal.valueOf(1 + intradayRange)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal low = open.min(close).multiply(BigDecimal.valueOf(1 - intradayRange)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal change = close.subtract(previous).setScale(2, RoundingMode.HALF_UP);
            BigDecimal changePercent = previous.compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO
                    : change.multiply(BigDecimal.valueOf(100)).divide(previous, 2, RoundingMode.HALF_UP);
            long volume = Math.round(18000L + Math.abs(deterministicNoise(symbol, index, 4)) * 14000L + index * 31L);
            candles.add(new MarketCandle(
                    cursor,
                    open,
                    close,
                    high,
                    low,
                    volume,
                    close.multiply(BigDecimal.valueOf(volume)).setScale(2, RoundingMode.HALF_UP),
                    high.subtract(low).multiply(BigDecimal.valueOf(100)).divide(previous, 2, RoundingMode.HALF_UP),
                    changePercent,
                    change,
                    BigDecimal.ZERO
            ));
            previous = close;
        }
        return candles;
    }

    private double deterministicNoise(String symbol, int index, int salt) {
        long value = 1469598103934665603L;
        String input = symbol + ":" + index + ":" + salt;
        for (int i = 0; i < input.length(); i++) {
            value ^= input.charAt(i);
            value *= 1099511628211L;
        }
        long positive = value & Long.MAX_VALUE;
        return positive / (double) Long.MAX_VALUE * 2 - 1;
    }
}
