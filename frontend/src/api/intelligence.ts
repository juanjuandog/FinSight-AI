// Typed wrappers for the company intelligence + document index surfaces.
//
// Mirrors `CompanyIntelligenceController`, `DocumentIndexController`,
// and the metrics reads at `/api/metrics/{symbol}` (consumed by the
// evidence search workspace).

import type { ApiClient } from './index';

export interface CompanyTimelineEvent {
  date: string;
  type: string;
  title: string;
  detail: string;
  source: string;
  severity?: number;
}

export interface CompanyTimeline {
  symbol: string;
  events: CompanyTimelineEvent[];
}

export interface FinancialMetric {
  symbol: string;
  fiscalYear: number;
  code: string;
  name: string;
  value: number;
  formulaVersion: string;
}

export interface RiskSignal {
  id: string;
  symbol: string;
  code: string;
  title: string;
  explanation: string | null;
  severity: number;
  detectedAt: string;
}

export interface DocumentSearchHit {
  id: string;
  documentId: string;
  companySymbol: string;
  documentType: string;
  title: string;
  section: string;
  publishedAt: string;
  text: string;
  score: number;
  channel?: string;
}

export interface DocumentSearchResult {
  hits: DocumentSearchHit[];
  total: number;
}

export function companyTimeline(
  client: ApiClient,
  symbol: string
): Promise<CompanyTimeline> {
  return client
    .GET('/api/intelligence/{companySymbol}/timeline', {
      params: { path: { companySymbol: symbol } }
    })
    .then((res) => (res.data as unknown as CompanyTimeline) ?? { symbol, events: [] });
}

export function rebuildIntelligence(
  client: ApiClient,
  symbol: string
): Promise<void> {
  return client
    .POST('/api/intelligence/{companySymbol}/rebuild', {
      params: { path: { companySymbol: symbol } }
    })
    .then(() => undefined);
}

export function companyMetrics(
  client: ApiClient,
  symbol: string
): Promise<FinancialMetric[]> {
  return client
    .GET('/api/metrics/{companySymbol}', {
      params: { path: { companySymbol: symbol } }
    })
    .then((res) => (res.data as unknown as FinancialMetric[]) ?? []);
}

export function companyRiskSignals(
  client: ApiClient,
  symbol: string
): Promise<RiskSignal[]> {
  return client
    .GET('/api/metrics/{companySymbol}/risks', {
      params: { path: { companySymbol: symbol } }
    })
    .then((res) => (res.data as unknown as RiskSignal[]) ?? []);
}

export function searchDocumentIndex(
  client: ApiClient,
  params: { query?: string; symbol?: string; limit?: number }
): Promise<DocumentSearchResult> {
  return client
    .GET(
      params.symbol
        ? '/api/document-index/{companySymbol}/search'
        : '/api/document-index/search',
      {
        params: {
          path: params.symbol ? { companySymbol: params.symbol } : ({} as Record<string, string>),
          query: {
            q: params.query ?? '',
            limit: params.limit ?? 6
          }
        }
      }
    )
    .then((res) => (res.data as unknown as DocumentSearchResult) ?? { hits: [], total: 0 });
}

export function rebuildDocumentIndex(
  client: ApiClient,
  symbol: string
): Promise<void> {
  return client
    .POST('/api/document-index/{companySymbol}/rebuild', {
      params: { path: { companySymbol: symbol } }
    })
    .then(() => undefined);
}
