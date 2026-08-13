# Issue 008: LLM Provider & Data Source Plugin System

## Summary

Introduce a plugin layer for three extension points
(`MarketDataSource`, `DocumentSource`, `AiProvider`) discoverable
via Java `ServiceLoader` on the backend and
`importlib.metadata.entry_points` on the AI sidecar. Ship
wrappers for the existing vendors as the first plugins, plus an
admin surface and a signed install UX.

## Motivation

- Today every new provider requires a fork. Plugins make third
  parties viable.
- `ROADMAP.md` lists "Add pluggable LLM providers" as Long Term;
  this RFC is the design.
- HK + US markets (RFC 006) bring vendor proliferation that this
  layer absorbs cleanly.

## Tasks

- [ ] `infrastructure/plugin/FinSightPlugin.java` annotation.
- [ ] `infrastructure/plugin/PluginScanner.java` reading
  `META-INF/services/`.
- [ ] `infrastructure/plugin/PluginRegistry.java` keyed by
  capability.
- [ ] `infrastructure/plugin/PluginSandbox.java` — per-plugin
  timeout, exception normalisation, Micrometer timer.
- [ ] `infrastructure/plugin/SandboxClassLoader.java` — URL
  classloader restricting access to `com.finsight.*`.
- [ ] Move existing Eastmoney + Sina clients into
  `ashare-eastmoney-quote` and `ashare-sina-quote` plugins.
- [ ] On the sidecar: wrap Ollama / OpenAI-compatible /
  Anthropic as `AiProvider` entry points under group
  `finsight.ai.provider`.
- [ ] `api/admin/PluginAdminController.java` for
  `/api/admin/plugins` + `/enable` + `/health`, guarded by
  `Permission.PLUGIN_ADMIN`.
- [ ] `scripts/install-plugin.sh` — download, signature verify,
  drop into `plugins/`, restart recipe.
- [ ] `scripts/sign-plugin.sh` — produce `ed25519` signature.
- [ ] Sample plugin skeleton in
  `docs/samples/finchat-news-pkg/`.
- [ ] `docs/plugin-authoring.md`, `docs/plugin-distribution.md`,
  `docs/plugin-security.md`.

## Acceptance criteria

- `./scripts/install-plugin.sh docs/samples/finchat-news-pkg`
  installs a stub plugin; `/api/admin/plugins` lists it; toggling
  `/enable` swaps the live behaviour.
- A plugin exceeding its declared timeout throws
  `PluginTimeoutException`; the calling service falls back to
  the previous source.
- The Ollama / OpenAI / Anthropic adapters migrated to entry
  points still answer `/analyze-stock` with no behaviour change.
- An unsigned plugin is rejected with `PluginSignatureException`
  when `finsight.plugins.require-signature=true`.

## Out of scope

- Hot-reload across the network.
- Plugin marketplace UI.
- Plugin-to-plugin RPC primitives.

## References

- `docs/rfcs/RFC-008-llm-and-data-source-plugins.md`
- `ROADMAP.md` (Long Term: pluggable LLM providers)
- `ai-service/app/llm_provider.py` (current provider code to be
  migrated to entry points)
- `RFC 007 RBAC` (for `Permission.PLUGIN_ADMIN`)

## Estimate

5 weeks. Split into 5 PRs:

1. Plugin descriptor + scanner + sandbox (≈ 600 LoC, 1 PR)
2. Built-in `MarketDataSource` wrappers (≈ 400 LoC, 1 PR)
3. Sidecar entry-point migration (≈ 400 LoC, 1 PR)
4. Admin endpoints + role/permission wiring (≈ 400 LoC, 1 PR)
5. Install + sign scripts + sample plugin + docs (≈ 800 LoC, 1 PR)
