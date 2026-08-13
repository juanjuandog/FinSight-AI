# Issue 010: Investment Signal Backtest Engine

## Summary

Build a backtesting engine that runs the platform's existing
recommendation / AI rating / guidance signals against historical
data and produces Sharpe / Sortino / MaxDD / IC metrics plus an
equity curve. Adds a backend `BacktestEngine`, three signal
generators, the metric calculator, and a new frontend workspace.

## Motivation

- `docs/benchmark.md` is offline. There is no way to test whether
  the AI rating actually has predictive value.
- Researchers asking "should I trust this rating?" need a
  reproducible, audit-friendly answer.
- This grounds the "evidence-grounded equity research" promise in
  `README.md`.

## Tasks

- [ ] V18 migration: `backtest_runs`, `backtest_positions`, plus
  supporting indices.
- [ ] `backtest/BacktestEngine.java` — event-driven iteration
  over trading sessions.
- [ ] `backtest/SignalGenerator.java` interface + 3
  implementations:
  - `RecommendationSignalGenerator` (uses `DailyRecommendationService`)
  - `AiRatingSignalGenerator` (uses
    `StockAiAnalysisService.analyze`)
  - `GuidancePrioritySignalGenerator` (uses `GuidanceScorer`)
- [ ] `backtest/sizing/VolatilityScaledSizer.java` (Kelly + vol
  scale).
- [ ] `backtest/matching/TradeMatcher.java` (slippage + impact).
- [ ] `backtest/metrics/MetricsCalculator.java` (Sharpe, Sortino,
  MaxDD, Calmar, IC, RankIC, hitRate).
- [ ] `backtest/BacktestRunExecutor.java` — bounded async
  executor (default 2 concurrent runs).
- [ ] `backtest/api/BacktestController.java` + DTOs.
- [ ] Frontend workspace: configure, run, view equity curve /
  drawdowns / per-symbol metrics.
- [ ] Testcontainers ITs (extends RFC 001):
  - `BacktestEngineIT` — determinism, session ordering
  - `RecommendationSignalGeneratorIT` — golden fixture
  - `AiRatingSignalGeneratorIT` — stub AI sidecar returns a
    fixed rating
  - `MetricsCalculatorIT` — invariants (e.g. MaxDD ≥ -100%,
    Sharpe matches `mean/std`)
- [ ] `docs/benchmark.md`: rewrite as a backtest tour.

## Acceptance criteria

- A backtest run on the existing A-share universe over 2024 data
  with the AI rating signal completes in < 30 s on a laptop and
  produces a `BacktestRun` row with a populated
  `metrics + equity_curve + drawdowns`.
- `MetricsCalculator` passes invariant tests: MaxDD ∈ [-100%, 0],
  Sharpe matches a hand-computed value for a fixed fixture.
- The frontend workspace can configure, run, and visualise a
  backtest end to end.
- No regressions in the existing recommendation + AI analysis
  flows.

## Out of scope

- Live trading.
- Options / futures support.
- Tick-level or minute-level intervals.
- Walk-forward cross-validation (the `Interval` hook supports it
  later).

## References

- `docs/rfcs/RFC-010-investment-signal-backtest-engine.md`
- `docs/benchmark.md` (existing offline harness to extend)
- `backend/src/main/java/com/finsight/application/DailyRecommendationService.java`
- `backend/src/main/java/com/finsight/application/StockAiAnalysisService.java`

## Estimate

5 weeks. Split into 5 PRs:

1. Migration + engine skeleton + signal interface (≈ 700 LoC, 1 PR)
2. Three signal generators + cache integration (≈ 700 LoC, 1 PR)
3. Sizing + matching + metrics (≈ 800 LoC, 1 PR)
4. Backtest executor + controllers (≈ 600 LoC, 1 PR)
5. Frontend workspace + ITs + docs (≈ 1,200 LoC, 1 PR)
