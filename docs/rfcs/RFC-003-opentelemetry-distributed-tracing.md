# RFC 003: OpenTelemetry Distributed Tracing

- **Status:** Draft
- **Date:** 2026-08-13
- **Author:** FinSight contributors
- **Tracker:** Issue TBD

## Context

When a `/api/research/stock/{symbol}/analyze` request stalls at p95, the
current logs give us which method threw but not which external
dependency was the bottleneck. Micrometer counters (added in commit
`6447917`) tell us *that* the AI sidecar timed out, but the request
flow through Postgres → Redis → RabbitMQ → sidecar is unobserved.

`Spring Boot 3.3` ships first-class OpenTelemetry support via
`spring-boot-starter-actuator` + `micrometer-tracing-bridge-otel`.
Wiring this up gives every Micrometer-instrumented call a trace span
for free, and a few targeted custom spans cover the gaps.

## Goals

1. Every backend request produces a trace with spans for: HTTP entry,
   workflow stages, RAG retrieval, AI sidecar calls, and outbound
   Postgres / Redis / RabbitMQ queries.
2. Trace context (`traceparent`, `tracestate`) propagates to the
   Python AI sidecar over HTTP and back.
3. A local OpenTelemetry Collector + Jaeger compose profile lets
   developers inspect traces without external infrastructure.
4. CI captures the trace topology in a regression test: a known
   scenario must produce a known number of spans in a known order.

## Non-Goals

- Replacing Micrometer metrics (we keep both — traces for causality,
  metrics for dashboards).
- Tail-based sampling. We use head-based parent-ratio sampling at
  10% for now; revisit if storage costs spike.
- Profiling / continuous-flamegraph (separate project).

## Design

### Backend dependencies

```xml
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
<dependency>
  <groupId>io.opentelemetry</groupId>
  <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
```

### Configuration

`application.yml` additions:

```yaml
management:
  tracing:
    sampling:
      probability: 0.1
    propagation:
      type: w3c
  otlp:
    tracing:
      endpoint: ${FINSIGHT_OTLP_ENDPOINT:http://localhost:4318/v1/traces}
```

### Custom spans

`@Observed` is enough for most services. Two paths need hand-rolled
spans because they cross JVM boundaries or have rich child
relationships:

```java
// application/StockAiAnalysisService.java
@NewSpan("analysis.computeContextHash")
String contextHash(StockAiAnalysisRequest request) { ... }

@ContinueSpan(log = "AI sidecar call")
StockAiAnalysisResponse callAiOrFallback(...) { ... }
```

### Propagation to the AI sidecar

The Python sidecar reads `traceparent` from request headers and
attaches it to its own OpenTelemetry spans. A small
`OtelMiddleware` in FastAPI is the entry point:

```python
# ai-service/app/observability.py
from opentelemetry import trace
from opentelemetry.propagate import extract

tracer = trace.get_tracer("finsight.ai")

async def with_traceparent(request, call_next):
    ctx = extract(dict(request.headers))
    with tracer.start_as_current_span("ai.handle", context=ctx):
        return await call_next(request)
```

The Java side sets `traceparent` via a `WebClient` filter:

```java
webClient.post()
  .uri("/analyze-stock")
  .bodyValue(request)
  .retrieve()
  .bodyToMono(...)
```

`micrometer-tracing-bridge-otel` injects the header automatically.

### Local collector

`docker-compose.yml` adds:

```yaml
otel-collector:
  image: otel/opentelemetry-collector-contrib:0.107.0
  command: ["--config=/etc/otel-collector-config.yaml"]
  volumes:
    - ./scripts/otel-collector-config.yaml:/etc/otel-collector-config.yaml:ro
  ports:
    - "4317:4317"   # OTLP gRPC
    - "4318:4318"   # OTLP HTTP

jaeger:
  image: jaegertracing/all-in-one:1.59
  environment:
    COLLECTOR_OTLP_ENABLED: "true"
  ports:
    - "16686:16686" # UI
```

### Regression test

A new IT in the Testcontainers matrix (RFC 001) captures the topology
of a `/api/research/stock/{symbol}/analyze` request:

```java
@Test
void analyze_emitsExpectedSpans() {
    // Tracer configured with InMemorySpanExporter.
    var exporter = InMemorySpanExporter.create();
    var provider = SdkTracerProvider.builder()
        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
        .build();

    AnalysisResponse response = analysisApp.analyze("600519");

    var spanNames = exporter.getFinishedSpanItems().stream()
        .map(SpanData::getName)
        .toList();
    assertThat(spanNames)
        .contains("GET /api/research/stock/600519/analyze",
                  "analysis.cacheLookup",
                  "analysis.leaseWait",
                  "ai.sidecar.analyze-stock",
                  "document.keywordSearch",
                  "document.vectorSearch");
}
```

## Migration plan

1. Add the dependencies, configure the OTLP exporter, deploy the
   collector in dev compose. No source code change yet.
2. Wire `@Observed` on the controllers and `WorkflowOrchestrator`.
3. Hand-rolled spans for `StockAiAnalysisService.contextHash` and
   `RestAiServiceClient.invoke`.
4. AI sidecar `OtelMiddleware` + `with_traceparent` registration.
5. Add the regression IT.
6. Update `docs/operations.md` with how to read traces locally and
   in production.

## Open questions

- Tail-based sampling: 10% head sampling is the default. Add a
  decision on storage cost vs. fidelity.
- Do we expose trace IDs in the JSON response so a user can quote
  them in support tickets? Decision: yes, on `4xx`/`5xx` only, via
  the `traceparent` response header.

## Estimated LoC

- Backend deps + config: ~150 LoC
- `@Observed` annotations + custom spans: ~400 LoC
- AI sidecar middleware: ~150 LoC
- Docker compose + collector config: ~80 LoC
- Regression IT: ~250 LoC
- **Total: ~1,000 LoC** (not including the auto-instrumented call
  sites that change only by an annotation).
