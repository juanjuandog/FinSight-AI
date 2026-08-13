# Issue 005: Frontend SPA Modularization (TypeScript + Vite)

## Summary

Replace the 1779-line `app.js` IIFE with a typed, modular
TypeScript + Vite build. Ship a single `/app.bundle.js` from
Spring Boot, tighten CSP to drop `'unsafe-inline'`, and add unit
test coverage for the workspace modules.

## Motivation

- The single-file IIFE has no module boundary, no test coverage,
  and blocks any CSP tightening.
- Each workspace contributor must currently read ~1000 lines of
  unrelated code before making a change.
- The Vite dev server gives a much faster iteration loop than
  "edit JS, restart Spring Boot".
- The existing `frontend-maven-plugin` pattern (already used by
  ~50% of Spring Boot projects) keeps the build one command.

## Tasks

- [ ] Add `frontend/` workspace with `package.json`,
  `tsconfig.json`, `vite.config.ts`, and `index.html` (copied
  from `backend/src/main/resources/static/index.html`).
- [ ] Add `frontend-maven-plugin` to `backend/pom.xml` so
  `mvn package` runs `npm ci && npm run build` automatically.
- [ ] Extract `core/dom.ts`, `core/router.ts`, `core/events.ts`,
  `core/state.ts` from `app.js` (RFC 003: keep the IIFE
  working via `module-bridge.ts` until cut-over).
- [ ] Migrate `auth/`, `watchlist/`, `marketScan/`, `company/`,
  `analysis/`, `evidence/`, `events/` workspaces to typed
  modules.
- [ ] Add `vitest` for the workspace modules; reach 50% line
  coverage.
- [ ] Tighten `SecurityHeadersFilter` CSP: drop
  `'unsafe-inline'` for `script-src`.
- [ ] Delete the legacy `app.js`; rename `module-bridge.ts` to
  `main.ts`; update `index.html` to load `/app.bundle.js`.
- [ ] `docs/development.md`: how to run `npm run dev`, how to
  build, how to write a workspace module.

## Acceptance criteria

- `npm run build` produces a single `app.bundle.js` ≤ 200 KB
  minified, with a source map.
- `tsc --noEmit` passes with `strict: true`.
- `npm run test` runs the workspace unit tests; coverage report
  shows ≥ 50% lines on `frontend/src/workspaces/`.
- The Spring Boot response for any page sets
  `Content-Security-Policy: ... script-src 'self' ...` (no
  `unsafe-inline` for scripts).
- Manually walking through the 6 workspaces in a browser shows
  no regression vs. the legacy IIFE: every interaction, hover
  state, chart, modal, auth flow behaves identically.

## Out of scope

- Migrating to React / Vue / Svelte.
- Server-side rendering.
- Replacing the static asset hosting with a separate origin.

## References

- `docs/rfcs/RFC-005-frontend-spa-modularization.md`
- `docs/rfcs/RFC-002-openapi-typescript-codegen.md` (the typed
  client that the modules consume)
- `backend/src/main/resources/static/app.js` (the legacy file to
  be deleted)

## Estimate

5 weeks. Split into 6 PRs:

1. Build pipeline + core modules + module-bridge (~600 LoC, 1 PR)
2. Auth + Watchlist workspaces (~500 LoC, 1 PR)
3. MarketScan + Company workspaces (~700 LoC, 1 PR)
4. Analysis + Evidence + Events workspaces (~800 LoC, 1 PR)
5. CSP tightening + cut-over + `app.js` deletion (~200 LoC, 1 PR)
6. Tests + docs (~700 LoC, 1 PR)
