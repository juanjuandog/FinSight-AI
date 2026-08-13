# Issue 006: Multi-Market Data Abstraction Layer

## Summary

Introduce a `MarketAdapter` interface plus A-share / HK / US
implementations so the platform can serve HK 港股 and US 美股
research without rewriting downstream consumers. Adds a market
selector to the static frontend and per-market recommendation
weights.

## Motivation

- Current `MarketDataService` is hard-wired to Eastmoney / Sina;
  HK and US are impossible without forking the data pipeline.
- The `RecommendationStrategyProperties` carry A-share-specific
  PE/PB thresholds (45x / 80x) that are nonsense for US tech
  stocks.
- The README promises HK and US roadmaps in `ROADMAP.md`
  (Long Term: "Add multi-market support beyond A-shares") but no
  design is in place.

## Tasks

- [ ] Add `domain/model/Market.java`, `Currency.java`,
  `MarketSession.java`; extend `Quote`, `MarketCandle`,
  `Company` records with sensible defaults (`Market.ASHARE` for
  existing call sites).
- [ ] Add `market/MarketAdapter.java` interface and
  `MarketAdapterRegistry.java` lookup.
- [ ] `market/AshareMarketAdapter.java` — delegate to the
  existing Eastmoney/Sina clients without behaviour change.
- [ ] `market/YahooFinanceMarketAdapter.java` — `WebClient`
  with 2-retry backoff; covers HK + US.
- [ ] `market/KnownHolidays.java` (HKEX + NYSE + NASDAQ hand
  table).
- [ ] Update `ExchangeResolver.normalizeSymbol` so that
  `00700.HK` / `AAPL` round-trip and the `Market` is inferred.
- [ ] Extend `RecommendationStrategyProperties` with a `markets`
  map (one entry per `Market`); move defaults to a new
  `recommendations/markets.properties`.
- [ ] Migrate `DailyRecommendationService` to read per-market
  weights.
- [ ] Add `MarketSelector` to the static frontend; persist
  choice in `localStorage` under `finsight.market`; filter the
  search datalist.
- [ ] Update `MarketDataCache` (commit `91bc46c`) so HK and US
  data flow through Caffeine with 1m quote / 5m history TTL.
- [ ] `MarketAdapterRegistryIT` + per-vendor IT (Yahoo happy
  path; offline path picks the inline fixture).
- [ ] `docs/api.md` and `README.md`: call out the new markets
  and required API keys.

## Acceptance criteria

- A user searching `00700.HK` retrieves a quote with
  `Currency.HKD` and `Market.HKEX`; searching `AAPL` returns
  `Currency.USD` and `Market.US`.
- The recommendation endpoint accepts a `?market=` parameter
  and applies the per-market weight set.
- The frontend market selector persists across reloads.
- All existing A-share flows continue to behave identically when
  `?market=ASHARE` or no parameter is supplied.

## Out of scope

- Real-time Level 2 feeds.
- FX conversion.
- Multi-currency portfolio accounting.

## References

- `docs/rfcs/RFC-006-multi-market-data-layer.md`
- `ROADMAP.md` (Long Term: multi-market)
- `backend/src/main/java/com/finsight/market/` (current vendor
  clients to be wrapped, not replaced)

## Estimate

4 weeks. Split into 5 PRs:

1. Domain extensions + `MarketAdapter` interface (≈ 500 LoC, 1 PR)
2. `AshareMarketAdapter` + adapter registry (≈ 600 LoC, 1 PR)
3. `YahooFinanceMarketAdapter` + calendars (≈ 700 LoC, 1 PR)
4. Recommendation strategy + frontend selector (≈ 500 LoC, 1 PR)
5. ITs + docs (≈ 300 LoC, 1 PR)
