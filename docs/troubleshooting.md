# Troubleshooting

This page covers the common local demo problems.

## The Dashboard Opens But Has No Data

Run the demo seeding script:

```bash
./scripts/quick-demo.sh
```

Then refresh `http://localhost:8080`.

## `curl: Failed to connect to localhost:8080`

Start the backend first:

```bash
cd backend
mvn spring-boot:run
```

Or start the full stack:

```bash
./scripts/run-full-stack.sh
```

## Docker Is Not Running

The default backend mode does not require Docker. Use:

```bash
cd backend
mvn spring-boot:run
```

The full stack requires Docker for PostgreSQL/pgvector, RabbitMQ, Redis, the AI sidecar, Elasticsearch, and MinIO.

## Configure a Generation Model

The default is `LLM_PROVIDER=ollama`, which needs no cloud API key. If it is unavailable, or if a selected cloud provider has no key configured, FinSight falls back to deterministic rule-based analysis with `aiGenerated=false`.

Copy `.env.example` to a local ignored `.env` file and choose one provider. Do not commit that file.

| Provider | `LLM_PROVIDER` | Required configuration | Typical use |
| --- | --- | --- | --- |
| Ollama | `ollama` | `OLLAMA_BASE_URL`, `OLLAMA_MODEL` | Local models, default |
| OpenAI-compatible | `openai-compatible` | `OPENAI_COMPATIBLE_BASE_URL`, `OPENAI_COMPATIBLE_MODEL`, `OPENAI_COMPATIBLE_API_KEY` | OpenAI, DeepSeek, Qwen-compatible APIs, OpenRouter |
| Anthropic | `anthropic` | `ANTHROPIC_BASE_URL`, `ANTHROPIC_MODEL`, `ANTHROPIC_API_KEY` | Claude Messages API |

`LLM_MODEL` is an optional provider-independent override. The `/health` endpoint returns the selected provider and model but never returns credentials.

## Configure Password Reset Email

Accounts, login, logout, and personal watchlists work without SMTP. To deliver password-reset links, configure these variables in `.env`:

```text
FINSIGHT_AUTH_EMAIL_ENABLED=true
FINSIGHT_AUTH_MAIL_FROM=noreply@example.com
FINSIGHT_PUBLIC_BASE_URL=https://your-finsight.example.com
SPRING_MAIL_HOST=smtp.example.com
SPRING_MAIL_PORT=587
SPRING_MAIL_USERNAME=your-smtp-user
SPRING_MAIL_PASSWORD=your-smtp-password
FINSIGHT_AUTH_SECURE_COOKIE=true
```

Keep email disabled until SMTP is valid. Production deployments served over HTTPS must use secure cookies; the local `http://localhost:8080` demo uses `FINSIGHT_AUTH_SECURE_COOKIE=false`.

To enable the default local model:

```bash
ollama serve
ollama pull qwen2.5:7b
```

## Port Conflicts

Default ports:

| Service | Port |
| --- | --- |
| Backend dashboard/API | `8080` |
| FastAPI AI sidecar | `8001` |
| PostgreSQL | `5432` |
| Redis | `6379` |
| RabbitMQ | `5672`, `15672` |

Stop the conflicting process or edit `docker-compose.yml` / Spring configuration.

For a temporary local override, pass a different Spring port when starting the
backend:

```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8081"
```

## Testcontainers Smoke Test Is Skipped

`mvn test` includes a Testcontainers smoke test for PostgreSQL/pgvector and RabbitMQ. If Docker is not available, that smoke test is skipped while unit tests still run.

Expected local output without Docker:

```text
Tests run: 4, Failures: 0, Errors: 0, Skipped: 1
BUILD SUCCESS
```

## Redis Is Unavailable

In local mode, the workflow lease service falls back to process-local single-flight locking. In the production-like profile, Redis enables cross-instance Lua leases and report caching.
