# RFC 008: LLM Provider & Data Source Plugin System

- **Status:** Draft
- **Date:** 2026-08-13
- **Author:** FinSight contributors
- **Tracker:** Issue TBD

## Context

`ai-service/app/llm_provider.py` already supports Ollama,
OpenAI-compatible, and Anthropic via three branches in a single
file. `AiServiceClient` similarly has only `RestAiServiceClient` and
`FallbackAiServiceClient`. Every new provider touches this code, and
the user must configure credentials by environment variables.

`ROADMAP.md` lists "Add pluggable LLM providers" as Long Term.
Once HK and US markets arrive (RFC 006) and once teams (RFC 007)
exist, we expect more provider requests — proprietary cloud LLMs,
custom screener vendors, internal data lakes. A plugin system lets
third parties extend FinSight without forking the core.

This RFC introduces a Java SPI-style plugin layer on the backend
plus a Python entry-point registry on the AI sidecar, with an
optional JAR / WHEEL distribution format for richer plugins.

## Goals

1. Define three plugin extension points: `MarketDataSource`,
   `DocumentSource`, `AiProvider`.
2. Implementations are discovered at runtime via Java `ServiceLoader`
   (backend) and `importlib.metadata.entry_points` (sidecar), with
   an optional classpath-jar / pyz / wheel format for richer
   plugins.
3. Each plugin declares its capabilities via a descriptor
   (`@FinSightPlugin(...)`) and is sandboxed by a per-plugin
   timeout and a per-plugin config map.
4. Operators install a plugin with a single shell command
   (`./scripts/install-plugin.sh <name>`); enablement toggles a
   flag in `finsight.plugins` config and exposes the new source on
   the next request.
5. A plugin health probe at `/api/admin/plugins/health` lists all
   loaded plugins with their status.
6. Documentation includes a plugin-authoring guide, a sample
   "finsight-news-cn" third-party plugin, and a security model
   (signature verification, optional).

## Non-Goals

- A plugin marketplace UI (separate product surface).
- Plugin-to-plugin communication primitives.
- Sandboxing beyond timeouts and reflection-restricted method
  invocation.

## Design

### Backend: Java SPI

```java
public interface MarketDataSource {
    String id();
    String displayName();
    boolean supports(Market market);
    Quote quote(Market market, String symbol);
    List<MarketCandle> history(Market market, String symbol, int limit);
    List<DocumentChunk> search(Market market, String query, int limit);
    default Duration timeout() { return Duration.ofSeconds(10); }
}

public interface DocumentSource {
    String id();
    String displayName();
    boolean supports(String documentType);
    List<FinancialDocument> fetch(String query, int limit);
}

public interface AiProvider {
    String id();
    String displayName();
    boolean supports(String capability);   // e.g. "stock_analysis", "rerank"
    AiProviderResponse generate(AiProviderRequest request);
}
```

A plugin JAR puts a `META-INF/services/com.finsight.plugin.MarketDataSource`
file listing the FQCNs of the implementations. `ServiceLoader.load`
collects them at startup; the registry exposes `byCapability(...)`
to the rest of the app.

### Plugin descriptor annotation

```java
@FinSightPlugin(
    id = "akshare-spot",
    vendor = "juanjuandog",
    version = "0.1.0",
    capabilities = { "market-data.ashare.spot" },
    configClass = AkshareConfig.class
)
public class AkshareSpotSource implements MarketDataSource { ... }
```

The `@FinSightPlugin` annotation is read by a `PluginScanner` at
startup that:

- Validates that `id` is unique across all plugins (throws at
  startup if two plugins share an `id`).
- Loads `configClass` and binds it to a `@ConfigurationProperties`
  bean under `finsight.plugins.<id>`.
- Registers a Micrometer `MeterRegistry` timer with tags
  `plugin=<id>` so per-plugin behaviour is observable.

### Sandbox

Each plugin call runs in a `CompletableFuture.supplyAsync(...)`
constrained by `timeout()` (default 10s) and wrapped in a
`try/catch` that converts `RuntimeException` to
`PluginTimeoutException` / `PluginInvocationException`. The
returned value is the plugin's contract output; the rest of the
app never sees the plugin's raw exception.

### Sidecar: Python entry points

```python
from finsight_plugin import AiProvider, ProviderRequest, ProviderResponse

@entry_point(group="finsight.ai.provider", name="ollama")
class OllamaProvider(AiProvider):
    def generate(self, request: ProviderRequest) -> ProviderResponse:
        ...
```

`ai-service/app/main.py` calls
`importlib.metadata.entry_points(group="finsight.ai.provider")` at
startup and registers each plugin alongside the built-in Ollama /
OpenAI / Anthropic adapters. The `/analyze-stock` endpoint picks
the first plugin whose `supports("stock_analysis")` is true.

### Installation UX

A `./scripts/install-plugin.sh` script downloads a JAR/WHEEL from
a configured plugin registry, verifies an
`ed25519:...` signature against a pinned key, drops it into
`plugins/`, and updates `plugins/installed.list`. Restart
required.

### Admin endpoints

- `GET /api/admin/plugins` — list installed plugins.
- `POST /api/admin/plugins/{id}/enable` — toggle the runtime flag.
- `GET /api/admin/plugins/health` — last-call status per plugin.

These endpoints live behind a `Permission.PLUGIN_ADMIN` (defined
alongside the RFC 007 role matrix) and are not exposed in the
lightweight preview profile.

## Migration plan

1. Land the plugin descriptor + scanner + sandbox. No concrete
   plugins yet, so the SPI registry is initially empty.
2. Wrap the existing `EastmoneyMarketHistoryClient` and
   `SinaMarketDataClient` as the first `MarketDataSource` plugins
   (`ashare-eastmoney-quote`, `ashare-sina-quote`).
3. Wrap the existing Ollama / OpenAI / Anthropic clients on the
   Python side as `AiProvider` entry points.
4. Land the admin endpoints behind a flag.
5. Land the install script and signature verification.
6. Author a third-party sample plugin
   (`docs/samples/finchat-news-pkg/`).
7. Migration guides in `docs/plugin-authoring.md`,
   `docs/plugin-distribution.md`.

## Open questions

- Signature verification in the lightweight preview profile:
  optional (controlled by `finsight.plugins.require-signature`).
- Should plugin JARs run in their own classloader? Decision: yes,
  via `URLClassLoader` with a whitelist of packages they can
  import from `com.finsight.*`.
- Sidecar plugin restart: restart the sidecar container via
  `./scripts/install-plugin.sh` for v1. Hot-reload is out of
  scope.

## Estimated LoC

- Plugin descriptor + scanner + sandbox: ~500 LoC
- Built-in `MarketDataSource` wrappers: ~300 LoC
- Built-in `AiProvider` entry points (sidecar): ~300 LoC
- Admin endpoints: ~250 LoC
- Install script + signature verification: ~300 LoC
- Plugin sandbox + classloader isolation: ~400 LoC
- Sample third-party plugin + docs: ~600 LoC
- **Total: ~2,650 LoC**
