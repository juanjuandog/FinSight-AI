# FinSight Research API

FinSight exposes a research-facing API on top of the lower-level workflow, retrieval, and report modules. The goal is to make the platform feel like an AI equity research system instead of a collection of isolated demos.

## User session

Public market and research reads remain available without an account. Personal APIs use a 14-day `HttpOnly` session cookie and a double-submit CSRF token.

1. Call `GET /api/auth/session` to receive the current user and `csrfToken`.
2. Send the returned token as `X-CSRF-Token` on authentication and personal write requests.
3. Preserve the `finsight_csrf` and `finsight_session` cookies returned by the server.

Authentication endpoints:

- `POST /api/auth/register`: email and password registration.
- `POST /api/auth/login`: create a session.
- `POST /api/auth/logout`: revoke the current session.
- `POST /api/auth/password-reset/request`: request a generic reset response and optional SMTP mail.
- `POST /api/auth/password-reset/confirm`: consume a one-time reset token.
- `GET/POST/DELETE /api/watchlist`: private watchlist for the authenticated user.

The legacy `X-Finsight-User` header is no longer trusted.

## Daily Recommendations

```bash
curl http://localhost:8080/api/market/recommendations
```

The response includes the market snapshot source, scan time, universe size,
ranked candidates, score breakdown, and `strategyVersion`. The version identifies
the exact scoring configuration used for that recommendation run.

Scoring thresholds and weights are configured under
`finsight.recommendations.strategy` in `application.yml`. The trend, liquidity,
and valuation weights must add up to `1.0`; invalid combinations stop the
application at startup instead of silently changing the score scale.

## Historical Market Data

```bash
curl "http://localhost:8080/api/market/history/600519?limit=120"
```

The history endpoint returns an envelope rather than an untyped candle array:

```json
{
  "candles": [],
  "source": "EASTMONEY_HISTORY",
  "fetchedAt": "2026-08-22T08:00:00Z",
  "simulated": false,
  "available": false,
  "error": "历史行情暂不可用"
}
```

Provider failures return `available: false` with no candles and are not cached, so the client can retry. Deterministic candles are available only when `demo=true` is explicitly supplied; those responses use `source: LOCAL_DEMO` and `simulated: true`.

## Research Task

Submit a recoverable research task for one company:

```bash
curl -X POST http://localhost:8080/api/research/tasks \
  -H 'Content-Type: application/json' \
  -d '{"symbol":"600519"}'
```

Response:

```json
{
  "taskId": "263ba128-f12a-4588-87c4-81b3636bcbd7",
  "symbol": "600519",
  "taskType": "FINANCIAL_DATA_INGESTION",
  "status": "SUCCEEDED",
  "stage": "SUCCEEDED",
  "attempts": 1,
  "idempotencyKey": "eastmoney-public:600519:2026-05-20",
  "fencingToken": null
}
```

The submitted task enters the existing workflow state machine. The workflow can ingest public data, recalculate metrics, rebuild the document index, update company intelligence, and generate a versioned AI report.

## Task Status

```bash
curl http://localhost:8080/api/research/tasks/{taskId}
```

This endpoint exposes the task stage, retry count, idempotency key, lease owner, fencing token, timestamps, and terminal error message. It is designed for UI progress bars, debugging, and interview discussion around distributed task governance.

## Latest Report

```bash
curl http://localhost:8080/api/research/reports/600519/latest
```

The response includes rating, summary, positive points, risk points, citations, model source, `dataSnapshotHash`, `reportVersion`, and `cacheHit`.

## Report Trace

```bash
curl http://localhost:8080/api/research/reports/600519/trace
```

This endpoint returns the report identity plus the evidence chunks retrieved for the report summary. It is the recommended integration point for showing why a conclusion was produced.

Trace fields:

- `reportId`: immutable report identity.
- `reportVersion`: per-symbol report version.
- `dataSnapshotHash`: cache boundary for source data.
- `citations`: model-facing citation labels.
- `evidence`: retrieved chunks with document id, section, channel, text, and score.

## Lower-Level APIs

- `GET /api/workflows/summary`: workflow counts and stage distribution.
- `POST /api/analysis/ask`: source-grounded answer with evidence and a RAG trace.
  The trace includes `dataSnapshotHash`, a stable SHA-256 digest of the ordered
  model-facing evidence, so runs can verify that they used the same retrieved context.
  The digest covers each selected chunk's document id, title, document type, nullable
  publication date, section, and text. Chunk order is significant because it is the
  reranked order supplied to answer generation. Retrieval `score` is excluded because
  it is diagnostic metadata and does not enter the current answer-generation prompt;
  changing only score values without changing order or content therefore preserves the
  fingerprint. Null values have a distinct encoding from empty strings.
- `POST /api/evaluations/rag/run`: RAG quality evaluation.
- `GET /api/document-index/{symbol}/search?q=...`: direct evidence search.
- `GET /api/companies/{symbol}/analysis-status`: data readiness and latest workflow status.
