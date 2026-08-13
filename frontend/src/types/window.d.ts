// Ambient declarations shared between the typed client and the
// static app shell. The static `app.js` continues to run for the
// duration of the RFC 002 migration and exposes a small
// `window.finsight.api` namespace so the rest of the codebase
// can opt in to typed calls one workspace at a time.

import type { ApiClient } from '../api';

declare global {
  interface Window {
    finsight: {
      api?: ApiClient;
    };
  }
}

export {};
