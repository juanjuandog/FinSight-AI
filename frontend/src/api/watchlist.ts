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
  client: ApiClient,
  symbol: string
): Promise<WatchlistItem> {
  return once(`watchlist:add:${symbol}`, () =>
    client
      .POST('/api/watchlist', { body: { companySymbol: symbol } })
      .then((res) => {
        if (res.error || !res.data) {
          throw new Error('add to watchlist failed');
        }
        return res.data as unknown as WatchlistItem;
      })
  );
}

export function removeFromWatchlist(
  client: ApiClient,
  symbol: string
): Promise<void> {
  return once(`watchlist:remove:${symbol}`, () =>
    client
      .DELETE('/api/watchlist/{symbol}', {
        params: { path: { symbol } }
      })
      .then(() => undefined)
  );
}
