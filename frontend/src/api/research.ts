// Typed wrappers for the research surface.
//
// Mirrors `ResearchController`, `AnalysisController`, and
// `CompanyIntelligenceController`. Each wrapper maps the openapi-fetch
// `paths` types to a clean domain interface and routes through
// `once()` for in-flight deduplication.

import { type ApiClient, once } from './index';

export interface MarketQuote {
  symbol: string;
  exchange: string;
  name: string;
  currentPrice: number;
  change: number;
  changePercent: number;
  open: number;
  high: number;
  low: number;
  volume: number;
  amount: number;
  tradeDate: string;
  tradeTime: string;
  source: string;
  realtime: boolean;
  message?: string;
}

export interface StockAiAnalysis {
  rating: string;
  summary: string;
  positivePoints: string[];
  riskPoints: string[];
  confidence: number;
  citations: string[];
  model: string;
  source: string;
  aiGenerated: boolean;
  reportId?: string | null;
  generatedAt?: string | null;
  cacheHit?: boolean;
  dataSnapshotHash?: string | null;
  reportVersion?: number;
  guidance?: ResearchGuidance;
}

export interface ResearchGuidance {
  researchPriority: string;
  dataCompleteness: number;
  summary: string;
  supportingEvidence: string[];
  confirmationConditions: string[];
  invalidationSignals: string[];
  nextResearchActions: string[];
}

export interface StockAnalysisHistory {
  items: StockAiAnalysis[];
}

export function analyzeStock(
  client: ApiClient,
  symbol: string
): Promise<StockAiAnalysis> {
  return once(`research:analyze:${symbol}`, () =>
    client
      .POST('/api/research/stock/{symbol}/analyze', {
        params: { path: { symbol } }
      })
      .then((res) => {
        if (res.error || !res.data) {
          throw new Error('analyze failed');
        }
        return res.data as unknown as StockAiAnalysis;
      })
  );
}

export function latestAnalysis(
  client: ApiClient,
  symbol: string
): Promise<StockAiAnalysis | null> {
  return client
    .GET('/api/research/stock/{symbol}/latest', {
      params: { path: { symbol } }
    })
    .then((res) => (res.data as unknown as StockAiAnalysis) ?? null);
}

export function analysisHistory(
  client: ApiClient,
  symbol: string,
  limit = 10
): Promise<StockAnalysisHistory> {
  return client
    .GET('/api/research/stock/{symbol}/history', {
      params: { path: { symbol }, query: { limit } }
    })
    .then((res) => (res.data as unknown as StockAnalysisHistory) ?? { items: [] });
}

export function quote(
  client: ApiClient,
  symbol: string
): Promise<MarketQuote> {
  return client
    .GET('/api/market/quote/{symbol}', {
      params: { path: { symbol } }
    })
    .then((res) => {
      if (res.error || !res.data) {
        throw new Error('quote failed');
      }
      return res.data as unknown as MarketQuote;
    });
}
