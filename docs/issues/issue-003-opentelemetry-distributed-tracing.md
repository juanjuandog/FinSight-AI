# Issue 003: OpenTelemetry Distributed Tracing

## Summary

Adopt OpenTelemetry tracing end-to-end. Wire `micrometer-tracing-bridge-otel`
into the Spring backend, propagate trace context to the Python AI
sidecar, deploy a local OpenTelemetry Collector + Jaeger via
docker-compose, and add a regression IT that captures the expected
span topology of a typical analyze request.

## Motivation

- P95 latency investigations today rely on logs; we have no causal
  chain showing which dependency is the bottleneck.
- Micrometer counters (commit `6447917`) answer "did it time out?"
  but not "where in the pipeline?".
- `springdoc-openapi` already shows that instrumentation hooks
  exist; the tracing side is the missing half.

## Tasks

- [ ] Add `micrometer-tracing-bridge-otel` and
  `opentelemetry-exporter-otlp` to `backend/pom.xml`.
- [ ] Configure `management.tracing.sampling.probability=0.1`,
  `management.tracing.propagation.type=w3c`, and
  `management.otlp.tracing.endpoint` (env-overridable).
- [ ] Annotate every `*Controller` method with `@Observed` and the
  `WorkflowOrchestrator` stage methods.
- [ ] Add `@NewSpan("analysis.computeContextHash")` to the hash
  helper and `@ContinueSpan` to the sidecar call.
- [ ] Add `OtelMiddleware` to `ai-service/app/observability.py`;
  register in `ai-service/app/main.py`; pass `traceparent` through
  in the Python call chain.
- [ ] Add `otel-collector` and `jaeger` services to
  `docker-compose.yml` and `scripts/otel-collector-config.yaml`.
- [ ] Add the `InMemorySpanExporter`-based regression IT in
  `src/test/java/com/finsight/it/observability/`.
- [ ] Emit `traceparent` on `4xx`/`5xx` responses (decision from
  the RFC).
- [ ] `docs/operations.md`: how to read traces in Jaeger, how to
  copy a trace ID from a 500 response.

## Acceptance criteria

- A full analyze request produces 6+ spans, including
  `GET /api/research/stock/{symbol}/analyze`,
  `analysis.cacheLookup`, `analysis.leaseWait`,
  `ai.sidecar.analyze-stock`, `document.keywordSearch`,
  `document.vectorSearch`.
- Traces from Java and the Python sidecar share the same
  `traceparent`.
- `docker compose up` exposes Jaeger on `http://localhost:16686`
  and shows traces for any analyze request issued against the
  compose stack.
- The regression IT fails if any of the expected spans go missing
  in a refactor.

## Out of scope

- Tail-based sampling.
- Replacing Micrometer metrics.
- Continuous profiling.

## References

- `docs/rfcs/RFC-003-opentelemetry-distributed-tracing.md`
- Spring Boot 3.3 tracing reference
  (https://docs.spring.io/spring-boot/docs/3.3.x/reference/html/actuator.html#actuator.micrometer-tracing)
- OpenTelemetry semantic conventions
  (https://opentelemetry.io/docs/specs/semconv/)

## Estimate

3 weeks. Split into 4 PRs:

1. Backend dependencies + auto-instrumentation (~400 LoC, 1 PR)
2. Custom spans + W3C propagation to sidecar (~300 LoC, 1 PR)
3. Collector + Jaeger compose profile (~120 LoC, 1 PR)
4. Regression IT + docs (~250 LoC, 1 PR)
