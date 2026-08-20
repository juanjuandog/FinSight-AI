// Typed wrappers for the companies surface and the auth
// password-reset flows not yet covered by `auth.ts`.

import type { ApiClient } from './index';

export interface CompanySummary {
  symbol: string;
  name: string;
  exchange: string;
  industry: string;
}

export interface CompanyDetail extends CompanySummary {
  marketCap?: number | null;
  listingDate?: string | null;
  description?: string | null;
}

export interface CompanySearchResult {
  matches: CompanySummary[];
}

export function searchCompanies(
  client: ApiClient,
  query: string,
  limit = 12
): Promise<CompanySearchResult> {
  return client
    .GET('/api/companies/search', {
      params: { query: { q: query, limit } }
    })
    .then((res) => (res.data as unknown as CompanySearchResult) ?? { matches: [] });
}

export function listCompanies(
  client: ApiClient,
  limit: number
): Promise<{ items: CompanySummary[] }> {
  return client
    .GET('/api/companies', {
      params: { query: { limit } }
    })
    .then((res) => (res.data as unknown as { items: CompanySummary[] }) ?? { items: [] });
}

export function companyCount(client: ApiClient): Promise<{ total: number }> {
  return client
    .GET('/api/companies/count', {})
    .then((res) => (res.data as unknown as { total: number }) ?? { total: 0 });
}

export function analysisStatus(
  client: ApiClient,
  symbol: string
): Promise<{ ready: boolean; lastAnalyzedAt: string | null }> {
  return client
    .GET('/api/companies/{companySymbol}/analysis-status', {
      params: { path: { companySymbol: symbol } }
    })
    .then(
      (res) =>
        (res.data as unknown as { ready: boolean; lastAnalyzedAt: string | null }) ?? {
          ready: false,
          lastAnalyzedAt: null
        }
    );
}

export function requestPasswordReset(client: ApiClient, email: string): Promise<void> {
  return client
    .POST('/api/auth/password-reset/request', { body: { email } })
    .then(() => undefined);
}

export function confirmPasswordReset(
  client: ApiClient,
  rawToken: string,
  newPassword: string
): Promise<void> {
  return client
    .POST('/api/auth/password-reset/confirm', {
      body: { token: rawToken, password: newPassword }
    })
    .then(() => undefined);
}

export function triggerBatchAnalysis(client: ApiClient): Promise<unknown> {
  return client.POST('/api/companies/batch-analysis', {}).then((res) => res.data);
}

export function syncASharesUniverse(client: ApiClient): Promise<unknown> {
  return client.POST('/api/companies/sync-a-shares', {}).then((res) => res.data);
}
