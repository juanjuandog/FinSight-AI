# RFC 010: Investment Signal Backtest Engine

- **Status:** Draft
- **Date:** 2026-08-13
- **Author:** FinSight contributors
- **Tracker:** Issue TBD

## Context

The platform computes structured `StockAiAnalysisResponse`
ratings and `GuidanceScorer.researchPriority` decisions every time
a user opens a company. There is no way to test whether these
signals actually have predictive value over the historical data
already in PostgreSQL.

`ROADMAP.md` (Mid Term) lists "Add evaluation trend history"
alongside "Add report diff view". `docs/benchmark.md` describes
the RAG evaluation harness but it is offline / static. This RFC
turns the existing recommendation + analysis pipeline into a
backtestable signal generator.

## Goals

1. BacktestEngine that consumes historical `Quote`,
   `FinancialMetric`, and `RiskSignal` rows and emits a portfolio
   curve.
2. First-class signals: the existing recommendation strategy
   (commit `c36b1ad`), the AI sidecar's structured
   `StockAiAnalysisResponse.rating`, and a new event-driven signal
   based on `GuidanceScorer.researchPriority == "优先研究"`.
3. Position sizing by `confidence` (0-100) scaled to a target
   volatility; default `Kelly-fraction = confidence / 200`.
4. Slippage model: `0.1%` + `impact = volume_factor *
   |target_fraction|`.
5. Result persistence: a `backtest_runs` table + a
   `backtest_positions` table for time series.
6. Evaluation metrics: Sharpe, Sortino, MaxDD, Calmar, IC,
   RankIC, hit rate, average excess return.
7. Frontend workspace: configure + run a backtest, view
   cumulative-return curve, drawdown waterfall, and per-signal
   metric breakdown.

## Non-Goals

- Live trading (FinSight is research-only).
- Options / futures (the data model has stocks and metrics
  only).
- Tick-level backtesting (we use daily candles; an `Interval`
  abstraction is the cleanup hook for later).

## Design

### Domain model (V18 migration)

```sql
CREATE TABLE backtest_runs (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    signal VARCHAR(64) NOT NULL,        -- 'recommendation' | 'ai_rating' | 'guidance_priority'
    universe JSONB NOT NULL,            -- { markets: [...], symbols: [...] }
    params JSONB NOT NULL,              -- size, slippage, Kelly cap, etc.
    status VARCHAR(16) NOT NULL,        -- 'pending' | 'running' | 'succeeded' | 'failed'
    metrics JSONB,
    equity_curve JSONB,                 -- [{date, equity}, ...]
    drawdowns JSONB,                    -- [{peak, trough, recovery}, ...]
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ
);

CREATE TABLE backtest_positions (
    run_id VARCHAR(64) NOT NULL REFERENCES backtest_runs(id),
    symbol VARCHAR(32) NOT NULL,
    side VARCHAR(8) NOT NULL,           -- 'long' | 'short' | 'flat'
    entry_date DATE NOT NULL,
    exit_date DATE,
    entry_price NUMERIC(20,4) NOT NULL,
    exit_price NUMERIC(20,4),
    pnl NUMERIC(20,4),
    size_fraction NUMERIC(6,4)
);
CREATE INDEX idx_backtest_positions_run ON backtest_positions(run_id, entry_date);
```

### BacktestEngine

```java
@Component
public class BacktestEngine {
    private final SignalGenerator signals;
    private final UniverseResolver universe;
    private final PriceLoader prices;

    public BacktestRunResult run(BacktestRequest request) {
        LocalDate[] sessions = request.sessions();
        PortfolioState state = PortfolioState.starting(request.params().capital());
        for (LocalDate session : sessions) {
            Map<String, Signal> today = signals.generate(session, universe.symbols());
            Map<String, BigDecimal> todayPrices = prices.at(session);
            Map<String, BigDecimal> targets = sizing.scale(today, state.equity, request.params());
            List<Trade> trades = matching.diff(state.positions, targets, todayPrices);
            state = matching.apply(state, trades, todayPrices, request.params().slippage());
            state.record(session);
        }
        return BacktestRunResult.from(state, request);
    }
}
```

### Signal generators

```java
public interface SignalGenerator {
    String id();
    Map<String, Signal> generate(LocalDate session, List<String> symbols);
}
```

Three implementations ship with v1:

- `RecommendationSignalGenerator` — calls the existing
  `DailyRecommendationService` with the same params; uses the
  composite score as `strength`.
- `AiRatingSignalGenerator` — calls
  `StockAiAnalysisService.analyze(symbol)` (cached via
  `StockAnalysisCache`) and uses `rating` + `confidence`.
- `GuidancePrioritySignalGenerator` — uses
  `GuidanceScorer.score(...)` and emits a long signal when
  `researchPriority == "优先研究"` and `confidence > 70`.

The signature `Signal { String symbol; String side; double strength; }` is
uniform across generators.

### Sizing

```java
public class VolatilityScaledSizer {
    public Map<String, BigDecimal> scale(
            Map<String, Signal> signals,
            BigDecimal equity,
            BacktestParams params
    ) {
        // Kelly fraction = strength / 200, capped at params.kellyCap()
        // Apply per-symbol vol scaling using 20-day realised vol.
    }
}
```

### Metrics

`MetricsCalculator` computes:

- `totalReturn`, `annualisedReturn`, `annualisedVolatility`
- `sharpe`, `sortino` (with configurable risk-free rate)
- `maxDrawdown`, `maxDrawdownDuration`
- `calmar = annualisedReturn / maxDrawdown`
- `ic = rank-correlation(signal.strength, next-week return)`
- `rankIC = same on rank-transformed data`
- `hitRate = % positives correctly identified`

### Frontend

A new `backtest` workspace in the static frontend:

- Configure: signal name, universe (existing watchlist or pick),
  start/end dates, params
- Run: `POST /api/backtest/runs` → poll status
- View: equity curve (line chart), drawdowns (waterfall), per-symbol
  metrics (table)

## Migration plan

1. V18 migration + domain entities + `BacktestEngine` skeleton.
2. Three signal generators, one per existing recommendation /
   analysis / guidance source.
3. Sizing, matching, metrics calculator.
4. Controllers: `POST /api/backtest/runs`, `GET
   /api/backtest/runs/{id}`, `GET /api/backtest/runs?workspaceId=`.
5. Frontend workspace: configure, run, view.
6. ITs (extending RFC 001): signal determinism, cost accounting,
   metric invariants.
7. `docs/benchmark.md` becomes a backtest tour.

## Open questions

- Should backtests run in a Spring `@Async` thread or in a
  background worker? Decision: async with a `BacktestRunExecutor`
  bounded by `BacktestConfig.maxConcurrentRuns = 2`.
- Do we offer cross-validation across windows (walk-forward)?
  Decision: out of scope; the `Interval` hook makes it possible
  later.
- Tax modelling: out of scope. PnL is pre-tax.

## Estimated LoC

- V18 migration + entities + value objects: ~400 LoC
- `BacktestEngine` + iteration: ~500 LoC
- 3 signal generators: ~600 LoC
- Sizing + matching + metrics: ~800 LoC
- Controllers + DTOs: ~400 LoC
- Frontend workspace: ~600 LoC
- ITs + golden fixtures: ~700 LoC
- **Total: ~4,000 LoC**
