# Benchmark And Evaluation Notes

This document records the current demo-level measurements and explains what they mean. The numbers are intentionally reproducible from local scripts rather than presented as production claims.

## How To Reproduce

Start the backend:

```bash
cd backend
mvn spring-boot:run
```

Seed and evaluate the demo:

```bash
./scripts/quick-demo.sh
```

Useful endpoints:

```bash
GET  /api/workflows/summary
POST /api/evaluations/rag/run
GET  /api/document-index/600519/count
GET  /api/intelligence/600519/graph
```

## Current Demo Snapshot

| Signal | Example Result |
| --- | --- |
| Workflow tasks | `1/1 succeeded`, `0 failed/dead-letter` |
| RAG evaluation | `85 / 100`, `2/3 cases passed` |
| Evidence index | `6 documents`, `6 chunks` for `600519` |
| Intelligence graph | `20 events`, `36 entities`, `47 relations` |
| Deterministic test status | `mvn test` passes; Docker-only smoke test skips when Docker is unavailable |

## RAG Evaluation Metrics

FinSight currently scores each evaluation case with:

- `ragHitRate`: share of retrieved evidence chunks that match required evidence keywords.
- `evidenceCoverage`: coverage of required evidence keywords across retrieved evidence.
- `answerCoverage`: coverage of required answer keywords in the final answer.
- `hallucinationRisk`: heuristic penalty for unsupported or overconfident claims.
- `conclusionConsistency`: whether risk and positive conclusions are expressed coherently.
- `confidenceCalibration`: whether confidence follows grounding quality.
- `latencyMillis`: response latency captured in the RAG trace.

The goal is not to claim perfect financial reasoning. The goal is to create a regression loop for evidence-grounded AI output.

## Workflow Reliability Checks

The demo verifies that the workflow API exposes:

- total task count;
- status distribution;
- stage distribution;
- failed/dead-letter count;
- latest created time.

This gives the dashboard enough signal to show whether the research pipeline is progressing, stuck, or recoverable.

## Cache Trust Checks

AI report reuse is tied to:

- `dataSnapshotHash`;
- `contextHash`;
- `reportVersion`.

When quote data, metrics, risks, or evidence changes, the snapshot hash changes and the report is regenerated instead of silently reusing stale conclusions.

## Next Benchmarks

- Run `python scripts/benchmark_api.py --url <endpoint> --requests 100` to capture
  min/mean/p50/p95/p99/max latency as reproducible JSON.
- The Testcontainers suite fires 50 concurrent Redis lease acquisitions and asserts
  that exactly one owner wins.
- The repository tests prove stale workers and incorrect fencing tokens cannot
  overwrite a recovered task.
- The PostgreSQL integration test allocates 50 report versions concurrently and
  asserts that every version is unique.
- Track RAG evaluation trend history across commits.
