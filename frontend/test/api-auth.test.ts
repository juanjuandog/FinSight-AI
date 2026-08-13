import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApiClient } from '../src/api';
import { login, logout, register } from '../src/api/auth';

const mockFetch = vi.fn();

beforeEach(() => {
  mockFetch.mockReset();
  vi.stubGlobal('fetch', mockFetch);
  // openapi-fetch reads cookies from document; provide a minimal stub.
  vi.stubGlobal('document', {
    cookie: 'finsight_csrf=test-csrf'
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' }
  });
}

describe('auth wrapper', () => {
  it('login POSTs and returns the session', async () => {
    mockFetch.mockResolvedValueOnce(
      jsonResponse(200, {
        token: 'tok',
        user: { id: 'u1', email: 'u@x.com', status: 'ACTIVE', createdAt: '2026-01-01T00:00:00Z' },
        expiresAt: '2026-12-31T00:00:00Z'
      })
    );
    const client = createApiClient('http://api.test');
    const session = await login(client, { email: 'u@x.com', password: 'secret' });
    expect(session.token).toBe('tok');
    expect(session.user.email).toBe('u@x.com');
    const [url, init] = mockFetch.mock.calls[0];
    expect(url).toContain('/api/auth/login');
    expect(init.method).toBe('POST');
    const headers = init.headers as Record<string, string>;
    expect(headers['X-CSRF-Token']).toBe('test-csrf');
  });

  it('register sends the verification code in the body', async () => {
    mockFetch.mockResolvedValueOnce(
      jsonResponse(201, {
        token: 'tok2',
        user: { id: 'u2', email: 'v@x.com', status: 'ACTIVE', createdAt: '2026-01-01T00:00:00Z' },
        expiresAt: '2026-12-31T00:00:00Z'
      })
    );
    const client = createApiClient('http://api.test');
    await register(client, { email: 'v@x.com', password: 'longenough12', verificationCode: '123456' });
    const [, init] = mockFetch.mock.calls[0];
    const body = JSON.parse(init.body as string);
    expect(body.email).toBe('v@x.com');
    expect(body.verificationCode).toBe('123456');
  });

  it('logout uses the POST endpoint and resolves to void', async () => {
    mockFetch.mockResolvedValueOnce(new Response(null, { status: 204 }));
    const client = createApiClient('http://api.test');
    await expect(logout(client)).resolves.toBeUndefined();
  });

  it('non-2xx responses throw ApiError', async () => {
    mockFetch.mockResolvedValueOnce(
      jsonResponse(401, { type: 'about:blank', title: 'Unauthorized', status: 401, detail: 'auth required' })
    );
    const client = createApiClient('http://api.test');
    await expect(login(client, { email: 'u@x.com', password: 'bad' })).rejects.toThrow();
  });
});
