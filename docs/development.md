# Development and integration tests

## Prerequisites

- JDK 17
- Maven 3.9 or newer
- Docker Desktop or another Docker-compatible engine

Testcontainers starts isolated PostgreSQL 16 + pgvector, Redis 7, and
RabbitMQ containers automatically. Do not start `docker compose` before the
test matrix unless you also want the long-running development stack.

## Test commands

Run the fast unit suite:

```bash
cd backend
mvn test
```

Run unit tests followed by every `*IT.java` integration test:

```bash
cd backend
mvn verify -Pfailsafe
```

Run one integration-test class while iterating:

```bash
cd backend
mvn verify -Pfailsafe -Dit.test=WorkflowOrchestratorIT
```

Generate coverage and enforce the RFC-001 60% line-coverage gates for the
workflow package, RAG package, and `StockAiAnalysisService`:

```bash
cd backend
mvn verify -Pjacoco
open target/site/jacoco/index.html
```

The complete matrix should finish comfortably inside the eight-minute CI
budget. The first run can be slower while Docker downloads images.

## Inspect PostgreSQL while debugging

Testcontainers intentionally removes its database when Maven exits. For an
interactive database that remains available, start the development PostgreSQL
service and open `psql` inside it:

```bash
docker compose up -d postgres
docker compose exec postgres psql -U finsight -d finsight
```

Useful commands inside `psql`:

```text
\dt
SELECT * FROM flyway_schema_history ORDER BY installed_rank;
SELECT id, status, stage, fencing_token FROM workflow_tasks ORDER BY updated_at DESC;
SELECT id, company_symbol, chunk_index FROM document_chunks LIMIT 20;
```

Stop the development database with `docker compose stop postgres`. Use
`docker compose down -v` only when you intentionally want to delete its local
volume and all stored development data.

## Troubleshooting

- Confirm Docker is reachable with `docker info`.
- Rerun a failure using `-Dit.test=ClassName` and inspect
  `backend/target/failsafe-reports/`.
- Docker Engine 29 requires API version 1.44 or newer. The test resources pin
  this compatible client API in `docker-java.properties` for the currently
  managed Testcontainers version.
- Container ports are random. Always use the dynamic properties supplied by
  the abstract integration-test base classes instead of hard-coded ports.
