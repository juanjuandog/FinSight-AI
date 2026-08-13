// Entry point for the typed-client bundle.
//
// The bundle exposes `window.finsight.api` so the existing
// `app.js` (still served as the static entry) can opt in to
// typed calls one workspace at a time. The full migration to
// `main.ts` as the primary entry point is the work of RFC 005.

import {
  ApiError,
  auth as authApi,
  companies as companiesApi,
  createApiClient,
  intelligence as intelligenceApi,
  market as marketApi,
  once,
  research as researchApi,
  watchlist as watchlistApi,
  workflow as workflowApi
} from './api';

const client = createApiClient();

const api = {
  client,
  once,
  ApiError,
  auth: authApi,
  companies: companiesApi,
  intelligence: intelligenceApi,
  market: marketApi,
  research: researchApi,
  watchlist: watchlistApi,
  workflow: workflowApi
};

declare global {
  interface Window {
    finsight: {
      api?: typeof api;
    };
  }
}

window.finsight = {
  ...(window.finsight ?? {}),
  api
};

export {
  api,
  ApiError,
  authApi,
  client,
  companiesApi,
  intelligenceApi,
  marketApi,
  once,
  researchApi,
  watchlistApi,
  workflowApi
};
