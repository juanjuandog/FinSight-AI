// Sanity tests for the high-traffic wrappers added in RFC 002 PR 3.
// Most wrappers are thin pass-throughs to `openapi-fetch`; the bulk
// of the value test happens in api-auth.test.ts. This file covers
// the response-shape contracts and the once() dedup across the
// rest of the surface.

import { describe, expect, it, vi } from 'vitest';
import createClient from 'openapi-fetch';
import { ApiError, createApiClient, once } from '../src/api';

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' }
  });
}

describe('ApiError from middleware', () => {
  it('preserves the body in problemDetail', async () => {
    const problemBody = {
      type: 'about:blank',
      title: 'Too Many Requests',
      status: 429,
      detail: 'slow down',
      traceparent: '00-aaaa-bbbb-01'
    };
    const err = new ApiError(
      429,
      problemBody.title,
      problemBody.detail,
      problemBody
    );
    expect(err.problemDetail).toBe(problemBody);
    expect((err.problemDetail as { traceparent?: string }).traceparent).toBe(
      '00-aaaa-bbbb-01'
    );
  });
});

describe('once dedup', () => {
  it('shares the result among concurrent callers', async () => {
    const fake = vi.fn(async () => 'value');
    const [a, b] = await Promise.all([once('dedup', fake), once('dedup', fake)]);
    expect(a).toBe('value');
    expect(b).toBe('value');
    expect(fake).toHaveBeenCalledTimes(1);
  });
});

describe('openapi-fetch shape', () => {
  it('returns a typed client for the placeholder schema', () => {
    const client = createApiClient('http://api.test') as ReturnType<typeof createClient<any>>;
    expect(typeof client.POST).toBe('function');
    expect(typeof client.GET).toBe('function');
    expect(typeof client.DELETE).toBe('function');
  });
});

describe('fetch error path', () => {
  it('rejects with ApiError when the server returns 4xx', async () => {
    const mockFetch = vi.fn(
      async () => jsonResponse(401, { type: 'about:blank', title: 'Unauthorized', status: 401, detail: 'login required' })
    );
    vi.stubGlobal('fetch', mockFetch);
    vi.stubGlobal('document', { cookie: '' });
    try {
      const client = createApiClient('http://api.test');
      await expect(
        client.POST('/api/auth/login', { body: { email: 'a@b.c', password: 'secret' } })
      ).rejects.toBeInstanceOf(ApiError);
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it('returns null body for status 204 without erroring', async () => {
    const mockFetch = vi.fn(async () => new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', mockFetch);
    vi.stubGlobal('document', { cookie: '' });
    try {
      const client = createApiClient('http://api.test');
      const res = await client.POST('/api/auth/logout', {});
      expect(res.response.status).toBe(204);
      expect(res.data == null || Object.keys(res.data).length === 0).toBe(true);
      expect(res.error).toBeUndefined();
    } finally {
      vi.unstubAllGlobals();
    }
  });
});
