package com.finsight.market;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExchangeResolverTest {
    private final ExchangeResolver resolver = new ExchangeResolver();

    @Test
    void resolvesMainlandAStockExchanges() {
        assertThat(resolver.exchangeOf("600519")).isEqualTo("SH");
        assertThat(resolver.exchangeOf("300750")).isEqualTo("SZ");
        assertThat(resolver.exchangeOf("920002")).isEqualTo("BJ");
    }

    @Test
    void validatesSupportedAStockCodes() {
        assertThat(resolver.isSupportedAStockCode("601398")).isTrue();
        assertThat(resolver.isSupportedAStockCode("002594")).isTrue();
        assertThat(resolver.isSupportedAStockCode("920002")).isTrue();
        assertThat(resolver.isSupportedAStockCode("12345")).isFalse();
    }

    @Test
    void trimsAndNormalizesSymbolsBeforeResolving() {
        assertThat(resolver.normalizeSymbol("  sh600519 ")).isEqualTo("SH600519");
        assertThat(resolver.sinaPrefix(" 600519 ")).isEqualTo("sh");
    }
}
