# Issue 001: Testcontainers Integration Test Suite

## Summary

Add a Testcontainers-backed integration test matrix that exercises the
Postgres + pgvector, Redis, RabbitMQ, and AI sidecar paths which the
existing 11 unit tests cannot reach. Target: 50 ITs, green
`mvn verify`, single command bring-up.

## Motivation

- `WorkflowOrchestrator` stage transitions and fencing-token races are
  unverified at the integration level.
- `RedisBackedWorkflowLeaseService` Lua scripts are exercised only by
  hand; the local-fallback semantics drift easily.
- `HybridRetrievalGateway` ordering (keyword + vector + structural
  + rerank) is asserted in unit tests with a fake `AiServiceClient` —
  the real `RestAiServiceClient` path is uncovered.
- `AuthenticationService` rate-limit edge cases depend on real clock +
  real Spring filter chain.
- The unit-test `AuthenticationServiceTest` already fails on JDK 25
  due to ByteBuddy; the IT gives us a Java-17-stable safety net.

## Tasks

- [ ] Add `maven-failsafe-plugin` to `backend/pom.xml`; configure
  `**/*IT.java` include; activate with `mvn verify`.
- [ ] Add `AbstractPostgresIT` (pgvector 16), `AbstractRedisIT`
  (redis:7-alpine), `AbstractRabbitIT` (rabbitmq:3-management), and
  `AbstractPostgresRedisRabbitIT` (composite).
- [ ] `WorkflowOrchestratorIT`: 4 tests covering stage progression,
  fencing token rejection, recovery scheduler pickup, DLQ retention.
- [ ] `RedisBackedWorkflowLeaseServiceIT`: 3 tests covering Lua
  acquire/renew/release semantics on a real Redis.
- [ ] `HybridRetrievalGatewayIT`: index 50-row fixture, assert
  rerank ordering, assert structural channel surface.
- [ ] `StockAiAnalysisServiceIT`: WireMock unreachable sidecar,
  fallback verification, peer-handoff under contention.
- [ ] `AuthenticationServiceIT`: rate-limit cooldown, password reset
  flow, session lifecycle.
- [ ] `AuditEventServiceIT`: 4 audit_log entries appear per scenario.
- [ ] `RestAiServiceClientIT` + `AiSidecarCircuitBreakerIT`:
  WireMock-driven circuit-open / half-open / closed transitions.
- [ ] `MarketDataServiceIT` and `EastmoneyMarketHistoryClientIT`:
  cache hit + fallback path against a `MockWebServer`.
- [ ] `AuthControllerIT`, `ResearchControllerIT`,
  `WatchlistControllerIT`: full request/response round trip with
  `MockMvc` + containerised services.
- [ ] CI: add `integration` job to `.github/workflows/ci.yml` that
  runs `mvn verify -Pfailsafe`; mark `required: false` on push,
  `required: true` on pull_request.
- [ ] `docs/development.md`: how to run the matrix locally, how to
  rerun a single IT, how to drop into a real Postgres to debug a
  failing IT.

## Acceptance criteria

- `mvn verify` from a clean checkout, JDK 17, with Testcontainers
  able to start Docker, takes under 8 minutes.
- The CI `integration` job is green on the latest `master`.
- The 6 pre-existing Mockito/ByteBuddy failures in
  `AuthenticationServiceTest` and `DailyRecommendationServiceTest`
  no longer block `mvn test` because we ship a `surefire` `<excludes>`
  list pointing them out as `KnownIssue`.
- Coverage report (`mvn verify -Pjacoco`) shows ≥ 60% line coverage
  on `com.finsight.workflow.*`, `com.finsight.rag.*`, and
  `com.finsight.application.StockAiAnalysisService`.

## Out of scope

- Performance benchmarks (separate `docs/benchmark.md` track).
- Multi-version Postgres matrix.
- Frontend integration tests (different repo / story).

## References

- `docs/rfcs/RFC-001-testcontainers-integration-suite.md`
- `backend/pom.xml` (existing Testcontainers deps)
- `backend/src/test/java/com/finsight/FinSightInfrastructureSmokeTest.java`
  (smoke test pattern to extend)

## Estimate

3 weeks. Split into 5 PRs:

1. Failsafe + abstract base classes (~600 LoC, 1 PR)
2. Workflow ITs (~800 LoC, 1 PR)
3. RAG + Analysis ITs (~900 LoC, 1 PR)
4. Auth + Audit + API ITs (~700 LoC, 1 PR)
5. CI + docs (~200 LoC, 1 PR)
