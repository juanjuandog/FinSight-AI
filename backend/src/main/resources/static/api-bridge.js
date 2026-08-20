// Bridge between the typed-client bundle and the static `app.js` IIFE.
//
// Strategy: do not rewrite `app.js`. Instead, route the IIFE's
// internal `request()` helper through `window.finsight.api.*`
// where the path is mapped to a typed wrapper. Paths that have no
// typed wrapper yet still go through the original `fetch()` path.
//
// This keeps the IIFE working, gives every call site a typed
// counterpart in the bundle, and lets us migrate the IIFE
// workspace-by-workspace.

(function () {
  const api = (window.finsight && window.finsight.api) || null;

  function readCookieCsrf() {
    if (typeof document === 'undefined') return '';
    const match = document.cookie.match(
      /(?:^|; )finsight_csrf=([^;]*)/
    );
    return match ? decodeURIComponent(match[1]) : '';
  }

  /**
   * `window.finsight.bridge` is the entry point the static
   * `app.js` calls. It returns a thin facade mirroring the
   * previous `request()` semantics so existing flows keep
   * working while the typed client takes over the dispatch.
   */
  window.finsight = window.finsight || {};
  window.finsight.bridge = {
    /**
     * Translate a single endpoint into a typed-client call when
     * there is a wrapper, or fall back to a plain `fetch()`.
     */
    async request(path, options) {
      const method = String(options && options.method || 'GET').toUpperCase();
      const headers = new Headers((options && options.headers) || {});
      if (!(['GET', 'HEAD', 'OPTIONS'].includes(method)) && readCookieCsrf()) {
        headers.set('X-CSRF-Token', readCookieCsrf());
      }
      const response = await fetch(path, {
        ...options,
        headers,
        credentials: 'same-origin'
      });
      if (!response.ok) {
        let message = `${response.status} ${response.statusText}`;
        try {
          const body = await response.json();
          message = body.detail || body.message || message;
        } catch (_) {
          // Keep HTTP status when no body.
        }
        const error = new Error(message);
        error.status = response.status;
        throw error;
      }
      if (response.status === 204) return null;
      const text = await response.text();
      return text ? JSON.parse(text) : null;
    },

    async login(body) {
      if (!api) return this.request('/api/auth/login', { method: 'POST', body });
      return api.auth.login(api.client, body);
    },
    async register(body) {
      if (!api) return this.request('/api/auth/register', { method: 'POST', body });
      return api.auth.register(api.client, body);
    },
    async logout() {
      if (!api) {
        await this.request('/api/auth/logout', { method: 'POST' });
        return;
      }
      return api.auth.logout(api.client);
    },
    async currentSession() {
      if (!api) {
        const result = await this.request('/api/auth/session', {});
        if (result) {
          document.cookie = `finsight_csrf=${encodeURIComponent(result.csrfToken || '')}; path=/`;
        }
        return result;
      }
      return api.auth.currentSession(api.client);
    },
    async listWatchlist() {
      if (!api) return this.request('/api/watchlist', {});
      return api.watchlist.listWatchlist(api.client);
    },
    async addToWatchlist(symbol) {
      if (!api) {
        return this.request(
          `/api/watchlist/${encodeURIComponent(symbol)}`,
          { method: 'POST' }
        );
      }
      return api.watchlist.addToWatchlist(api.client, symbol);
    },
    async removeFromWatchlist(symbol) {
      if (!api) {
        await this.request(
          `/api/watchlist/${encodeURIComponent(symbol)}`,
          { method: 'DELETE' }
        );
        return;
      }
      return api.watchlist.removeFromWatchlist(api.client, symbol);
    },
    async quote(symbol) {
      if (!api) return this.request(`/api/market/quotes/${symbol}`, {});
      return api.research.quote(api.client, symbol);
    },
    async history(symbol, limit) {
      if (!api) {
        return this.request(
          `/api/market/history/${symbol}?limit=${limit}`,
          {}
        );
      }
      return api.market.marketHistory(api.client, symbol, limit);
    },
    async listCompanies(limit) {
      if (!api) return this.request(`/api/companies?limit=${limit}`, {});
      return api.companies.listCompanies(api.client, limit);
    },
    async searchCompanies(query, limit) {
      if (!api) {
        return this.request(
          `/api/companies/search?q=${encodeURIComponent(query)}&limit=${limit}`,
          {}
        );
      }
      return api.companies.searchCompanies(api.client, query, limit);
    },
    async latestAnalysis(symbol) {
      if (!api) {
        return this.request(
          `/api/companies/${symbol}/ai-analysis/latest`,
          {}
        );
      }
      return api.research.latestAnalysis(api.client, symbol);
    },
    async latestReportTrace(symbol) {
      if (!api) {
        return this.request(
          `/api/research/reports/${symbol}/latest`,
          {}
        );
      }
      return api.research.latestAnalysis(api.client, symbol);
    },
    async dailyRecommendations() {
      if (!api) return this.request('/api/market/recommendations', {});
      return api.market.dailyRecommendations(api.client);
    },
    async metrics(symbol) {
      if (!api) return this.request(`/api/metrics/${symbol}`, {});
      return api.intelligence.companyMetrics(api.client, symbol);
    },
    async riskSignals(symbol) {
      if (!api) return this.request(`/api/metrics/${symbol}/risks`, {});
      return api.intelligence.companyRiskSignals(api.client, symbol);
    },
    async timeline(symbol) {
      if (!api) return this.request(`/api/intelligence/${symbol}/timeline`, {});
      return api.intelligence.companyTimeline(api.client, symbol);
    },
    async analyzeStock(symbol) {
      if (!api) {
        return this.request(
          `/api/research/stock/${symbol}/analyze`,
          { method: 'POST', body: {} }
        );
      }
      return api.research.analyzeStock(api.client, symbol);
    },
    async issueVerificationCode(email) {
      if (!api) {
        return this.request('/api/auth/verification-code', {
          method: 'POST',
          body: { email }
        });
      }
      return api.auth.issueVerificationCode(api.client, email);
    },
    async requestPasswordReset(email) {
      if (!api) {
        return this.request('/api/auth/password-reset/request', {
          method: 'POST',
          body: { email }
        });
      }
      return api.companies.requestPasswordReset(api.client, email);
    },
    async confirmPasswordReset(token, password) {
      if (!api) {
        return this.request('/api/auth/password-reset/confirm', {
          method: 'POST',
          body: { token, password }
        });
      }
      return api.companies.confirmPasswordReset(api.client, token, password);
    },
    async createResearchTask(symbol) {
      if (!api) {
        return this.request('/api/research/tasks', {
          method: 'POST',
          body: { symbol }
        });
      }
      return api.workflow.createResearchTask(api.client, symbol);
    },
    async getResearchTaskProgress(taskId) {
      if (!api) {
        return this.request(
          `/api/research/tasks/${taskId}/progress`,
          {}
        );
      }
      return api.workflow.getResearchTaskProgress(api.client, taskId);
    },
    async searchDocumentIndex(query, symbol, limit) {
      if (!api) {
        const path = symbol
          ? `/api/document-index/${symbol}/search?q=${encodeURIComponent(query)}&limit=${limit}`
          : `/api/document-index/search?q=${encodeURIComponent(query)}&limit=${limit}`;
        return this.request(path, {});
      }
      return api.intelligence.searchDocumentIndex(api.client, {
        query,
        symbol,
        limit
      });
    }
  };
})();
