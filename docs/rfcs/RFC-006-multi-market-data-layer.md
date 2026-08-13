# RFC 006: Multi-Market Data Abstraction Layer

- **Status:** Draft
- **Date:** 2026-08-13
- **Author:** FinSight contributors
- **Tracker:** Issue TBD

## Context

`MarketDataService` and the various vendor clients in
`backend/src/main/java/com/finsight/market/` are A-share specific:
`EastmoneyMarketHistoryClient` calls `eastmoney.com`, `SinaMarketDataClient`
calls `hq.sinajs.cn`. `ExchangeResolver.exchangeOf(symbol)` only knows
SH/SZ/BJ. The README promises A-share research; this RFC widens the
platform to HK (港股) and US (美股) markets without rewriting every
downstream consumer (`StockAiAnalysisService`,
`DailyRecommendationService`, the static frontend).

## Goals

1. Introduce a `MarketAdapter` interface plus three implementations
   (`AshareMarketAdapter`, `HkexMarketAdapter`, `UsMarketAdapter`)
   that fan out from one entry point.
2. Extend `Quote`, `MarketCandle`, and `Company` domain objects to
   carry `market`, `currency`, `lotSize`, and `marketSession`.
3. Add HK and US vendor clients (Yahoo Finance adapter + akshare's
   HKEX adapter; Alpha Vantage or Finnhub for US if the user
   supplies an API key).
4. Make `StockUniverseService.load_universe` return a per-market
   list.
5. Add a market selector to the static frontend so users pick the
   market before searching a symbol.
6. Update the `RecommendationStrategyProperties` so that each
   market has its own weight set (PE/PB thresholds differ sharply).
7. Cache `MarketDataCache` so that HK and US data flow through the
   same Caffeine layer added in commit `91bc46c`.

## Non-Goals

- Real-time Level 2 quotes (HK and US real-time feeds are paid).
- FX conversion (we will display the local currency; conversion is
  a separate RFC).
- Multi-currency portfolio accounting.

## Design

### Domain extensions

```java
public record Quote(
    String symbol, Market market, Currency currency,
    String name, BigDecimal currentPrice, BigDecimal change,
    BigDecimal changePercent, /* …existing fields… */
    MarketSession session, boolean realtime
) { }

public enum Market { ASHARE, HKEX, US, OTHER }
public enum MarketSession { PRE, REGULAR, POST, CLOSED, LUNCH }
public record Currency(String code, int scale) {
    public static final Currency CNY = new Currency("CNY", 100);
    public static final Currency HKD = new Currency("HKD", 100);
    public static final Currency USD = new Currency("USD", 100);
}
```

### `MarketAdapter` interface

```java
public interface MarketAdapter {
    Market market();
    boolean supports(String normalizedSymbol);
    String normalize(String symbol);             // SH600519 -> 600519, 0700.HK -> 0700.HK, AAPL -> AAPL
    Quote quote(String normalizedSymbol);
    List<MarketCandle> history(String normalizedSymbol, int limit);
    List<MarketCalendarEntry> calendar(LocalDate from, LocalDate to);
}
```

A `MarketAdapterRegistry` looks up the right adapter by prefix or
explicit hint; the lightweight in-memory adapter picks a sample
`Quote` per market for demos.

### Symbol normalization

`ExchangeResolver.normalizeSymbol("00700.HK")` -> `"00700.HK"`
`ExchangeResolver.normalizeSymbol("aapl")` -> `"AAPL"`
`ExchangeResolver.normalizeSymbol("600519.SH")` -> `"600519"`

`Market` is inferred from suffix or from a registry lookup; an
explicit `Market` argument bypasses the inference.

### Vendor clients

- **A-share**: existing `EastmoneyMarketHistoryClient`,
  `SinaMarketDataClient`, `EastmoneyMarketScreenerClient` are
  wrapped in `AshareMarketAdapter`.
- **HK**: a new `YahooFinanceMarketAdapter` (via
  `https://query1.finance.yahoo.com/v8/finance/chart/`) for both
  snapshot and history. The shim uses `WebClient` plus a 2-retry
  exponential backoff. Calendar via known HKEX holidays table
  (hand-curated in `KnownHolidays.java`).
- **US**: same `YahooFinanceMarketAdapter` with a US session
  calendar that understands pre/post-market.

### Recommendation strategy

`RecommendationStrategyProperties` gains a `markets` map keyed by
`Market`. Each entry carries the existing `weights`, `trend`,
`valuation`, and `risk` blocks. Default values come from a
`recommendations/markets.properties` resource.

### Frontend

- A `marketSelector.ts` component sits in `static/dom.js` (or the
  future RFC 005 bundle). The selector persists in
  `localStorage` under `finsight.market`.
- The static `index.html` adds a top-bar dropdown.
- The search input's `datalist` is filtered to the active market.
- Quote / history / chart renderers display the currency code in
  the price label.

## Migration plan

1. Add `Market`, `Currency`, `MarketSession` enums; extend
   `Quote`, `MarketCandle`, `Company` records with a default
   `Market.ASHARE` everywhere (existing call sites continue to
   compile).
2. Introduce `MarketAdapter` interface and the registry. Wire
   `AshareMarketAdapter` to delegate to the existing vendor
   clients.
3. Implement `YahooFinanceMarketAdapter` for HK and US.
4. Update `RecommendationStrategyProperties` and add per-market
   defaults. Migrate `DailyRecommendationService` to read per
   market.
5. Add market selector to the frontend (small PR, low risk).
6. Update README and `docs/api.md` to call out the new markets.
7. Integration test: `MarketAdapterRegistryIT` + per-vendor IT.

## Open questions

- Yahoo Finance's TOS forbids heavy scraping. Should we require
  users to supply an Alpha Vantage or Finnhub API key by default?
  Decision: default to Yahoo for HK, Alpha Vantage for US when
  the key is configured. Otherwise fall back to "no US quotes
  available" with a friendly message.
- HK lot size: 0700.HK has 100-share lots, some warrants have
  different lot sizes. We will round lot size to the nearest
  hundred for v1 and refine when a warrant issuer surfaces.

## Estimated LoC

- Domain extensions: ~150 LoC
- `MarketAdapter` + registry + 3 implementations: ~900 LoC
- HKEX + US calendars: ~250 LoC
- `RecommendationStrategyProperties` extensions: ~200 LoC
- Frontend market selector + i18n currencies: ~300 LoC
- ITs: ~600 LoC
- **Total: ~2,400 LoC**
