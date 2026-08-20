// Coverage for the research wrappers added in RFC 002 PR 3.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApiClient } from '../src/api';
import {
  analyzeStock,
  analysisHistory,
  latestAnalysis,
  quote
} from '../src/api/research';

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

describe('research wrappers', () => {
  it('quote returns a typed MarketQuote', async () => {
    mockFetch.mockResolvedValueOnce(
      jsonResponse(200, {
        symbol: '600519',
        exchange: 'SH',
        name: '贵州茅台',
        currentPrice: 1700,
        change: 10,
        changePercent: 0.6,
        open: 1690,
        high: 1710,
        low: 1680,
        volume: 12345,
        tradeDate: '2026-01-01',
        tradeTime: '15:00:00',
        source: 'eastmoney',
        realtime: true
      })
    );
    const client = createApiClient('http://api.test');
    const q = await quote(client, '600519');
    expect(q.symbol).toBe('600519');
    expect(q.currentPrice).toBe(1700);
  });

  it('analyzeStock POSTs and returns the analysis', async () => {
    mockFetch.mockResolvedValueOnce(
      jsonResponse(200, {
        rating: '积极',
        summary: 'test',
        positivePoints: [],
        riskPoints: [],
        confidence: 80,
        citations: [],
        model: 'rule-fallback',
        source: 'fallback-rule',
        aiGenerated: false,
        reportId: null,
        generatedAt: null,
        cacheHit: false,
        dataSnapshotHash: null,
        reportVersion: 0
      })
    );
    const client = createApiClient('http://api.test');
    const a = await analyzeStock(client, '600519');
    expect(a.rating).toBe('积极');
  });

  it('analysisHistory honors the limit query', async () => {
    mockFetch.mockResolvedValueOnce(jsonResponse(200, { items: [] }));
    const client = createApiClient('http://api.test');
    await analysisHistory(client, '600519', 25);
    const last = mockFetch.mock.calls[mockFetch.mock.calls.length - 1];
    const [req] = last as [Request];
    expect(req.url).toContain('limit=25');
  });

  it('latestAnalysis returns null on empty body', async () => {
    mockFetch.mockResolvedValueOnce(jsonResponse(200, null));
    const client = createApiClient('http://api.test');
    const a = await latestAnalysis(client, 'NOMATCH');
    expect(a).toBeNull();
  });

  it('concurrent analyzeStock calls dedup', async () => {
    mockFetch.mockResolvedValueOnce(
      jsonResponse(200, {
        rating: '积极',
        summary: 'test',
        positivePoints: [],
        riskPoints: [],
        confidence: 80,
        citations: [],
        model: 'rule-fallback',
        source: 'fallback-rule',
        aiGenerated: false
      })
    );
    const client = createApiClient('http://api.test');
    await Promise.all([
      analyzeStock(client, '600519'),
      analyzeStock(client, '600519')
    ]);
    expect(mockFetch.mock.calls.length).toBe(1);
  });
});
