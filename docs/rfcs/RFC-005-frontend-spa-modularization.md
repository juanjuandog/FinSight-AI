# RFC 005: Frontend SPA Modularization (TypeScript + Vite)

- **Status:** Draft
- **Date:** 2026-08-13
- **Author:** FinSight contributors
- **Tracker:** Issue TBD

## Context

`backend/src/main/resources/static/app.js` is a 1779-line IIFE that
mixes:

- Global state (`companies`, `latestQuote`, `latestAiAnalysis`,
  `authUser`, `csrfToken`, `evidenceScope`, `chartGeneration`, ...)
- DOM helpers (`$`, `cssToken`, `escapeHtml`, `formatDateTime`)
- API plumbing (every `fetch` is inlined)
- Workspace rendering for 6 different views
- Auth UI (login, register, password reset, CSRF)
- Watchlist UI
- Workflow triggers
- A custom hash router

The IIFE has no module boundary, no test coverage, and any
contributor who needs to add a workspace must read 1000+ lines of
unrelated code first. CSP is currently `unsafe-inline` for both
script and style; a hard CSP is blocked by this monolithic blob.

## Goals

1. Split the 1779-line `app.js` into ~25 ES modules with explicit
   imports and a single entry point.
2. Type the whole frontend with TypeScript; lift every workspace
   into a typed component.
3. Ship a Vite-built bundle (≤ 200 KB minified) that Spring Boot
   serves as a single `/app.bundle.js`.
4. Tighten CSP to drop `'unsafe-inline'` and `'unsafe-eval'` for
   scripts.
5. Reach 50%+ unit-test coverage of the workspace modules.

## Non-Goals

- Migrating off vanilla DOM (no React, no Vue). The bundle is small
  enough to keep this simple.
- Server-side rendering.
- Replacing the static assets served by Spring Boot with a separate
  origin (still single deployable).

## Design

### Module layout

```
frontend/
  package.json
  tsconfig.json
  vite.config.ts
  index.html               (the source HTML, copied from backend)
  src/
    main.ts                (entry, replaces app.js)
    types.d.ts
    core/
      dom.ts               (the helpers from dom.js + new utilities)
      router.ts            (hash router extracted from app.js)
      events.ts            (typed event bus)
      state.ts             (typed store, replaces globals)
    api/
      index.ts             (re-exports from RFC 002)
      csrf.ts
      errors.ts
    workspaces/
      marketScan/
        view.ts
        view.test.ts
        recommend.ts
      company/
        view.ts
        view.test.ts
        priceChart.ts
      analysis/
        view.ts
        view.test.ts
        guidance.ts
      evidence/
        view.ts
        view.test.ts
      events/
        view.ts
        view.test.ts
      watchlist/
        view.ts
        view.test.ts
    auth/
      view.ts
      view.test.ts
      session.ts
  test/
    setup.ts
```

### State management

A tiny ~80-LoC `core/state.ts` provides `createStore<T>(initial)`
with `get()`, `set()`, `subscribe()`. Workspaces subscribe to the
slice they care about; the DOM updates on change. This is enough
for the existing app; pulling in Redux or Zustand is overkill.

```ts
type Store<T> = {
  get: () => T;
  set: (next: T | ((prev: T) => T)) => void;
  subscribe: (fn: (next: T) => void) => () => void;
};

export function createStore<T>(initial: T): Store<T> { ... }
```

### Build pipeline

`vite.config.ts`:

```ts
export default defineConfig({
  build: {
    outDir: 'dist',
    rollupOptions: {
      input: 'src/main.ts',
      output: { manualChunks: undefined }
    },
    target: 'es2022',
    sourcemap: true
  },
  plugins: [sri()],
  server: { port: 5173, proxy: { '/api': 'http://localhost:8080' } }
});
```

A new Maven plugin `frontend-maven-plugin` runs `npm ci && npm run
build` during the `generate-resources` phase and copies
`frontend/dist/app.bundle.js` to
`backend/src/main/resources/static/app.bundle.js`. The legacy
`app.js` is deleted; `index.html` loads `/app.bundle.js` instead.

### CSP tightening

The current `SecurityHeadersFilter` (commit `07f3c15`) sets
`script-src 'self' 'unsafe-inline'`. With a built bundle, the
`'unsafe-inline'` can go: the bundle has no inline scripts and no
`eval`. The migration PR removes it and adds a regression test
that asserts the response header.

### Migration plan

This is the largest single RFC in the bundle. The plan is to
land the new structure **alongside** the old, then move call
sites one workspace at a time:

1. Land the build pipeline + `frontend/` skeleton. `app.js`
   continues to be the runtime entry.
2. Extract `core/dom.ts`, `core/router.ts`, `core/state.ts` from
   `app.js`; have `app.js` `import` them via a small
   `module-bridge.ts`. Verify by hand that the existing flows
   still work.
3. Move the auth view (the smallest) to a typed workspace.
4. Move the watchlist view.
5. Move the marketScan view.
6. Move the company + price chart view.
7. Move the analysis + evidence + events views.
8. Delete the legacy `app.js`; rename `module-bridge.ts` to
   `main.ts`; tighten CSP.
9. Add `vitest` coverage threshold; reach 50% line coverage on
   workspace modules.

## Open questions

- Should we add a route-data prefetch (e.g. on hover)? Decision:
  no, the existing in-memory caches already serve the
  same symbol in < 50 ms.
- Charts: the existing inline `<svg>` rendering is sufficient; do
  we introduce a chart library? Decision: no for now, add
  `uplot` only when we need > 1k points.

## Estimated LoC

- Build pipeline + configs: ~200 LoC
- `core/` modules: ~500 LoC
- `api/` wrappers (carry-over from RFC 002): ~700 LoC
- 6 workspaces × ~300 LoC each: ~1,800 LoC
- Tests: ~500 LoC
- **Total: ~3,700 LoC** (not counting deletions from `app.js`).
