// FinSight typed API client.
//
// The generated `schema.d.ts` is produced at build time by
// `npm run api:generate` against a running Spring Boot instance
// exposing `/v3/api-docs`. We do not commit the generated file
// until CI is green on the api-lint job; in the meantime the
// `openapi-fetch` client will fall back to `any` typed paths
// because the type is empty.
//
// The wrappers below are the surface the static frontend uses.
// Each wrapper is responsible for: header propagation (X-CSRF-Token,
// traceparent), error normalisation to `ApiError`, and surfacing a
// per-symbol once() lock so the UI cannot fire two parallel
// analyses for the same symbol.

import createClient, { type Middleware } from 'openapi-fetch';
import type { paths } from './generated/schema';

export type ApiPaths = paths;
/**
 * The typed-client type is loosened to `any` until `npm run api:generate`
 * has produced the real schema. The api-lint CI job ensures the
 * generated schema replaces this placeholder before merge, at which
 * point we drop the `as any` cast and the response types light up.
 */
export type ApiClient = ReturnType<typeof createClient<any>>;

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly title: string,
    message: string,
    public readonly problemDetail?: unknown
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

const csrfHeader: Middleware = {
  async onRequest({ request }) {
    const csrf = readCookie('finsight_csrf');
    if (csrf) {
      request.headers.set('X-CSRF-Token', csrf);
    }
    return request;
  }
};

const errorNormaliser: Middleware = {
  async onResponse({ response }) {
    if (response.ok) {
      return response;
    }
    let body: unknown = null;
    try {
      body = await response.clone().json();
    } catch {
      body = await response.clone().text();
    }
    const title =
      (body && typeof body === 'object' && 'title' in body
        ? String((body as { title?: unknown }).title ?? '')
        : '') || response.statusText;
    const detail =
      (body && typeof body === 'object' && 'detail' in body
        ? String((body as { detail?: unknown }).detail ?? '')
        : '') || response.statusText;
    throw new ApiError(response.status, title, detail, body);
  }
};

function readCookie(name: string): string {
  if (typeof document === 'undefined') {
    return '';
  }
  const match = document.cookie.match(new RegExp('(?:^|; )' + name + '=([^;]*)'));
  return match ? decodeURIComponent(match[1]) : '';
}

const inflight = new Map<string, Promise<unknown>>();

/**
 * `once(key, fn)` deduplicates in-flight requests that share the same key.
 * Concurrent callers receive the same promise, so the UI cannot trigger
 * two parallel analyses for the same symbol.
 */
export function once<T>(key: string, fn: () => Promise<T>): Promise<T> {
  const existing = inflight.get(key) as Promise<T> | undefined;
  if (existing) {
    return existing;
  }
  const next = fn().finally(() => {
    inflight.delete(key);
  });
  inflight.set(key, next);
  return next;
}

export function createApiClient(baseUrl = ''): ApiClient {
  const client = createClient<any>({
    baseUrl,
    credentials: 'include'
  }) as ApiClient;
  client.use(csrfHeader, errorNormaliser);
  return client;
}

// Re-export each module as a namespace so callers can do
// `import { auth, research } from '../api'` and use
// `auth.login(client, ...)`. Mirrors the structure exposed on
// `window.finsight.api` in `main.ts`.
export * as auth from './auth';
export * as research from './research';
export * as watchlist from './watchlist';
export * as market from './market';
export * as intelligence from './intelligence';
export * as companies from './companies';
export * as workflow from './workflow';
