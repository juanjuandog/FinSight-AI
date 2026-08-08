package com.finsight.market;

import java.math.BigDecimal;

public record MarketScreenerRow(
        String symbol,
        String name,
        String exchange,
        String industry,
        BigDecimal price,
        BigDecimal changePercent,
        BigDecimal amount,
        BigDecimal turnover,
        BigDecimal amplitude,
        BigDecimal pe,
        BigDecimal pb
) {
}
