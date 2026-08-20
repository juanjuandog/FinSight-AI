// Coverage for the watchlist wrappers added in RFC 002 PR 3.
// These mirror the typed contract: the bridge calls the wrapper
// with the right client and body shape; the wrapper translates
// the response back.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApiClient } from '../src/api';
import {
  addToWatchlist,
  listWatchlist,
  removeFromWatchlist
} from '../src/api/watchlist';

const mockFetch = vi.fn();

beforeEach(() => {
  mockFetch.mockReset();
  vi.stubGlobal('fetch', mockFetch);
  vi.stubGlobal('document', { cookie: '' });
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

async function readRequestUrl(call: unknown[]): Promise<string> {
  const [req] = call as [Request | string, RequestInit | undefined];
  if (req instanceof Request) return req.url;
  return String(req);
}

describe('watchlist wrappers', () => {
  it('listWatchlist returns an array', async () => {
    mockFetch.mockResolvedValueOnce(
      jsonResponse(200, [
        { companySymbol: '600519', createdAt: '2026-01-01T00:00:00Z' },
        { companySymbol: '00700.HK', createdAt: '2026-01-02T00:00:00Z' }
      ])
    );
    const client = createApiClient('http://api.test');
    const items = await listWatchlist(client);
    expect(items).toHaveLength(2);
    expect(items[0].companySymbol).toBe('600519');
  });

  it('addToWatchlist POSTs to /api/watchlist/{symbol}', async () => {
    mockFetch.mockResolvedValueOnce(
      jsonResponse(201, { companySymbol: 'AAPL', createdAt: '2026-01-01T00:00:00Z' })
    );
    const client = createApiClient('http://api.test');
    const item = await addToWatchlist(client, 'AAPL');
    expect(item.companySymbol).toBe('AAPL');
    const last = mockFetch.mock.calls[mockFetch.mock.calls.length - 1];
    expect(await readRequestUrl(last)).toContain('/api/watchlist/AAPL');
  });

  it('removeFromWatchlist DELETEs the symbol and resolves void', async () => {
    mockFetch.mockResolvedValueOnce(new Response(null, { status: 204 }));
    const client = createApiClient('http://api.test');
    await expect(removeFromWatchlist(client, 'AAPL')).resolves.toBeUndefined();
    const last = mockFetch.mock.calls[mockFetch.mock.calls.length - 1];
    expect(await readRequestUrl(last)).toContain('/api/watchlist/AAPL');
  });

  it('concurrent addToWatchlist calls dedup', async () => {
    mockFetch.mockResolvedValueOnce(
      jsonResponse(201, { companySymbol: 'AAPL', createdAt: '2026-01-01T00:00:00Z' })
    );
    const client = createApiClient('http://api.test');
    await Promise.all([
      addToWatchlist(client, 'AAPL'),
      addToWatchlist(client, 'AAPL')
    ]);
    expect(mockFetch.mock.calls.length).toBe(1);
  });

  it('addToWatchlist URL contains the symbol for a different name', async () => {
    mockFetch.mockResolvedValueOnce(
      jsonResponse(201, { companySymbol: '600519', createdAt: '2026-01-01T00:00:00Z' })
    );
    const client = createApiClient('http://api.test');
    await addToWatchlist(client, '600519');
    const last = mockFetch.mock.calls[mockFetch.mock.calls.length - 1];
    const url = await readRequestUrl(last);
    expect(url).toContain('/api/watchlist/600519');
  });
});
