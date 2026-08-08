package com.finsight.api;

import com.finsight.application.DailyRecommendationService;
import com.finsight.market.MarketDataService;
import com.finsight.market.MarketCandle;
import com.finsight.market.MarketQuote;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/market")
public class MarketController {
    private final MarketDataService marketDataService;
    private final DailyRecommendationService dailyRecommendationService;

    public MarketController(MarketDataService marketDataService, DailyRecommendationService dailyRecommendationService) {
        this.marketDataService = marketDataService;
        this.dailyRecommendationService = dailyRecommendationService;
    }

    @GetMapping("/quotes/{symbol}")
    public MarketQuote quote(@PathVariable String symbol) {
        return marketDataService.quote(symbol);
    }

    @GetMapping("/history/{symbol}")
    public List<MarketCandle> history(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "120") int limit
    ) {
        return marketDataService.history(symbol, limit);
    }

    @GetMapping("/recommendations")
    public DailyRecommendationService.DailyRecommendations recommendations() {
        return dailyRecommendationService.today();
    }

    @PostMapping("/recommendations/refresh")
    public DailyRecommendationService.DailyRecommendations refreshRecommendations() {
        return dailyRecommendationService.refresh();
    }
}
