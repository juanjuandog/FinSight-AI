// Entry point for the typed-client bundle.
//
// The bundle exposes `window.finsight.api` so the existing
// `app.js` (still served as the static entry) can opt in to
// typed calls one workspace at a time. The full migration to
// `main.ts` as the primary entry point is the work of RFC 005.

import { createApiClient } from './api';
import { analyzeStock, latestAnalysis, quote } from './api/research';
import {
  addToWatchlist,
  listWatchlist,
  removeFromWatchlist
} from './api/watchlist';
import {
  currentSession,
  issueVerificationCode,
  login,
  logout,
  register
} from './api/auth';

const api = createApiClient();

window.finsight = {
  ...(window.finsight ?? {}),
  api
};

export {
  api,
  analyzeStock,
  latestAnalysis,
  quote,
  addToWatchlist,
  listWatchlist,
  removeFromWatchlist,
  currentSession,
  issueVerificationCode,
  login,
  logout,
  register
};
