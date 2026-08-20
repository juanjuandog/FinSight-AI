// Typed wrappers for the market surface.
//
// Mirrors `MarketController` plus the history endpoint
// (`/api/market/history/{symbol}`). The quotes endpoint is
// exposed under `research.ts` because its primary consumer is
// the AI analysis workspace.

import type { ApiClient } from './index';

export interface MarketCandle {
  date: string;
  open: number;
  close: number;
  high: number;
  low: number;
  volume: number;
  amount: number;
  amplitude: number;
  changePercent: number;
  change: number;
  turnoverRate: number;
}

export interface MarketHistory {
  symbol: string;
  candles: MarketCandle[];
}

export interface DailyRecommendation {
  recommendations: Array<{
    rank: number;
    symbol: string;
    name: string;
    exchange: string;
    industry: string;
    score: number;
    rating: string;
    currentPrice: number;
    changePercent: number;
    amount: number;
    strategyVersion: string;
  }>;
  generatedAt: string;
  strategyVersion: string;
  universeSize: number;
}

export function marketHistory(
  client: ApiClient,
  symbol: string,
  limit: number
): Promise<MarketHistory> {
  return client
    .GET('/api/market/history/{symbol}', {
      params: { path: { symbol }, query: { limit } }
    })
    .then((res) => (res.data as unknown as MarketHistory) ?? { symbol, candles: [] });
}

export function dailyRecommendations(
  client: ApiClient
): Promise<DailyRecommendation> {
  return client.GET('/api/market/recommendations', {}).then((res) => {
    if (!res.data) {
      return { recommendations: [], generatedAt: '', strategyVersion: '', universeSize: 0 };
    }
    return res.data as unknown as DailyRecommendation;
  });
}

export function refreshRecommendations(client: ApiClient): Promise<void> {
  return client
    .POST('/api/market/recommendations/refresh', {})
    .then(() => undefined);
}
