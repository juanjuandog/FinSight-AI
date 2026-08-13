# RFC 001: Testcontainers Integration Test Suite

- **Status:** Draft
- **Date:** 2026-08-13
- **Author:** FinSight contributors
- **Tracker:** Issue TBD

## Context

FinSight-AI's backend has 11 unit tests today, all of which run in milliseconds with
in-memory adapters. Critical paths — workflow recovery, Redis lease fencing, RAG
hybrid retrieval, AI sidecar timeout/fallback — are exercised only by hand on
`docker compose up`. The project already carries the Testcontainers dependencies
(`junit-jupiter`, `postgresql`, `rabbitmq`) but no `*IT.java` files exist.

The pre-existing Mockito/ByteBuddy incompatibility on JDK 25 already breaks
5 `AuthenticationServiceTest` cases. Adding real infrastructure tests reduces
reliance on Mockito for the contracts that matter most (repository behaviour,
RabbitMQ retry/DLQ semantics, fencing token races).

## Goals

1. Reach a green `mvn verify` matrix on Java 17 (CI's actual target) with at
   least 50 Testcontainers-backed ITs.
2. Cover the paths that unit tests cannot reach: Postgres + pgvector HNSW,
   Redis Lua lease expiry, RabbitMQ retry-queue + DLQ, AI sidecar circuit
   breaker with a mock HTTP target.
3. Make the IT matrix self-contained: a single `mvn verify` brings up
   containers, runs assertions, tears down.
4. Keep the lightweight preview mode (`mvn spring-boot:run` with H2) usable
   for non-test demos.

## Non-Goals

- Replacing the existing 11 unit tests.
- Performance benchmarks (separate scope, see `docs/benchmark.md`).
- Cross-version Postgres matrix (single Postgres 16 with pgvector).

## Design

### Module layout

Add the failsafe plugin and a `verify` profile that activates the IT
classpath. New `src/test/java/com/finsight/it/` package groups ITs by
component. A shared `AbstractPostgresRabbitRedisIT` boots all three
containers once per JVM using the Ryuk reaper; subclasses opt in to
specific ones.

```
src/test/java/com/finsight/it/
  AbstractPostgresIT.java          (postgres + flyway)
  AbstractRedisIT.java             (redis 7-alpine)
  AbstractRabbitIT.java            (rabbitmq 3-management)
  AbstractPostgresRedisRabbitIT.java
  workflow/
    WorkflowOrchestratorIT.java
    RedisBackedWorkflowLeaseServiceIT.java
    WorkflowRecoverySchedulerIT.java
  rag/
    HybridRetrievalGatewayIT.java
    JdbcDocumentChunkRepositoryIT.java
  market/
    MarketDataServiceIT.java
    EastmoneyMarketHistoryClientIT.java
  application/
    StockAiAnalysisServiceIT.java
    StockAiAnalysisCacheIT.java
    AuthenticationServiceIT.java
    AuditEventServiceIT.java
  api/
    AuthControllerIT.java
    ResearchControllerIT.java
    WatchlistControllerIT.java
  ai/
    RestAiServiceClientIT.java
    AiSidecarCircuitBreakerIT.java
```

### Plugin configuration

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-failsafe-plugin</artifactId>
  <executions>
    <execution>
      <goals>
        <goal>integration-test</goal>
        <goal>verify</goal>
      </goals>
    </execution>
  </executions>
  <configuration>
    <includes>
      <include>**/*IT.java</include>
    </includes>
    <systemPropertyVariables>
      <finsight.it.postgres.url>${finsight.test.postgres.url}</finsight.it.postgres.url>
    </systemPropertyVariables>
  </configuration>
</plugin>
```

### Base classes

`AbstractPostgresIT` spins up `PostgreSQLContainer<>("pgvector/pgvector:pg16")`,
runs Flyway migrations, exposes a `JdbcTemplate` configured for the test
schema, and schedules `TRUNCATE ... CASCADE` between tests via a
`@BeforeEach` callback. The schema is reset, not recreated, to keep wall
clock under 8 minutes for the full matrix.

`AbstractRedisIT` uses `GenericContainer<>("redis:7-alpine")` with
`REDIS_URL` and a `StringRedisTemplate` that points at the test container.
Each test uses `@Testcontainers` with a static `RedisContainer` so JUnit
5 caches the container for the whole class.

`AbstractRabbitIT` uses `RabbitMQContainer("rabbitmq:3-management")`,
declares the same exchanges/queues/DLQ that the production profile
declares, and provides a `RabbitTemplate` for assertions.

### Concrete ITs

`WorkflowOrchestratorIT` covers the four cases a unit test cannot reach:

- `recoverTimedOutTasks_picksUpRunningTaskAfterDeadline`
- `stageProgression_publishesNextTaskOnSuccess`
- `fencingToken_preventsStaleOwnerFromAdvancing`
- `dlq_persistsPoisonMessageAfterMaxRetries`

`StockAiAnalysisServiceIT` simulates an unreachable AI sidecar via a
`WireMockServer`, asserts fallback, and exercises the
`ConcurrentAnalysisWaiter` peer-handoff.

`HybridRetrievalGatewayIT` indexes a 50-row fixture into pgvector with
HNSW, runs `search("现金流", 5)`, and asserts that the rerank
ordering is honoured (asserts at least one of the AI-reranked chunks
appears in the top 3).

### CI integration

`.github/workflows/ci.yml` gains a new job `integration` that runs
`mvn verify -Pfailsafe`. The job uses `ubuntu-latest` with 4 GB RAM
(Testcontainers + 3 containers + JaCoCo). The job does not gate
push-to-master (it is `required: false`) but does block merges when
the workflow is invoked via `pull_request`.

## Migration plan

1. Land the `maven-failsafe-plugin` configuration and the four abstract
   base classes in a single PR. CI will continue to pass.
2. Land 5 ITs at a time, one PR each, until the matrix is full.
3. Add a `docs/development.md` section explaining how to run the matrix
   locally and what to do when a test flakes.

## Open questions

- Should the matrix also run in `lightweight` (H2) profile? The
  current unit tests already cover the H2 path. Decision: no.
- Container reuse across JVM runs via
  `testcontainers.reuse.enable=true`? Decision: yes for CI, no for
  local dev (Ryuk handles teardown).
- Should the AI sidecar mock live in `ai-service/` or in the
  `backend/`? Decision: in `backend/` as a Testcontainers container
  running the real FastAPI app with `LLM_PROVIDER=fallback`.
