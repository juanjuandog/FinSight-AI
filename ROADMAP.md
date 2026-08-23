# FinSight AI Roadmap

This roadmap focuses on turning FinSight from a strong engineering prototype into a reusable AI Agent backend template.

## Done

- Spring Boot backend and static dashboard.
- Financial document ingestion and metric calculation.
- Risk signal detection.
- PostgreSQL/pgvector hybrid retrieval.
- RabbitMQ workflow dispatch.
- Redis-backed analysis cache.
- Redis Lua single-flight lease with fencing token.
- Workflow timeout recovery and retry.
- Versioned AI report persistence with `dataSnapshotHash`.
- Report diff view between `reportVersion`s.
- Report export to Markdown/PDF.
- RAG evaluation metrics and demo dashboard.
- English and Chinese README.

## Near Term

- Persist workflow transition history for audit and replay.
- Add Redis single-flight integration tests.
- Add workflow timeout recovery tests.
- Add GitHub Actions status badge after CI is green.
- Add demo GIF or short video.
- Add more financial QA evaluation cases.
- Add a public example dataset that does not depend on live market APIs.

## Mid Term

- Add multi-agent planning for research tasks.
- Add evaluation trend history.
- Add OpenTelemetry tracing for workflow and retrieval spans.
- Add Elasticsearch retrieval as an optional backend.

## Long Term

- Add multi-market support beyond A-shares.
- Add user-authenticated watchlists and portfolios.
- Add backtesting-style research signal validation.
- Add pluggable LLM providers.
- Add hosted demo deployment.

## Non-Goals

- FinSight is not investment advice.
- FinSight is not a trading bot.
- FinSight does not try to replace regulated financial research workflows.
