# Issue 002: OpenAPI → TypeScript Typed API Client

## Summary

Generate a typed TypeScript client from the existing
`/v3/api-docs` Springdoc schema and migrate the 50+ `fetch()` call
sites in `static/app.js` to the typed wrapper. Catch API drift in
CI via `api-lint` and `api-diff` jobs.

## Motivation

- `app.js` currently has no machine-readable contract with the
  Spring backend; renames slip through.
- The 1779-line IIFE mixes API plumbing with workspace state and
  DOM logic. A typed client is the first step to teasing them apart
  (next step is RFC 003 — module split).
- `springdoc-openapi` is already wired in (commit `72b20b1`); we
  just need a consumer.

## Tasks

- [ ] Add `frontend/` workspace with `package.json`, `tsconfig.json`,
  `vite.config.ts`, and an `npm run build` script.
- [ ] Add `openapi-typescript` generator and commit
  `frontend/src/api/generated/` to the repo.
- [ ] Add typed wrappers under `frontend/src/api/`:
  `auth.ts`, `research.ts`, `watchlist.ts`, `workflow.ts`,
  `market.ts`, `evaluation.ts`.
- [ ] Migrate the 50+ `fetch()` call sites in `app.js` to
  `window.finsight.api.*` typed wrappers.
- [ ] Add `window.finsight.api` shim to `static/app.js`; keep the
  existing IIFE entry point.
- [ ] Add `api-lint` job: boot the lightweight Spring profile,
  regenerate the client, fail the job if the diff is non-empty.
- [ ] Add `api-diff` job: compare `master` spec with PR's spec and
  post a markdown report as a PR comment.
- [ ] Add `vitest` coverage for the typed wrappers (auth,
  research, watchlist).
- [ ] `docs/development.md`: how to regenerate the client, how to
  update the wrapper after a server change, troubleshooting.

## Acceptance criteria

- `npm run build` produces `frontend/dist/app.js` ≤ 200 KB
  minified; the static `app.js` continues to work in the meantime.
- `tsc --noEmit` passes with zero `any` in the wrappers
  (`@typescript-eslint/no-explicit-any: error`).
- The `api-lint` job is required for pull_request and green on
  the latest `master`.
- 100% of `fetch('/api/...')` call sites in `app.js` are routed
  through `window.finsight.api.*`.
- The 6 pre-existing `AuthenticationServiceTest` failures and the
  `DailyRecommendationServiceTest` failure remain unaffected (the
  typecheck + lint jobs are independent of the Java test matrix).

## Out of scope

- Migrating to a full SPA framework (RFC 003).
- Generating non-API helpers (e.g. `webpack.config.js` for legacy
  modules).
- Mocking the network in the static UI (covered by `vitest` with
  MSW).

## References

- `docs/rfcs/RFC-002-openapi-typescript-codegen.md`
- `docs/api.md` (current hand-written API reference)
- `backend/src/main/java/com/finsight/api/` (existing controllers
  that springdoc introspects)

## Estimate

2 weeks. Split into 4 PRs:

1. Build pipeline + first generated batch (~500 LoC, 1 PR)
2. Second batch + wrapper migration (~700 LoC, 1 PR)
3. `app.js` migration + tests (~600 LoC, 1 PR)
4. CI lint/diff jobs + docs (~200 LoC, 1 PR)
