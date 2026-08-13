// Typed wrappers for the watchlist surface.

import { type ApiClient, once } from './index';

export interface WatchlistItem {
  companySymbol: string;
  createdAt: string;
}

export function listWatchlist(client: ApiClient): Promise<WatchlistItem[]> {
  return client
    .GET('/api/watchlist', {})
    .then((res) => (res.data as unknown as WatchlistItem[]) ?? []);
}

export function addToWatchlist(
  _client: ApiClient,
  symbol: string
): Promise<WatchlistItem> {
  return once(`watchlist:add:${symbol}`, async () => {
    // The controller endpoint is POST /api/watchlist/{symbol}; use
    // fetch directly because the placeholder schema doesn't know
    // about this path yet. The client argument is kept for parity
    // with the other wrappers and will be used once api-lint has
    // generated the real schema.
    const response = await fetch(
      `/api/watchlist/${encodeURIComponent(symbol)}`,
      { method: 'POST', credentials: 'same-origin' }
    );
    if (!response.ok) {
      throw new Error('add to watchlist failed');
    }
    if (response.status === 204) return { companySymbol: symbol, createdAt: '' };
    const body = (await response.json()) as WatchlistItem;
    return body;
  });
}

export function removeFromWatchlist(
  _client: ApiClient,
  symbol: string
): Promise<void> {
  return once(`watchlist:remove:${symbol}`, async () => {
    const response = await fetch(
      `/api/watchlist/${encodeURIComponent(symbol)}`,
      { method: 'DELETE', credentials: 'same-origin' }
    );
    if (!response.ok && response.status !== 204) {
      throw new Error('remove from watchlist failed');
    }
  });
}
