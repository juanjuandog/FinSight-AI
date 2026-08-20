# Frontend Typed Client

The static `index.html` loads two new files:

```html
<script src="/dom.js"></script>            <!-- (RFC 005 helper) -->
<script src="/app.bundle.js"></script>      <!-- RFC 002 typed bundle -->
<script src="/api-bridge.js"></script>      <!-- RFC 002 IIFE bridge -->
```

`app.bundle.js` exposes a typed TypeScript client under
`window.finsight.api`. Each wrapper takes the request shape
(opinionated input) and returns a typed domain object (opinionated
output), so the static UI no longer parses response JSON by hand.

`api-bridge.js` exposes `window.finsight.bridge`, an object the
existing `app.js` IIFE uses. Every bridge method either delegates
to `window.finsight.api.*` (typed) or falls back to the existing
`fetch()` path. This means the typed layer is opt-in per call
site and the IIFE continues to work without the bundle.

## Quick reference

```js
// Login (typed)
const session = await window.finsight.bridge.login({
  email: 'u@x.com',
  password: 'secret'
});

// Register (typed)
const session = await window.finsight.bridge.register({
  email: 'u@x.com',
  password: 'longenough12',
  verificationCode: '123456'
});

// Logout
await window.finsight.bridge.logout();

// Watchlist
const items = await window.finsight.bridge.listWatchlist();
const item = await window.finsight.bridge.addToWatchlist('AAPL');
await window.finsight.bridge.removeFromWatchlist('AAPL');

// Market
const q = await window.finsight.bridge.quote('600519');
const history = await window.finsight.bridge.history('600519', 120);
const recs = await window.finsight.bridge.dailyRecommendations();

// Research
const a = await window.finsight.bridge.analyzeStock('600519');
const latest = await window.finsight.bridge.latestAnalysis('600519');
const task = await window.finsight.bridge.createResearchTask('600519');
const progress = await window.finsight.bridge.getResearchTaskProgress(task.id);
```

The full set is in
[`backend/src/main/resources/static/api-bridge.js`](../backend/src/main/resources/static/api-bridge.js).

## Regenerating the typed schema

The committed `frontend/src/api/generated/schema.d.ts` is a
permissive placeholder until `npm run api:generate` regenerates
it from `/v3/api-docs`. To keep it in sync:

```bash
# 1. Start the backend (any profile).
cd backend && mvn -DskipTests spring-boot:run &

# 2. Regenerate.
cd ../frontend && npm run api:generate

# 3. Typecheck + test.
npm run typecheck && npm test

# 4. Commit the regenerated schema.d.ts.
git add src/api/generated/schema.d.ts
git commit -m "chore(api): regenerate typed schema from /v3/api-docs"
```

CI runs the same on every push: the `api-lint` job fails the
build if the freshly generated file is not byte-identical to
the committed one.

## Fallback behaviour

When `window.finsight.api` is missing (bundle failed to load,
CSP blocked, etc.), every bridge method falls back to the
original `fetch()` semantics. The bridge also includes
`bridge.request(path, options)` which exposes the raw fetch
path used by call sites we have not yet migrated.

## Adding a new endpoint

1. Add the controller method in the relevant
   `backend/src/main/java/com/finsight/api/*Controller.java`.
2. Regenerate the schema with `npm run api:generate`.
3. Add a typed wrapper in `frontend/src/api/<module>.ts`.
4. Optionally, add a bridge method in
   `backend/src/main/resources/static/api-bridge.js` so the
   IIFE can opt in.
5. Add a vitest case in `frontend/test/<module>.test.ts`.

For new wrappers, prefer reusing `once()` for any call that
should be deduplicated across concurrent UI clicks (e.g.,
analysis requests on the same symbol).

## Migration status

| Surface | Typed wrapper | Bridge method | IIFE call sites migrated |
| --- | --- | --- | --- |
| auth (login / register / logout / session / verification / password reset) | ✅ | ✅ | ✅ all |
| watchlist | ✅ | ✅ | ✅ all |
| market (quote / history / recommendations / refresh) | ✅ | ✅ | ✅ all |
| research (analyze / latest / history / quote) | ✅ | ✅ | ✅ all |
| workflow (tasks / progress / trace) | ✅ | ✅ | ✅ all |
| intelligence (timeline / metrics / risk signals / document-index search) | ✅ | ✅ | ✅ all |
| companies (search / count / list / analysis-status) | ✅ | ✅ | ✅ all |
| evaluation (rag cases / run) | ✅ | (TODO) | n/a |
| ingestion (demo / async) | ✅ | (TODO) | n/a |

PR 7 closed out the IIFE migration: every \`request(...)\` call
site in app.js is now paired with a \`bridge.X(...)\` ternary. The
remaining items are evaluation + ingestion, which the current IIFE
does not call.
