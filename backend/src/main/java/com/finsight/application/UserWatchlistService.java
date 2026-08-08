package com.finsight.application;

import com.finsight.domain.model.UserWatchlistItem;
import com.finsight.domain.repository.UserWatchlistRepository;
import com.finsight.market.ExchangeResolver;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserWatchlistService {
    private final UserWatchlistRepository userWatchlistRepository;
    private final StockUniverseService stockUniverseService;
    private final ExchangeResolver exchangeResolver;

    public UserWatchlistService(
            UserWatchlistRepository userWatchlistRepository,
            StockUniverseService stockUniverseService,
            ExchangeResolver exchangeResolver
    ) {
        this.userWatchlistRepository = userWatchlistRepository;
        this.stockUniverseService = stockUniverseService;
        this.exchangeResolver = exchangeResolver;
    }

    public List<UserWatchlistItem> list(String userId) {
        return userWatchlistRepository.findByUserId(normalizeUser(userId));
    }

    public List<UserWatchlistItem> add(String userId, String symbol) {
        String normalizedSymbol = exchangeResolver.normalizeSymbol(symbol);
        stockUniverseService.resolveAStock(normalizedSymbol);
        userWatchlistRepository.add(normalizeUser(userId), normalizedSymbol);
        return list(userId);
    }

    public List<UserWatchlistItem> remove(String userId, String symbol) {
        userWatchlistRepository.remove(normalizeUser(userId), exchangeResolver.normalizeSymbol(symbol));
        return list(userId);
    }

    private String normalizeUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("用户身份不能为空。");
        }
        return userId.trim();
    }
}
