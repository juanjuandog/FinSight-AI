let symbol = "600900";
let companies = [];
let suggestionTimer = null;
let chartLimit = 120;
let latestQuote = null;
let latestCandles = [];
let latestHistory = null;
let latestAiAnalysis = null;
let latestMetrics = [];
let latestRisks = [];
let latestEvents = [];
let latestWatchlist = [];
let latestReports = [];
let reportDiffGeneration = 0;
let eventsLoadError = false;
let watchlistLoadError = false;
let evidenceScope = "company";
let refreshGeneration = 0;
let chartGeneration = 0;
let loadedSymbol = null;
let workflowGeneration = 0;
let authUser = null;
let csrfToken = "";
let authView = "login";
let pendingAuthAction = null;
let resetToken = "";
let verificationCountdownTimer = null;
let dailyRecommendationRetryTimer = null;

const $ = (id) => document.getElementById(id);

function cssToken(name, fallback) {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || fallback;
}

async function request(path, options = {}) {
  const method = String(options.method || "GET").toUpperCase();
  const headers = new Headers(options.headers || {});
  if (!(["GET", "HEAD", "OPTIONS"].includes(method)) && csrfToken) {
    headers.set("X-CSRF-Token", csrfToken);
  }
  const response = await fetch(path, { ...options, headers, credentials: "same-origin" });
  if (!response.ok) {
    let message = `${response.status} ${response.statusText}`;
    try {
      const body = await response.json();
      message = body.detail || body.message || message;
    } catch {
      // Keep the HTTP status when the server did not return JSON.
    }
    const error = new Error(message);
    error.status = response.status;
    throw error;
  }
  if (response.status === 204) {
    return null;
  }
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

async function loadSession() {
  const response = await fetch("/api/auth/session", { credentials: "same-origin" });
  if (!response.ok) {
    throw new Error("账户状态暂时无法读取");
  }
  const session = await response.json();
  csrfToken = session.csrfToken || "";
  authUser = session.user || null;
  renderAccountState();
  setAuthGateRequired(!authUser);
}

function setAuthGateRequired(required) {
  document.body.classList.toggle("auth-required", required);
  if (required) {
    document.body.classList.add("auth-open");
    $("authDialog").hidden = false;
  } else {
    document.body.classList.remove("auth-open");
    $("authDialog").hidden = true;
  }
}

function renderAccountState() {
  const button = $("accountButton");
  const label = $("accountStatusLabel");
  const detail = $("accountStatusDetail");
  const logout = $("logoutButton");
  if (!button || !label || !detail || !logout) return;
  if (authUser) {
    label.textContent = authUser.email;
    detail.textContent = "已登录";
    logout.hidden = false;
    button.setAttribute("aria-label", "打开个人工作区");
  } else {
    label.textContent = "登录以保存研究";
    detail.textContent = "关注列表与研究记录仅对你可见";
    logout.hidden = true;
    button.setAttribute("aria-label", "登录以保存研究");
  }
  renderWatchlist();
}

function openAuthDialog(view = "login", action = null) {
  authView = view;
  pendingAuthAction = action;
  $("authDialog").hidden = false;
  document.body.classList.add("auth-open");
  renderAuthView();
  window.setTimeout(() => $("authEmail")?.focus(), 0);
}

function closeAuthDialog() {
  if (!authUser) return;
  $("authDialog").hidden = true;
  document.body.classList.remove("auth-open");
  pendingAuthAction = null;
  $("authFeedback").textContent = "";
  $("authForm").reset();
  $("resetRequestForm").reset();
  $("resetConfirmForm").reset();
  $("authVerificationCode").value = "";
  $("authVerificationHint").textContent = "请先获取邮箱验证码。";
  resetPasswordVisibility();
  if (window.location.search) {
    window.history.replaceState({}, "", window.location.pathname + window.location.hash);
  }
}

function renderAuthView() {
  const login = authView === "login";
  const register = authView === "register";
  const resetRequest = authView === "reset-request";
  const resetConfirm = authView === "reset-confirm";
  $("authTitle").textContent = login ? "登录 FinSight AI" : register ? "注册 FinSight AI" : resetConfirm ? "设置新密码" : "重置你的密码";
  $("authIntro").textContent = login
    ? "登录后开始你的股票研究。"
    : register
      ? "创建账号后保存你的研究。"
      : "";
  document.querySelectorAll(".auth-tab").forEach(tab => {
    const active = tab.dataset.authView === authView;
    tab.classList.toggle("active", active);
    tab.setAttribute("aria-selected", String(active));
    tab.hidden = resetRequest || resetConfirm;
  });
  $("authForm").hidden = !login && !register;
  $("resetRequestForm").hidden = !resetRequest;
  $("resetConfirmForm").hidden = !resetConfirm;
  $("forgotPasswordButton").hidden = !login;
  $("authPasswordConfirmLabel").hidden = !register;
  $("authPasswordConfirmField").hidden = !register;
  $("authPasswordConfirm").required = register;
  $("authVerificationFields").hidden = !register;
  $("authVerificationCode").required = register;
  $("authPassword").autocomplete = register ? "new-password" : "current-password";
  $("authSubmitButton").textContent = register ? "创建账号" : "登录";
  $("authFeedback").textContent = "";
  resetPasswordVisibility();
}

function setAuthFeedback(message) {
  $("authFeedback").textContent = message || "";
}

function resetPasswordVisibility() {
  document.querySelectorAll(".password-toggle").forEach(button => {
    const input = $(button.dataset.passwordTarget);
    if (input) input.type = "password";
    button.setAttribute("aria-pressed", "false");
    button.setAttribute("aria-label", button.dataset.passwordTarget?.includes("Confirm") ? "显示确认密码" : "显示密码");
  });
}

document.querySelectorAll(".password-toggle").forEach(button => {
  button.addEventListener("click", () => {
    const input = $(button.dataset.passwordTarget);
    if (!input) return;
    const visible = input.type === "password";
    input.type = visible ? "text" : "password";
    button.setAttribute("aria-pressed", String(visible));
    button.setAttribute("aria-label", visible ? "隐藏密码" : "显示密码");
  });
});

async function refresh() {
  const requestGeneration = ++refreshGeneration;
  const requestedSymbol = symbol;
  const requestedChartGeneration = ++chartGeneration;
  const isCurrent = () => requestGeneration === refreshGeneration && requestedSymbol === symbol;

  if (loadedSymbol !== requestedSymbol) {
    loadedSymbol = requestedSymbol;
    latestQuote = null;
    latestCandles = [];
    latestHistory = null;
    latestAiAnalysis = null;
    latestMetrics = [];
    latestRisks = [];
    latestReports = [];
    resetReportArchive();
    updateCompanyCard();
    renderAnalysis([], [], null, null);
    renderChart([]);
    renderChartStats([], null);
    renderChartStatus(null);
  }

  const companiesRequest = request("/api/companies?limit=200")
    .catch(() => companies)
    .then(nextCompanies => {
      if (!isCurrent()) return;
      companies = Array.isArray(nextCompanies) ? nextCompanies : companies;
      updateCompanyCard(latestQuote);
    });

  request(`/api/market/quotes/${requestedSymbol}`).catch(error => ({
      symbol: requestedSymbol,
      name: `股票 ${requestedSymbol}`,
      exchange: "CN",
      realtime: false,
      source: "LOCAL_ERROR",
      message: error.message
    })).then(quote => {
      if (!isCurrent()) return;
      latestQuote = quote;
      updateCompanyCard(quote);
      renderUniverseStatus();
      renderAnalysis(latestMetrics, latestRisks, quote, latestAiAnalysis);
      renderChartStats(latestCandles, quote);
    });

  request(`/api/companies/${requestedSymbol}/ai-analysis/latest`)
    .catch(() => null)
    .then(aiAnalysis => {
      if (!isCurrent()) return;
      latestAiAnalysis = aiAnalysis;
      renderAnalysis(latestMetrics, latestRisks, latestQuote, aiAnalysis);
      renderUniverseStatus();
    });

  request(`/api/market/history/${requestedSymbol}?limit=${chartLimit}`)
    .catch(error => unavailableHistory(error))
    .then(history => {
      if (!isCurrent() || requestedChartGeneration !== chartGeneration) return;
      applyHistoryResponse(history);
    });

  request(`/api/metrics/${requestedSymbol}`)
    .catch(() => [])
    .then(metrics => {
      if (!isCurrent()) return;
      latestMetrics = Array.isArray(metrics) ? metrics : [];
      renderAnalysis(latestMetrics, latestRisks, latestQuote, latestAiAnalysis);
    });

  request(`/api/metrics/${requestedSymbol}/risks`)
    .catch(() => [])
    .then(risks => {
      if (!isCurrent()) return;
      latestRisks = Array.isArray(risks) ? risks : [];
      renderAnalysis(latestMetrics, latestRisks, latestQuote, latestAiAnalysis);
    });

  refreshReportHistory(requestedSymbol, isCurrent);

  request(`/api/intelligence/${requestedSymbol}/timeline`)
    .then(data => ({ ok: true, data }))
    .catch(error => ({ ok: false, error }))
    .then(result => {
      if (!isCurrent()) return;
      eventsLoadError = !result.ok;
      latestEvents = result.ok && Array.isArray(result.data) ? result.data : [];
      renderEvents();
    });

  if (authUser) {
    request("/api/watchlist")
      .then(data => ({ ok: true, data }))
      .catch(error => ({ ok: false, error }))
      .then(result => {
        if (!isCurrent()) return;
        watchlistLoadError = !result.ok;
        latestWatchlist = result.ok && Array.isArray(result.data) ? result.data : [];
        renderWatchlist();
      });
  } else {
    watchlistLoadError = false;
    latestWatchlist = [];
    renderWatchlist();
  }

  await companiesRequest;
}

function updateCompanyCard(quote = null) {
  const company = companies.find(item => item.symbol === symbol);
  const name = quote?.name || company?.name || `股票 ${symbol}`;
  const exchange = quote?.exchange || company?.exchange || "CN";
  $("companyName").textContent = name;
  $("companyMeta").textContent = `${symbol}.${exchange}`;
  $("evidenceCompanyName").textContent = name;
  $("evidenceCompanySymbol").textContent = `${symbol}.${exchange}`;
  $("eventCompanyName").textContent = name;
  $("eventCompanySymbol").textContent = `${symbol}.${exchange}`;
  renderQuote(quote);
}

function renderUniverseStatus(message = "") {
  const updatedAt = latestQuote?.tradeTime || formatDateTime(latestAiAnalysis?.generatedAt) || "待同步";
  $("universeStatus").textContent = message || `更新时间 ${updatedAt}`;
}

function renderWatchlist() {
  if (!authUser) {
    $("stockList").innerHTML = `
      <div class="empty-state">
        <strong>登录后使用个人关注列表</strong>
        <p>登录后，你的关注公司会在不同设备间保持同步。</p>
        <button class="text-action" type="button" data-open-auth>登录 / 注册</button>
      </div>
    `;
    $("poolCount").textContent = "需要登录";
    $("addCurrentToWatchlist").textContent = "登录后加入";
    return;
  }
  if (watchlistLoadError) {
    $("stockList").innerHTML = `
      <div class="empty-state error-state">
        <strong>关注列表暂时无法加载</strong>
        <p>服务恢复后会自动显示原有关注公司。</p>
      </div>
    `;
    $("poolCount").textContent = "同步失败";
    return;
  }

  const rows = latestWatchlist.map(entry => {
    const company = entry.company || entry;
    const companySymbol = String(company.symbol || "");
    const exchange = company.exchange || "CN";
    const industry = company.industry || "待分类";
    const companyName = company.name || `股票 ${companySymbol}`;
    return `
      <article class="stock-row ${companySymbol === symbol ? "active" : ""}">
        <button class="stock-main" type="button" data-symbol="${escapeHtml(companySymbol)}">
          <strong>${escapeHtml(companyName)}</strong>
          <span>${escapeHtml(companySymbol)}.${escapeHtml(exchange)}&nbsp;&nbsp;${escapeHtml(industry)}</span>
        </button>
        <div class="stock-row-meta">
          <time>${escapeHtml(formatDateTime(entry.createdAt) || "已关注")}</time>
          <button class="stock-remove" type="button" data-symbol="${escapeHtml(companySymbol)}" aria-label="从关注列表移除${escapeHtml(companyName)}">移除</button>
        </div>
      </article>
    `;
  }).join("");

  $("stockList").innerHTML = rows || `
    <div class="empty-state">
      <strong>暂无关注公司</strong>
      <p>点击“加入当前公司”，把正在研究的股票放到这里。</p>
    </div>
  `;
  $("poolCount").textContent = `${latestWatchlist.length} 只`;

  const addButton = $("addCurrentToWatchlist");
  const alreadyFollowing = latestWatchlist.some(entry => (entry.company || entry).symbol === symbol);
  addButton.disabled = alreadyFollowing;
  addButton.textContent = alreadyFollowing ? "已在关注列表" : "加入当前公司";

  document.querySelectorAll(".stock-main").forEach(button => {
    button.addEventListener("click", async () => {
      await selectSymbol(button.dataset.symbol);
      setWorkspace("company");
    });
  });
  document.querySelectorAll(".stock-remove").forEach(button => {
    button.addEventListener("click", () => removeFromWatchlist(button.dataset.symbol));
  });
}

async function addCurrentCompanyToWatchlist() {
  if (!authUser) {
    openAuthDialog("login", addCurrentCompanyToWatchlist);
    return;
  }
  const button = $("addCurrentToWatchlist");
  button.disabled = true;
  $("watchlistActionStatus").textContent = "正在加入…";
  try {
    latestWatchlist = await request(`/api/watchlist/${encodeURIComponent(symbol)}`, { method: "POST" });
    watchlistLoadError = false;
    $("watchlistActionStatus").textContent = "已加入当前公司";
    renderWatchlist();
  } catch (error) {
    button.disabled = false;
    $("watchlistActionStatus").textContent = `加入失败：${error.message}`;
  }
}

async function removeFromWatchlist(companySymbol) {
  $("watchlistActionStatus").textContent = "正在移除…";
  try {
    latestWatchlist = await request(`/api/watchlist/${encodeURIComponent(companySymbol)}`, { method: "DELETE" });
    watchlistLoadError = false;
    $("watchlistActionStatus").textContent = "已从关注列表移除";
    renderWatchlist();
  } catch (error) {
    $("watchlistActionStatus").textContent = `移除失败：${error.message}`;
  }
}

function renderEvents() {
  if (eventsLoadError) {
    $("eventList").innerHTML = `
      <div class="empty-state error-state">
        <strong>近期事件暂时无法加载</strong>
        <p>当前没有使用旧数据替代，请稍后再试。</p>
      </div>
    `;
    return;
  }

  const rows = latestEvents.slice(0, 30).map(event => {
    const type = eventTypeName(event.type);
    return `
      <article class="event-row">
        <time>${escapeHtml(formatEventDate(event.happenedAt) || "日期待同步")}</time>
        <strong>${escapeHtml(event.title || "未命名事件")}</strong>
        <span class="event-type ${eventTypeClass(event.type)}">${escapeHtml(type)}</span>
        <p>${escapeHtml(event.summary || "暂无摘要")}</p>
      </article>
    `;
  }).join("");
  $("eventList").innerHTML = rows || `
    <div class="empty-state">
      <strong>暂无近期事件</strong>
      <p>生成分析后，系统会根据公开披露、指标变化和风险信号建立公司时间线。</p>
    </div>
  `;
}

function eventTypeName(type) {
  return ({
    FINANCIAL_RESULT: "财务",
    RISK_SIGNAL: "风险",
    MANAGEMENT_DISCUSSION: "管理层",
    INDUSTRY_CHANGE: "公告",
    RESEARCH_VIEW: "研究"
  })[type] || "事件";
}

function eventTypeClass(type) {
  if (type === "RISK_SIGNAL") {
    return "risk";
  }
  if (type === "FINANCIAL_RESULT") {
    return "financial";
  }
  return "neutral";
}

function renderQuote(quote) {
  if (!quote) {
    $("marketQuote").innerHTML = "<span>行情加载中</span>";
    $("marketStatus").textContent = "市场状态 待连接";
    $("marketStatus").className = "market-status";
    return;
  }
  const price = numeric(quote.currentPrice);
  const change = numeric(quote.change);
  const changePercent = numeric(quote.changePercent);
  const direction = changePercent >= 0 ? "up" : "down";
  const latestCandle = Array.isArray(latestCandles) ? latestCandles[latestCandles.length - 1] : null;
  const amount = numeric(latestCandle?.amount);
  $("marketStatus").textContent = quote.realtime ? "市场状态 实时接入" : "市场状态 降级数据";
  $("marketStatus").className = `market-status ${quote.realtime ? "live" : "fallback"}`;
  $("marketQuote").innerHTML = `
    <section class="summary-price">
      <strong class="${direction}">${price > 0 ? price.toFixed(2) : "--"}</strong>
      <em class="${direction}">${formatSigned(change)} / ${formatSigned(changePercent)}%</em>
    </section>
    <section class="summary-facts">
      <article><span>今开</span><strong>${formatNumber(quote.openPrice)}</strong></article>
      <article><span>最高</span><strong>${formatNumber(quote.highPrice)}</strong></article>
      <article><span>最低</span><strong>${formatNumber(quote.lowPrice)}</strong></article>
      <article><span>成交额</span><strong>${formatMoney(amount)}</strong></article>
      <article><span>数据源</span><strong>${escapeHtml(marketSourceName(quote.source))}</strong></article>
    </section>
  `;
}

function renderAnalysis(metrics, risks, quote, aiAnalysis = null) {
  const checks = metrics.length ? healthChecks(metrics) : [];
  if (!metrics.length) {
    const guidance = analysisGuidance(aiAnalysis, "等待确认", [], []);
    const displayRating = aiAnalysis ? guidance.researchPriority : "等待分析";
    $("ratingBadge").textContent = displayRating;
    $("ratingBadge").className = `rating ${ratingClass(displayRating)}`;
    $("analysisConclusion").textContent = aiAnalysis?.summary || "点击“生成分析”后，这里会给出研究优先级、确认条件、失效信号和下一步动作。";
    renderGuidancePanels(guidance, aiAnalysis?.positivePoints, aiAnalysis?.riskPoints);
    $("confidenceScore").textContent = aiAnalysis?.confidence != null ? `${aiAnalysis.confidence}%` : "置信度待生成";
    $("confidenceScore").classList.toggle("pending", aiAnalysis?.confidence == null);
    $("confidenceScore").hidden = aiAnalysis?.confidence == null;
    $("analysisUpdatedAt").textContent = aiAnalysis ? analysisMeta(aiAnalysis) : "暂无数据";
    $("analysisUpdatedAt").hidden = !aiAnalysis;
    $("healthList").innerHTML = emptyHealth();
    return;
  }

  const warningCount = checks.filter(check => check.level !== "good").length + risks.length;
  const quoteWeak = Number(quote?.changePercent || 0) < -1;
  const rating = warningCount >= 4 || quoteWeak ? "暂不进入候选" : warningCount >= 2 ? "等待确认" : "优先研究";
  const confidence = Math.max(68, Math.min(92, 86 - warningCount * 4 + (quote?.realtime ? 4 : 0)));
  const company = quote?.name || companies.find(item => item.symbol === symbol)?.name || `股票 ${symbol}`;
  const guidance = analysisGuidance(aiAnalysis, rating, positiveText(checks), negativeText(checks, risks, quote));
  const displayRating = guidance.researchPriority;
  const displayConfidence = aiAnalysis?.confidence ?? confidence;

  $("ratingBadge").textContent = displayRating;
  $("ratingBadge").className = `rating ${ratingClass(displayRating)}`;
  $("analysisConclusion").textContent = aiAnalysis?.guidance?.summary || aiAnalysis?.summary || conclusionText(company, rating, checks, risks, quote);
  renderGuidancePanels(guidance, aiAnalysis?.positivePoints || positiveText(checks), aiAnalysis?.riskPoints || negativeText(checks, risks, quote));
  $("confidenceScore").textContent = `${displayConfidence}%`;
  $("confidenceScore").classList.remove("pending");
  $("confidenceScore").hidden = false;
  $("analysisUpdatedAt").textContent = aiAnalysis ? analysisMeta(aiAnalysis) : quote?.tradeDate && quote?.tradeTime ? `${quote.tradeDate} ${quote.tradeTime}` : "基于当前数据";
  $("analysisUpdatedAt").hidden = false;
  $("healthList").innerHTML = checks.map(healthCard).join("");
}

function resetReportArchive() {
  reportDiffGeneration += 1;
  $("comparePreviousToggle").checked = false;
  $("comparePreviousToggle").disabled = true;
  $("reportHistoryStatus").hidden = false;
  $("reportHistoryStatus").textContent = "正在读取版本记录…";
  $("reportHistoryList").innerHTML = "";
  $("reportDiffPanel").hidden = true;
  $("reportDiffFields").innerHTML = "";
}

async function refreshReportHistory(requestedSymbol = symbol, isCurrent = () => requestedSymbol === symbol) {
  try {
    const reports = await request(`/api/research/stock/${encodeURIComponent(requestedSymbol)}/reports?limit=8`);
    if (!isCurrent()) return;
    latestReports = Array.isArray(reports) ? reports : [];
    renderReportHistory(requestedSymbol);
  } catch (error) {
    if (!isCurrent()) return;
    latestReports = [];
    closeReportComparison();
    $("comparePreviousToggle").disabled = true;
    $("reportHistoryList").innerHTML = "";
    $("reportHistoryStatus").hidden = false;
    $("reportHistoryStatus").textContent = `版本记录暂时无法读取：${error.message}`;
  }
}

function renderReportHistory(requestedSymbol) {
  const historyStatus = $("reportHistoryStatus");
  const compareToggle = $("comparePreviousToggle");
  compareToggle.disabled = latestReports.length < 2;
  if (latestReports.length < 2 && compareToggle.checked) {
    compareToggle.checked = false;
    closeReportComparison();
  }
  if (!latestReports.length) {
    historyStatus.hidden = false;
    historyStatus.textContent = "还没有可归档的报告。生成两版分析后，即可查看结论变化。";
    $("reportHistoryList").innerHTML = "";
    return;
  }
  historyStatus.hidden = false;
  historyStatus.textContent = latestReports.length > 1
    ? `已保存 ${latestReports.length} 个版本，最新两版可直接对照。`
    : "已保存首个版本；再生成一版即可开启对比。";
  $("reportHistoryList").innerHTML = latestReports.map((report, index) => {
    const version = Number(report.reportVersion || latestReports.length - index);
    const safeReportId = encodeURIComponent(report.id || "");
    const safeSymbol = encodeURIComponent(requestedSymbol);
    const summary = String(report.summary || "暂无结论摘要").trim();
    const meta = [
      formatDateTime(report.generatedAt) || "时间待记录",
      report.model || report.source || "规则报告",
      report.dataSnapshotHash ? `快照 ${String(report.dataSnapshotHash).slice(0, 10)}` : "快照待记录"
    ].join(" · ");
    return `
      <li class="report-history-row">
        <div class="report-version">V${escapeHtml(version)}${index === 0 ? "<small>最新</small>" : ""}</div>
        <div class="report-history-copy">
          <strong>${escapeHtml(report.rating || "等待确认")} · ${escapeHtml(summary)}</strong>
          <p title="${escapeHtml(meta)}">${escapeHtml(meta)}</p>
        </div>
        <div class="report-export-actions" aria-label="导出 V${escapeHtml(version)}">
          <a href="/api/research/stock/${safeSymbol}/reports/${safeReportId}.md" download>Markdown</a>
          <a href="/api/research/stock/${safeSymbol}/reports/${safeReportId}.pdf" download>PDF</a>
        </div>
      </li>
    `;
  }).join("");
  if (compareToggle.checked) {
    openLatestReportComparison();
  }
}

async function openLatestReportComparison() {
  if (latestReports.length < 2) return;
  const generation = ++reportDiffGeneration;
  const [to, from] = latestReports;
  const panel = $("reportDiffPanel");
  panel.hidden = false;
  $("reportDiffMeta").textContent = "正在读取差异…";
  $("reportDiffFields").innerHTML = '<p class="report-history-status">正在计算字段级变化…</p>';
  try {
    const diff = await request(
      `/api/research/stock/${encodeURIComponent(symbol)}/reports/${encodeURIComponent(from.id)}/diff/${encodeURIComponent(to.id)}`
    );
    if (generation !== reportDiffGeneration || !$("comparePreviousToggle").checked) return;
    renderReportDiff(diff);
  } catch (error) {
    if (generation !== reportDiffGeneration) return;
    $("reportDiffMeta").textContent = "对比暂不可用";
    $("reportDiffFields").innerHTML = `<p class="report-history-status">${escapeHtml(error.message)}</p>`;
  }
}

function renderReportDiff(diff) {
  const from = diff?.from || {};
  const to = diff?.to || {};
  $("reportDiffHeading").textContent = `V${Number(to.reportVersion || 0)} 与 V${Number(from.reportVersion || 0)}`;
  const flags = [
    diff?.dataSnapshotHashChanged ? "数据快照已变化" : "数据快照一致",
    diff?.contextHashChanged ? "研究上下文已变化" : "研究上下文一致"
  ];
  $("reportDiffMeta").textContent = `${formatDateTime(from.generatedAt) || "上一版"} → ${formatDateTime(to.generatedAt) || "最新版"} · ${flags.join(" · ")}`;
  const fields = [
    ["评级", diff?.rating],
    ["结论摘要", diff?.summary],
    ["支持依据", diff?.positivePoints],
    ["风险与失效信号", diff?.riskPoints],
    ["引用证据", diff?.citations]
  ];
  $("reportDiffFields").innerHTML = fields.map(([label, field]) => reportDiffField(label, field, from, to)).join("");
}

function reportDiffField(label, field = {}, from = {}, to = {}) {
  const changed = Boolean(field.changed);
  return `
    <article class="report-diff-field ${changed ? "changed" : ""}">
      <header>
        <h3>${escapeHtml(label)}</h3>
        <span class="report-diff-state ${changed ? "changed" : ""}">${changed ? "已变化" : "未变化"}</span>
      </header>
      <div class="report-diff-columns">
        <section class="report-diff-side">
          <span>V${escapeHtml(Number(from.reportVersion || 0))} · 之前</span>
          ${reportDiffValue(field.before)}
        </section>
        <section class="report-diff-side">
          <span>V${escapeHtml(Number(to.reportVersion || 0))} · 现在</span>
          ${reportDiffValue(field.after)}
        </section>
      </div>
    </article>
  `;
}

function reportDiffValue(values) {
  const rows = Array.isArray(values) ? values.filter(value => String(value || "").trim()) : [];
  if (!rows.length) return "<p>暂无内容</p>";
  if (rows.length === 1) return `<p>${escapeHtml(rows[0])}</p>`;
  return `<ul>${rows.map(value => `<li>${escapeHtml(value)}</li>`).join("")}</ul>`;
}

function closeReportComparison() {
  reportDiffGeneration += 1;
  $("comparePreviousToggle").checked = false;
  $("reportDiffPanel").hidden = true;
  $("reportDiffFields").innerHTML = "";
}

function analysisGuidance(aiAnalysis, fallbackPriority, supporting = [], risks = []) {
  const guidance = aiAnalysis?.guidance;
  if (guidance?.researchPriority) return guidance;
  return {
    researchPriority: fallbackPriority,
    dataCompleteness: 0,
    supportingEvidence: supporting,
    confirmationConditions: ["补齐财务、公告与证据来源后再完成判断。"],
    invalidationSignals: risks,
    nextResearchActions: ["查看证据来源并重新生成分析。"]
  };
}

function renderGuidancePanels(guidance, fallbackSupporting = [], fallbackRisks = []) {
  $("positivePoints").innerHTML = decisionList(guidance?.supportingEvidence || fallbackSupporting, "暂无支持依据。");
  $("confirmationConditions").innerHTML = decisionList(guidance?.confirmationConditions, "暂无待确认条件。");
  $("negativePoints").innerHTML = decisionList(guidance?.invalidationSignals || fallbackRisks, "暂无失效信号。");
  $("nextResearchActions").innerHTML = decisionList(guidance?.nextResearchActions, "暂无下一步动作。");
  const completeness = Number(guidance?.dataCompleteness);
  $("dataCompleteness").textContent = Number.isFinite(completeness) && completeness > 0 ? `数据完整度 ${completeness}%` : "待补充数据";
  $("dataCompleteness").hidden = false;
}

function normalizeHistoryResponse(response) {
  if (Array.isArray(response)) {
    return {
      candles: response,
      source: response.length ? "EASTMONEY_HISTORY" : "UNAVAILABLE",
      fetchedAt: null,
      simulated: false,
      available: response.length > 0,
      error: response.length ? null : "历史行情暂不可用"
    };
  }
  const value = response && typeof response === "object" ? response : {};
  const candles = Array.isArray(value.candles) ? value.candles : [];
  return {
    ...value,
    candles,
    simulated: Boolean(value.simulated),
    available: value.available === true && candles.length > 0
  };
}

function unavailableHistory() {
  return {
    candles: [],
    source: "UNAVAILABLE",
    fetchedAt: new Date().toISOString(),
    simulated: false,
    available: false,
    error: "历史行情暂不可用"
  };
}

function applyHistoryResponse(response) {
  const history = normalizeHistoryResponse(response);
  latestHistory = history;
  latestCandles = history.candles;
  const emptyMessage = history.available ? null : "历史行情暂不可用，未展示模拟 K 线。";
  renderChart(latestCandles, emptyMessage);
  renderChartStats(latestCandles, latestQuote, history);
  renderChartStatus(history);
}

function renderChartStatus(history) {
  const source = $("chartSource");
  const retry = $("retryChart");
  if (!source || !retry) return;
  if (!history) {
    source.textContent = "历史行情加载中…";
    retry.hidden = true;
    return;
  }
  const fetchedAt = formatDateTime(history.fetchedAt);
  if (!history.available) {
    source.textContent = history.error || "历史行情暂不可用，未展示模拟 K 线。";
    retry.hidden = false;
    return;
  }
  const sourceLabel = history.simulated ? "演示数据（模拟）" : marketSourceName(history.source);
  source.textContent = fetchedAt ? sourceLabel + " · 抓取于 " + fetchedAt : sourceLabel;
  retry.hidden = true;
}

function renderChart(candles, emptyMessage = null) {
  const canvas = $("priceChart");
  const tooltip = $("chartTooltip");
  const ctx = canvas.getContext("2d");
  const rect = canvas.getBoundingClientRect();
  const dpr = window.devicePixelRatio || 1;
  const width = Math.max(320, Math.floor(rect.width));
  const height = width < 640 ? 280 : 360;
  canvas.width = Math.floor(width * dpr);
  canvas.height = Math.floor(height * dpr);
  canvas.style.height = `${height}px`;
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, width, height);
  tooltip.hidden = true;
  canvas.onmousemove = null;
  canvas.onmouseleave = null;

  if (!Array.isArray(candles) || candles.length < 2) {
    drawEmptyChart(ctx, width, height, emptyMessage || "暂无历史行情数据，等待行情源返回 K 线。");
    return;
  }

  const data = candles.map(candle => ({
    date: candle.tradeDate,
    close: numeric(candle.close),
    changePercent: numeric(candle.changePercent)
  })).filter(candle => candle.close > 0);

  if (data.length < 2) {
    drawEmptyChart(ctx, width, height, emptyMessage || "暂无历史行情数据，等待行情源返回 K 线。");
    return;
  }

  const closes = data.map(candle => candle.close);
  const rawMin = Math.min(...closes);
  const rawMax = Math.max(...closes);
  const pricePadding = Math.max((rawMax - rawMin) * 0.08, rawMax * 0.006, 1);
  const minPrice = rawMin - pricePadding;
  const maxPrice = rawMax + pricePadding;
  const pad = { left: 16, right: 64, top: 24, bottom: 38 };
  const priceBottom = height - pad.bottom;
  const priceHeight = priceBottom - pad.top;
  const span = Math.max(maxPrice - minPrice, 1);
  const xStep = (width - pad.left - pad.right) / Math.max(data.length - 1, 1);
  const x = index => pad.left + index * xStep;
  const y = value => pad.top + (maxPrice - value) / span * priceHeight;

  const drawBaseChart = () => {
    ctx.clearRect(0, 0, width, height);
    drawGrid(ctx, width, pad, priceBottom, minPrice, maxPrice);
    drawLine(ctx, closes, x, y, cssToken("--chart-line", "#A48754"), 2.2);
  };

  drawBaseChart();

  canvas.onmousemove = event => {
    const bounds = canvas.getBoundingClientRect();
    const mouseX = event.clientX - bounds.left;
    const index = Math.min(data.length - 1, Math.max(0, Math.round((mouseX - pad.left) / xStep)));
    const candle = data[index];
    const cx = x(index);
    drawBaseChart();
    drawCrosshair(ctx, cx, pad.top, height - 32);
    tooltip.hidden = false;
    tooltip.style.left = `${Math.min(width - 210, Math.max(10, cx + 12))}px`;
    tooltip.style.top = `${Math.max(10, y(candle.close) - 28)}px`;
    tooltip.innerHTML = `
      <strong>${escapeHtml(candle.date)}</strong>
      <div>收盘：${formatNumber(candle.close)}</div>
      <div>涨跌：<span class="${candle.changePercent >= 0 ? "up" : "down"}">${formatSigned(candle.changePercent)}%</span></div>
    `;
  };
  canvas.onmouseleave = () => {
    tooltip.hidden = true;
    drawBaseChart();
  };
}

function drawEmptyChart(ctx, width, height, message) {
  ctx.clearRect(0, 0, width, height);
  ctx.strokeStyle = cssToken("--chart-grid", "rgba(117, 107, 88, .16)");
  ctx.lineWidth = 1;
  for (let i = 0; i < 6; i++) {
    const y = 36 + i * ((height - 92) / 5);
    ctx.beginPath();
    ctx.moveTo(48, y);
    ctx.lineTo(width - 48, y);
    ctx.stroke();
  }
  ctx.fillStyle = cssToken("--chart-label", "#6F736F");
  ctx.font = "13px IBM Plex Sans, PingFang SC, sans-serif";
  ctx.fillText(message, 28, 42);
}

function drawGrid(ctx, width, pad, priceBottom, minPrice, maxPrice) {
  ctx.strokeStyle = cssToken("--chart-grid", "rgba(117, 107, 88, .16)");
  ctx.lineWidth = 1;
  ctx.fillStyle = cssToken("--chart-label", "#77736B");
  ctx.font = "10px IBM Plex Mono, SFMono-Regular, Menlo, monospace";
  for (let i = 0; i <= 4; i++) {
    const yLine = pad.top + (priceBottom - pad.top) * i / 4;
    ctx.beginPath();
    ctx.moveTo(pad.left, yLine);
    ctx.lineTo(width - pad.right, yLine);
    ctx.stroke();
    const label = maxPrice - (maxPrice - minPrice) * i / 4;
    ctx.fillText(formatNumber(label), width - pad.right + 8, yLine + 4);
  }
}

function drawLine(ctx, values, x, y, color, width) {
  ctx.strokeStyle = color;
  ctx.lineWidth = width;
  ctx.beginPath();
  let started = false;
  values.forEach((value, index) => {
    if (!value) {
      return;
    }
    if (!started) {
      ctx.moveTo(x(index), y(value));
      started = true;
    } else {
      ctx.lineTo(x(index), y(value));
    }
  });
  if (started) {
    ctx.stroke();
  }
}

function drawCrosshair(ctx, x, top, bottom) {
  ctx.save();
  ctx.strokeStyle = cssToken("--chart-crosshair", "rgba(128, 106, 67, .28)");
  ctx.setLineDash([4, 4]);
  ctx.beginPath();
  ctx.moveTo(x, top);
  ctx.lineTo(x, bottom);
  ctx.stroke();
  ctx.restore();
}

function renderChartStats(candles, quote, history = latestHistory) {
  if (history && history.available === false) {
    $("chartStats").innerHTML = '<p class="chart-error">历史行情暂不可用，未展示模拟 K 线。</p>';
    return;
  }
  const latest = candles?.[candles.length - 1] || null;
  const high = candles?.length ? Math.max(...candles.map(candle => numeric(candle.close))) : numeric(quote?.currentPrice);
  const low = candles?.length ? Math.min(...candles.map(candle => numeric(candle.close))) : numeric(quote?.currentPrice);
  const first = candles?.[0];
  const rangeReturn = first && latest && numeric(first.close) > 0
    ? (numeric(latest.close) - numeric(first.close)) / numeric(first.close) * 100
    : 0;
  $("chartStats").innerHTML = [
    ["区间涨跌", `${formatSigned(rangeReturn)}%`, rangeReturn >= 0 ? "up" : "down"],
    ["区间最高", formatNumber(high), "up"],
    ["区间最低", formatNumber(low), "down"]
  ].map(([label, value, klass]) => `
    <article>
      <span>${label}</span>
      <strong class="${klass}">${escapeHtml(value)}</strong>
    </article>
  `).join("");
}

function splitPoints(value) {
  if (Array.isArray(value)) {
    return value.filter(Boolean).slice(0, 2);
  }
  return String(value || "")
    .replaceAll("。", "；")
    .split("；")
    .map(item => item.trim())
    .filter(Boolean)
    .slice(0, 2);
}

function decisionList(points, fallback) {
  const rows = splitPoints(points || fallback);
  return rows.length
    ? `<ol>${rows.map(point => `<li>${escapeHtml(point)}</li>`).join("")}</ol>`
    : `<p>${escapeHtml(fallback)}</p>`;
}

function healthChecks(metrics) {
  return [
    check("盈利能力", "ROE", metric(metrics, "ROE"), value => value >= 0.18, value => value >= 0.10, "ROE 代表净资产创造利润的效率。"),
    check("成长能力", "营收同比", metric(metrics, "REVENUE_YOY"), value => value >= 0.10, value => value >= 0, "收入增长决定公司基本面的扩张速度。"),
    check("利润增长", "净利润同比", metric(metrics, "NET_PROFIT_YOY"), value => value >= 0.10, value => value >= 0, "净利润增长体现盈利弹性。"),
    check("现金流质量", "经营现金流/净利润", metric(metrics, "OCF_NET_PROFIT"), value => value >= 1, value => value >= 0.8, "现金流越接近或超过净利润，利润质量越稳。"),
    check("资产负债率", "资产负债率", metric(metrics, "DEBT_RATIO"), value => value <= 0.45, value => value <= 0.65, "负债率越高，财务安全边际越薄。", true)
  ];
}

function metric(metrics, code) {
  return metrics
    .filter(item => item.code === code)
    .sort((a, b) => String(b.fiscalYear).localeCompare(String(a.fiscalYear)))[0];
}

function check(title, label, metricValue, good, watch, help, inverse = false) {
  const value = Number(metricValue?.value ?? NaN);
  let level = "empty";
  if (!Number.isNaN(value)) {
    level = good(value) ? "good" : watch(value) ? "watch" : "risk";
  }
  return {
    title,
    label,
    value,
    display: Number.isNaN(value) ? "--" : formatMetric(value, inverse),
    fiscalYear: metricValue?.fiscalYear || "--",
    level,
    help
  };
}

function formatMetric(value, raw = false) {
  if (raw || Math.abs(value) <= 2) {
    return `${(value * 100).toFixed(2)}%`;
  }
  return Number(value).toLocaleString("zh-CN");
}

function healthCard(check) {
  return `
    <article class="health-card ${check.level}">
      <span>${escapeHtml(check.title)}</span>
      <strong>${escapeHtml(check.display)}</strong>
      <em>${statusName(check.level)}</em>
    </article>
  `;
}

function emptyHealth() {
  return ["ROE", "营收同比", "净利润同比", "现金流质量", "资产负债率"]
    .map(title => `<article class="health-card empty"><span>${title}</span><strong>--</strong><p>等待分析</p><em>暂无数据</em></article>`)
    .join("");
}

function conclusionText(company, rating, checks, risks, quote) {
  const good = checks.filter(check => check.level === "good").map(check => check.title);
  const weak = checks.filter(check => check.level === "risk").map(check => check.title);
  const priceText = quote?.realtime ? `实时行情显示涨跌幅为 ${Number(quote.changePercent || 0).toFixed(2)}%。` : "实时行情暂不可用，当前以本地分析数据为主。";
  if (rating === "优先研究") {
    return `${company} 当前基本面指标较稳，${good.slice(0, 2).join("、")}表现较好。${priceText} 可以继续关注盈利持续性和估值安全边际。`;
  }
  if (rating === "暂不进入候选") {
    return `${company} 当前尚不满足进入候选池的条件，${weak.slice(0, 2).join("、") || "部分核心指标"}存在压力，系统识别到 ${risks.length} 条风险信号。${priceText}`;
  }
  return `${company} 当前处于等待确认状态，基本面或行情已有部分支撑，但仍需核验${(weak[0] || "行业变化")}和短期价格波动。${priceText}`;
}

function positiveText(checks) {
  const good = checks.filter(check => check.level === "good").map(check => `${check.title}较好`);
  return good.length ? good.slice(0, 3).join("；") + "。" : "暂未看到特别突出的优势指标，需要结合后续财报继续观察。";
}

function negativeText(checks, risks, quote) {
  const weak = checks.filter(check => check.level === "risk").map(check => `${check.title}偏弱`);
  const riskTitles = risks.map(risk => risk.title);
  const priceRisk = Number(quote?.changePercent || 0) < -1 ? ["短期股价承压"] : [];
  const all = [...weak, ...riskTitles, ...priceRisk];
  return all.length ? all.slice(0, 3).join("；") + "。" : "暂未发现明显风险，但仍需关注行业景气度和后续公告变化。";
}

async function runWorkflow() {
  const button = $("runWorkflow");
  const currentWorkflowGeneration = ++workflowGeneration;
  button.disabled = true;
  button.textContent = "分析进行中";
  showAnalysisProgress("prepare", "正在准备数据", "正在基于当前数据生成新版报告；上一版结论仍可继续查看。");
  renderUniverseStatus(`正在分析 ${symbol}...`);
  try {
    const requestedSymbol = resolveSymbol($("symbolInput").value);
    if (requestedSymbol !== symbol) {
      await selectSymbol(requestedSymbol, { preserveAnalysisProgress: true });
    } else {
      $("symbolInput").value = symbol;
    }
    const workflowSymbol = symbol;
    const task = await request("/api/research/tasks", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ symbol })
    });
    const result = await waitForResearchTask(task, currentWorkflowGeneration, workflowSymbol);
    if (!result.completed) {
      showAnalysisProgress("analysis", "任务仍在后台运行", "你可以继续查看行情和上一版结论；新报告完成后会自动更新。", true);
      monitorResearchTask(task, currentWorkflowGeneration, workflowSymbol);
      return;
    }
    await refreshCompletedAnalysis(workflowSymbol, currentWorkflowGeneration);
  } catch (error) {
    if (currentWorkflowGeneration === workflowGeneration) {
      showAnalysisProgress("analysis", "分析未能完成", `${error.message}。你可以稍后重新生成。`, true, true);
      $("analysisUpdatedAt").hidden = false;
      $("analysisUpdatedAt").textContent = "上一版结论未受影响";
    }
  } finally {
    button.disabled = false;
    button.textContent = "生成分析";
  }
}

async function waitForResearchTask(task, currentWorkflowGeneration, workflowSymbol, options = {}) {
  const maxAttempts = options.maxAttempts || 45;
  const pollDelay = options.pollDelay || 900;
  const startedAt = Date.now();
  for (let attempt = 0; attempt < maxAttempts; attempt += 1) {
    if (currentWorkflowGeneration !== workflowGeneration || workflowSymbol !== symbol) {
      return { completed: false, cancelled: true };
    }
    const tasks = attempt === 0
      ? [task]
      : await request(`/api/research/tasks/${encodeURIComponent(task.taskId)}/progress`);
    const failedTask = tasks.find(item => ["FAILED", "DEAD_LETTER"].includes(String(item.status)));
    if (failedTask) {
      throw new Error(failedTask.errorMessage || "分析任务未能完成");
    }
    const aiTask = tasks.find(item => String(item.taskType) === "STOCK_AI_ANALYSIS");
    if (aiTask && String(aiTask.status) === "SUCCEEDED") {
      showAnalysisProgress("complete", "正在保存报告", "新结论已经生成，正在更新分析区域。");
      return { completed: true, task: aiTask };
    }
    const currentTask = workflowCurrentTask(tasks);
    updateAnalysisProgress(currentTask, Date.now() - startedAt);
    await delay(pollDelay);
  }
  return { completed: false };
}

function workflowCurrentTask(tasks) {
  const unfinished = tasks.filter(item => String(item.status) !== "SUCCEEDED");
  return unfinished[unfinished.length - 1] || tasks[tasks.length - 1] || {};
}

function analysisProgressStage(task) {
  const taskType = String(task?.taskType || "");
  const stage = String(task?.stage || "");
  if (taskType === "STOCK_AI_ANALYSIS" || stage === "AI_ANALYZING") return "analysis";
  if (["DOCUMENT_INDEX_BUILD", "COMPANY_INTELLIGENCE_BUILD"].includes(taskType)
      || ["DOCUMENT_INDEXING", "INTELLIGENCE_BUILDING"].includes(stage)) return "evidence";
  if (String(task?.status) === "SUCCEEDED") return "complete";
  return "prepare";
}

function updateAnalysisProgress(task, elapsed) {
  const stage = analysisProgressStage(task);
  const content = {
    prepare: ["正在准备数据", "同步行情与财务指标，建立本次研究快照。"],
    evidence: ["正在检索证据", "整理公开披露、指标变化与风险信号。"],
    analysis: ["正在生成结论", "模型正在归纳支持因素、风险与置信度。"],
    complete: ["正在保存报告", "新结论已经生成，正在更新分析区域。"]
  }[stage];
  const detail = elapsed >= 8000
    ? "任务仍在后台运行，你可以继续查看行情和上一版结论。"
    : content[1];
  showAnalysisProgress(stage, content[0], detail);
}

function showAnalysisProgress(stage, title, detail, persistent = false, failed = false) {
  const progress = $("analysisProgress");
  const stages = ["prepare", "evidence", "analysis", "complete"];
  const activeIndex = Math.max(0, stages.indexOf(stage));
  progress.hidden = false;
  progress.dataset.persistent = String(persistent);
  progress.classList.toggle("is-failed", failed);
  $("analysisProgressTitle").textContent = title;
  $("analysisProgressDetail").textContent = detail;
  progress.querySelectorAll("[data-progress-stage]").forEach((item, index) => {
    item.classList.toggle("is-complete", !failed && index < activeIndex);
    item.classList.toggle("is-active", !failed && index === activeIndex);
  });
}

function hideAnalysisProgress() {
  $("analysisProgress").hidden = true;
  $("analysisProgress").classList.remove("is-failed");
}

async function refreshCompletedAnalysis(workflowSymbol, currentWorkflowGeneration) {
  const aiAnalysis = await request(`/api/research/reports/${encodeURIComponent(workflowSymbol)}/latest`);
  if (workflowSymbol !== symbol || currentWorkflowGeneration !== workflowGeneration) return;
  latestAiAnalysis = aiAnalysis;
  renderAnalysis(latestMetrics, latestRisks, latestQuote, aiAnalysis);
  renderUniverseStatus();
  showAnalysisProgress("complete", "报告已更新", "分析区域已切换到最新结论。");
  window.setTimeout(() => {
    if (currentWorkflowGeneration === workflowGeneration
        && $("analysisProgress").dataset.persistent !== "true") {
      hideAnalysisProgress();
    }
  }, 1800);
  refreshAnalysisInputs(workflowSymbol, currentWorkflowGeneration);
  refreshReportHistory(workflowSymbol, () => workflowSymbol === symbol && currentWorkflowGeneration === workflowGeneration);
}

function refreshAnalysisInputs(workflowSymbol, currentWorkflowGeneration) {
  request(`/api/metrics/${workflowSymbol}`).then(metrics => {
    if (workflowSymbol !== symbol || currentWorkflowGeneration !== workflowGeneration) return;
    latestMetrics = Array.isArray(metrics) ? metrics : [];
    renderAnalysis(latestMetrics, latestRisks, latestQuote, latestAiAnalysis);
  }).catch(() => {});
  request(`/api/metrics/${workflowSymbol}/risks`).then(risks => {
    if (workflowSymbol !== symbol || currentWorkflowGeneration !== workflowGeneration) return;
    latestRisks = Array.isArray(risks) ? risks : [];
    renderAnalysis(latestMetrics, latestRisks, latestQuote, latestAiAnalysis);
  }).catch(() => {});
}

async function monitorResearchTask(task, currentWorkflowGeneration, workflowSymbol) {
  try {
    const result = await waitForResearchTask(task, currentWorkflowGeneration, workflowSymbol, {
      maxAttempts: 60,
      pollDelay: 4000
    });
    if (result.completed) {
      await refreshCompletedAnalysis(workflowSymbol, currentWorkflowGeneration);
    }
  } catch (error) {
    if (currentWorkflowGeneration === workflowGeneration && workflowSymbol === symbol) {
      showAnalysisProgress("analysis", "后台分析未能完成", `${error.message}。你可以稍后重新生成。`, true, true);
    }
  }
}

async function submitAuth(event) {
  event.preventDefault();
  const register = authView === "register";
  const email = $("authEmail").value.trim();
  const password = $("authPassword").value;
  if (register && password !== $("authPasswordConfirm").value) {
    setAuthFeedback("两次输入的密码不一致。");
    return;
  }
  const button = $("authSubmitButton");
  button.disabled = true;
  button.textContent = register ? "创建中…" : "登录中…";
  setAuthFeedback("");
  try {
    const user = await request(`/api/auth/${register ? "register" : "login"}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        email,
        password,
        verificationCode: register ? $("authVerificationCode").value.trim() : undefined
      })
    });
    authUser = user;
    renderAccountState();
    setAuthGateRequired(false);
    const action = pendingAuthAction;
    closeAuthDialog();
    await initializeAuthenticatedWorkspace();
    if (typeof action === "function") {
      await action();
    }
  } catch (error) {
    setAuthFeedback(error.message);
  } finally {
    button.disabled = false;
    button.textContent = register ? "创建账号" : "登录";
  }
}

async function requestEmailVerificationCode() {
  const email = $("authEmail").value.trim();
  if (!email) {
    setAuthFeedback("请先输入邮箱地址。");
    $("authEmail").focus();
    return;
  }
  const button = $("requestVerificationCode");
  button.disabled = true;
  setAuthFeedback("");
  try {
    const result = await request("/api/auth/verification-code", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email })
    });
    $("authVerificationHint").textContent = result.message || "验证码已发送。";
    let remaining = 60;
    button.textContent = `${remaining}s 后重发`;
    window.clearInterval(verificationCountdownTimer);
    verificationCountdownTimer = window.setInterval(() => {
      remaining -= 1;
      if (remaining <= 0) {
        window.clearInterval(verificationCountdownTimer);
        button.disabled = false;
        button.textContent = "获取验证码";
        return;
      }
      button.textContent = `${remaining}s 后重发`;
    }, 1000);
  } catch (error) {
    setAuthFeedback(error.message);
    button.disabled = false;
  }
}

async function requestPasswordReset(event) {
  event.preventDefault();
  const button = $("resetRequestForm").querySelector(".primary-action");
  button.disabled = true;
  setAuthFeedback("");
  try {
    const result = await request("/api/auth/password-reset/request", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email: $("resetEmail").value.trim() })
    });
    setAuthFeedback(result.message);
  } catch (error) {
    setAuthFeedback(error.message);
  } finally {
    button.disabled = false;
  }
}

async function confirmPasswordReset(event) {
  event.preventDefault();
  const password = $("resetPassword").value;
  if (password !== $("resetPasswordConfirm").value) {
    setAuthFeedback("两次输入的密码不一致。");
    return;
  }
  const button = $("resetConfirmForm").querySelector(".primary-action");
  button.disabled = true;
  try {
    const result = await request("/api/auth/password-reset/confirm", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ token: resetToken, password })
    });
    setAuthFeedback(result.message);
    window.setTimeout(() => openAuthDialog("login"), 700);
  } catch (error) {
    setAuthFeedback(error.message);
  } finally {
    button.disabled = false;
  }
}

async function logout() {
  const button = $("logoutButton");
  button.disabled = true;
  try {
    await request("/api/auth/logout", { method: "POST" });
  } catch (error) {
    if (error.status !== 401) {
      $("watchlistActionStatus").textContent = `退出失败：${error.message}`;
      button.disabled = false;
      return;
    }
  }
  authUser = null;
  latestWatchlist = [];
  renderAccountState();
  setAuthGateRequired(true);
  openAuthDialog("login");
  setWorkspace("company");
  button.disabled = false;
}

function openResetFromUrl() {
  const params = new URLSearchParams(window.location.search);
  resetToken = params.get("resetToken") || "";
  if (resetToken) {
    openAuthDialog("reset-confirm");
  }
}

$("accountButton").addEventListener("click", () => {
  if (authUser) {
    setWorkspace("watchlist");
  } else {
    openAuthDialog("login");
  }
});
$("logoutButton").addEventListener("click", logout);
$("requestVerificationCode").addEventListener("click", requestEmailVerificationCode);
document.querySelectorAll(".auth-tab").forEach(tab => {
  tab.addEventListener("click", () => {
    authView = tab.dataset.authView || "login";
    renderAuthView();
  });
});
$("authForm").addEventListener("submit", submitAuth);
$("forgotPasswordButton").addEventListener("click", () => {
  authView = "reset-request";
  renderAuthView();
  $("resetEmail").focus();
});
$("backToLoginButton").addEventListener("click", () => {
  authView = "login";
  renderAuthView();
});
$("resetRequestForm").addEventListener("submit", requestPasswordReset);
$("resetConfirmForm").addEventListener("submit", confirmPasswordReset);
$("resetConfirmBackButton").addEventListener("click", () => {
  authView = "login";
  resetToken = "";
  renderAuthView();
});
document.addEventListener("click", event => {
  if (event.target.closest("[data-open-auth]")) {
    openAuthDialog("login");
  }
});

function delay(milliseconds) {
  return new Promise(resolve => window.setTimeout(resolve, milliseconds));
}

async function suggestStocks(query) {
  const normalized = String(query || "").trim();
  const suggestions = normalized
    ? await request(`/api/companies/search?q=${encodeURIComponent(normalized)}&limit=12`).catch(() => [])
    : companies.slice(0, 12);
  $("stockSuggestions").innerHTML = suggestions.map(company =>
    `<option value="${escapeHtml(company.symbol)}">${escapeHtml(company.name)}，${escapeHtml(company.exchange)}，${escapeHtml(company.industry)}</option>`
  ).join("");
}

async function search() {
  const button = $("searchButton");
  const question = $("searchInput").value.trim();
  if (!question) {
    $("questionStatus").textContent = "请先输入一个研究问题。";
    $("searchInput").focus();
    return;
  }
  button.disabled = true;
  button.textContent = "检索中";
  $("questionStatus").textContent = "正在检索财报与公告…";
  $("questionAnswer").hidden = true;
  $("sourcePanel").hidden = true;
  $("retrievalList").innerHTML = "";
  try {
    const query = encodeURIComponent(question);
    const endpoint = evidenceScope === "global"
      ? `/api/document-index/search?q=${query}&limit=6`
      : `/api/document-index/${symbol}/search?q=${query}&limit=6`;
    const results = await request(endpoint);
    const visible = results.slice(0, 6);
    $("questionStatus").textContent = visible.length
      ? `已找到 ${visible.length} 条${evidenceScope === "global" ? "全市场" : "当前公司"}来源`
      : "暂未找到与问题直接相关的来源";
    $("questionAnswer").hidden = false;
    $("sourcePanel").hidden = !visible.length;
    $("questionAnswer").innerHTML = visible.length
      ? `<span>检索摘要</span><p>以下${evidenceScope === "global" ? "全市场" : escapeHtml($("evidenceCompanyName").textContent)}公开资料与“${escapeHtml(question)}”最相关。</p>`
      : `<span>暂无匹配</span><p>请先生成分析以建立证据索引，或换一个更具体的问题。</p>`;
    $("retrievalList").innerHTML = visible.map((hit, index) => {
      const section = String(hit.section || "公开资料").split(" / ")[0];
      const sourceMeta = [documentTypeName(hit.documentType), hit.publishedAt, section].filter(Boolean).join("，");
      return `
        <details class="source-result">
          <summary>
            <span class="source-index">${String(index + 1).padStart(2, "0")}</span>
            <span class="source-title">${escapeHtml(hit.title)}</span>
            <span class="source-score">${escapeHtml(hit.publishedAt || "")}</span>
          </summary>
          <div class="source-detail">
            <p>${escapeHtml(hit.text || "")}</p>
            <span>${escapeHtml(sourceMeta)}</span>
          </div>
        </details>
      `;
    }).join("");
  } catch (error) {
    $("questionStatus").textContent = "检索失败，请稍后重试。";
    $("questionAnswer").hidden = false;
    $("questionAnswer").innerHTML = `<span>连接失败</span><p>${escapeHtml(error.message)}</p>`;
  } finally {
    button.disabled = false;
    button.textContent = "检索";
  }
}

async function selectSymbol(nextSymbol, options = {}) {
  const input = $("symbolInput");
  let next;
  try {
    next = resolveSymbol(nextSymbol);
  } catch (error) {
    input.setCustomValidity(error.message);
    input.reportValidity();
    input.focus();
    return false;
  }
  input.setCustomValidity("");
  if (next !== symbol && !options.preserveAnalysisProgress) {
    workflowGeneration += 1;
    hideAnalysisProgress();
  }
  symbol = next;
  $("symbolInput").value = symbol;
  await refresh();
  $("questionStatus").textContent = "";
  $("questionAnswer").hidden = true;
  $("sourcePanel").hidden = true;
  $("retrievalList").innerHTML = "";
  return true;
}

function renderDailyRecommendations(result) {
  const items = Array.isArray(result?.items) ? result.items : [];
  const top = items[0];
  if (!top) return null;
  const scoreText = value => Number.isFinite(Number(value)) ? Number(value).toFixed(1) : "--";
  document.querySelector(".scan-lead-body h2").textContent = top.name;
  document.querySelector(".scan-symbol").textContent = `${top.symbol}.${top.exchange} · ${top.industry || "待分类"}`;
  document.querySelector(".scan-lead-topline strong").textContent = `${scoreText(top.score)} / 100`;
  document.querySelector(".scan-thesis").textContent = "基于当日全市场行情、流动性与估值约束生成的候选。进入公司研究后，请结合原始证据和风险信号判断。";
  const chips = document.querySelectorAll(".scan-chips span");
  [top.qualityScore, top.trendScore, top.liquidityScore].forEach((value, index) => {
    if (chips[index]) chips[index].textContent = ["估值约束", "趋势", "流动性"][index] + " " + scoreText(value);
  });
  const leadResearchButton = document.querySelector(".scan-lead-footer .scan-research-button");
  leadResearchButton.dataset.symbol = top.symbol;
  leadResearchButton.disabled = false;
  leadResearchButton.textContent = "查看研究";
  leadResearchButton.onclick = async () => {
    setWorkspace("company");
    await selectSymbol(top.symbol);
  };

  const scores = [
    ["趋势结构", "日内动量与振幅约束", top.trendScore],
    ["估值约束", "PE 与 PB 的基础筛选", top.qualityScore],
    ["流动性", "成交额与换手活跃度", top.liquidityScore],
    ["风险扣分", "过高振幅与极端涨跌", `−${scoreText(top.riskPenalty)}`]
  ];
  document.querySelectorAll(".scan-score-list > div").forEach((row, index) => {
    const [name, detail, value] = scores[index];
    row.querySelector("strong").textContent = name;
    row.querySelector("small").textContent = detail;
    row.querySelector("dd").textContent = value;
  });
  const rows = $("scanCandidateRows");
  rows.innerHTML = items.map(item => `
    <tr data-scan-kind="all trend quality">
      <td><strong>${escapeHtml(item.name)}</strong><span>${escapeHtml(item.symbol)}.${escapeHtml(item.exchange)} · ${escapeHtml(item.industry || "待分类")}</span></td>
      <td><b>${scoreText(item.score)}</b></td>
      <td><i style="--score:${Math.max(0, Math.min(100, Number(item.trendScore) || 0))}%"></i></td>
      <td>${scoreText(item.qualityScore)}</td><td>${scoreText(item.liquidityScore)}</td>
      <td class="${Number(item.changePercent) >= 0 ? "up" : "down"}">${formatSigned(item.changePercent)}%</td>
      <td><button class="scan-research-button" type="button" data-symbol="${escapeHtml(item.symbol)}">研究 →</button></td>
    </tr>`).join("");
  document.querySelector(".scan-candidates-heading p").textContent = `共 ${items.length} 只 · 已按综合评分排序 · 涨跌颜色仅表示市场表现`;
  const sourceLabel = String(result.source || "").includes("sina") ? "新浪备用行情" : "东方财富行情";
  const strategyLabel = result.strategyVersion ? ` · 策略 ${result.strategyVersion}` : "";
  document.querySelector(".scan-data-note").textContent = `${sourceLabel}${strategyLabel} · 全市场 ${result.universeSize.toLocaleString("zh-CN")} 只 · ${formatDateTime(result.scannedAt)}`;
  document.querySelector(".scan-top-picks").setAttribute("aria-busy", "false");
  $("retryMarketScan").hidden = true;
  window.clearTimeout(dailyRecommendationRetryTimer);
  rows.querySelectorAll(".scan-research-button").forEach(button => {
    button.addEventListener("click", async () => {
      setWorkspace("company");
      await selectSymbol(button.dataset.symbol);
    });
  });
  return top;
}

async function loadDailyRecommendations() {
  const result = await request("/api/market/recommendations");
  return renderDailyRecommendations(result);
}

function showDailyRecommendationError(error) {
  const message = error?.name === "AbortError"
    ? "全市场扫描仍在进行，10 秒后将自动重试。"
    : "行情源连接暂时中断，10 秒后将自动重试。";
  document.querySelector(".scan-lead-topline > span").textContent = "行情源等待恢复";
  document.querySelector(".scan-lead-body h2").textContent = "暂时无法完成今日扫描";
  document.querySelector(".scan-symbol").textContent = "不会展示过期或固定候选";
  document.querySelector(".scan-thesis").textContent = "外部行情快照未能完整读取。你可以立即重新读取，或等待系统自动重试。";
  document.querySelector(".scan-lead-footer .scan-research-button").disabled = true;
  document.querySelector(".scan-lead-footer .scan-research-button").textContent = "等待行情";
  $("scanCandidateRows").innerHTML = '<tr><td colspan="7">行情源连接中断，尚未生成今日候选。</td></tr>';
  document.querySelector(".scan-candidates-heading p").textContent = "等待当日全市场行情快照；不会回退到旧候选。";
  document.querySelector(".scan-data-note").textContent = message;
  document.querySelector(".scan-top-picks").setAttribute("aria-busy", "false");
  $("retryMarketScan").hidden = false;
}

async function applyDailyRecommendation(top) {
  if (!top?.symbol) return;
  symbol = top.symbol;
  $("symbolInput").value = symbol;
  await refresh();
  await suggestStocks(symbol);
  openResetFromUrl();
}

function scheduleDailyRecommendationRetry() {
  window.clearTimeout(dailyRecommendationRetryTimer);
  dailyRecommendationRetryTimer = window.setTimeout(async () => {
    try {
      const top = await loadDailyRecommendations();
      await applyDailyRecommendation(top);
    } catch (error) {
      showDailyRecommendationError(error);
      scheduleDailyRecommendationRetry();
    }
  }, 10_000);
}

function ratingClass(rating) {
  return rating === "优先研究" || rating === "积极" ? "positive" : rating === "暂不进入候选" || rating === "谨慎" ? "cautious" : "neutral";
}

function analysisMeta(aiAnalysis) {
  const source = aiAnalysis.aiGenerated
    ? (aiAnalysis.model || aiAnalysis.source || "AI 模型")
    : "规则兜底";
  return `${source}，${aiAnalysis.cacheHit ? "缓存命中" : "新报告"}，${formatDateTime(aiAnalysis.generatedAt) || "刚刚"}`;
}

function statusName(level) {
  return ({
    good: "健康",
    watch: "关注",
    risk: "风险",
    empty: "暂无数据"
  })[level] || level;
}

function formatNumber(value, digits = 2) {
  if (value == null || value === "") {
    return "--";
  }
  const number = numeric(value);
  return Number.isFinite(number) ? number.toFixed(digits) : "--";
}

function formatSigned(value) {
  const number = numeric(value);
  if (!Number.isFinite(number)) {
    return "--";
  }
  return `${number > 0 ? "+" : ""}${number.toFixed(2)}`;
}

function formatMoney(value) {
  const number = numeric(value);
  if (!number) {
    return "--";
  }
  if (number >= 100000000) {
    return `${(number / 100000000).toFixed(2)}亿`;
  }
  if (number >= 10000) {
    return `${(number / 10000).toFixed(1)}万`;
  }
  return number.toLocaleString("zh-CN");
}

function formatDateTime(value) {
  if (!value) {
    return "";
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return String(value).replace("T", " ").replace(/Z$/, "").replace(/\.\d+$/, "");
  }
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat("zh-CN", {
      timeZone: "Asia/Shanghai",
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      hourCycle: "h23"
    }).formatToParts(date).map(part => [part.type, part.value])
  );
  return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}`;
}

function formatEventDate(value) {
  if (!value) {
    return "";
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return String(value).slice(0, 10);
  }
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat("zh-CN", {
      timeZone: "Asia/Shanghai",
      year: "numeric",
      month: "2-digit",
      day: "2-digit"
    }).formatToParts(date).map(part => [part.type, part.value])
  );
  return `${parts.year}-${parts.month}-${parts.day}`;
}

function numeric(value) {
  const number = Number(value);
  return Number.isFinite(number) ? number : 0;
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function normalizeSymbol(value) {
  const next = String(value || "").trim().toUpperCase();
  if (!next) {
    throw new Error("请输入股票代码");
  }
  return next;
}

function resolveSymbol(value) {
  const query = normalizeSymbol(value);
  const bareSymbol = query.replace(/\.(SH|SZ|BJ)$/i, "");
  if (/^\d{6}$/.test(bareSymbol)) {
    return bareSymbol;
  }
  const matchedCompany = companies.find(company => {
    const companySymbol = String(company.symbol || "").toUpperCase();
    const companyName = String(company.name || "").trim().toUpperCase();
    return companySymbol === query || companyName === query;
  });
  if (matchedCompany?.symbol) {
    return String(matchedCompany.symbol);
  }
  throw new Error("请输入 6 位股票代码，或从建议中选择公司");
}

function documentTypeName(value) {
  return ({
    ANNUAL_REPORT: "年度报告",
    QUARTERLY_REPORT: "季度报告",
    ANNOUNCEMENT: "公司公告",
    NEWS: "公开资讯",
    METRIC: "财务指标"
  })[value] || "公开资料";
}

function marketSourceName(value) {
  return ({
    SINA_QUOTE: "新浪行情",
    TENCENT_QUOTE: "腾讯行情",
    EASTMONEY_QUOTE: "东方财富",
    EASTMONEY_HISTORY: "东方财富历史行情",
    LOCAL_DEMO: "演示数据（模拟）",
    LOCAL_FALLBACK: "本地快照",
    LOCAL_ERROR: "本地快照"
  })[value] || value || "待同步";
}

$("runWorkflow").addEventListener("click", runWorkflow);
$("searchButton").addEventListener("click", search);
$("searchInput").addEventListener("keydown", event => {
  if (event.key === "Enter") {
    event.preventDefault();
    search();
  }
});
$("symbolSearchButton").addEventListener("click", () => selectSymbol($("symbolInput").value));
$("openAnalysisEvidence").addEventListener("click", () => setWorkspace("evidence"));
$("comparePreviousToggle").addEventListener("change", event => {
  if (event.currentTarget.checked) {
    openLatestReportComparison();
  } else {
    closeReportComparison();
  }
});
$("closeReportDiff").addEventListener("click", closeReportComparison);
$("addCurrentToWatchlist").addEventListener("click", addCurrentCompanyToWatchlist);
$("symbolInput").addEventListener("keydown", event => {
  if (event.key === "Enter") {
    event.preventDefault();
    selectSymbol(event.currentTarget.value);
  }
});
$("symbolInput").addEventListener("input", event => {
  event.currentTarget.setCustomValidity("");
  clearTimeout(suggestionTimer);
  suggestionTimer = setTimeout(() => suggestStocks(event.target.value), 180);
});
document.querySelectorAll(".range-tab").forEach(button => {
  button.addEventListener("click", async () => {
    document.querySelectorAll(".range-tab").forEach(item => {
      const selected = item === button;
      item.classList.toggle("active", selected);
      item.setAttribute("aria-pressed", String(selected));
    });
    chartLimit = Number(button.dataset.limit || 120);
    await refreshChart();
  });
});

$("retryChart").addEventListener("click", refreshChart);
async function refreshChart() {
  const requestedSymbol = symbol;
  const requestedLimit = chartLimit;
  const requestGeneration = ++chartGeneration;
  try {
    const history = await request(`/api/market/history/${requestedSymbol}?limit=${requestedLimit}`);
    if (
      requestGeneration !== chartGeneration
      || requestedSymbol !== symbol
      || requestedLimit !== chartLimit
    ) {
      return;
    }
    applyHistoryResponse(history);
  } catch (error) {
    if (requestGeneration === chartGeneration) {
      applyHistoryResponse(unavailableHistory(error));
    }
  }
}

const productNav = $("productNav");
const mobileMenuButton = $("mobileMenuButton");
const navBackdrop = $("navBackdrop");
const workspaceLabels = {
  marketScan: "今日优选",
  company: "公司研究",
  analysis: "AI 分析",
  evidence: "证据来源",
  events: "近期事件",
  watchlist: "关注列表"
};
const workspaceViews = Object.fromEntries(
  [...document.querySelectorAll("[data-workspace-view]")].map(view => [view.dataset.workspaceView, view])
);

function setNavigationOpen(open) {
  productNav.classList.toggle("open", open);
  mobileMenuButton.setAttribute("aria-expanded", String(open));
  mobileMenuButton.setAttribute("aria-label", open ? "关闭导航" : "打开导航");
  navBackdrop.hidden = !open;
  document.body.classList.toggle("navigation-open", open);
}

mobileMenuButton.addEventListener("click", () => {
  setNavigationOpen(!productNav.classList.contains("open"));
});

navBackdrop.addEventListener("click", () => setNavigationOpen(false));

function setWorkspace(view, updateHistory = true) {
  const nextView = workspaceViews[view] ? view : "marketScan";
  Object.entries(workspaceViews).forEach(([name, element]) => {
    element.hidden = name !== nextView;
  });
  document.body.dataset.workspace = nextView;
  $("workspaceLabel").textContent = workspaceLabels[nextView];
  document.querySelectorAll(".product-link").forEach(item => {
    const active = item.dataset.workspace === nextView;
    item.classList.toggle("active", active);
    if (active) {
      item.setAttribute("aria-current", "page");
    } else {
      item.removeAttribute("aria-current");
    }
  });
  setNavigationOpen(false);
  if (updateHistory) {
    const target = workspaceViews[nextView]?.id;
    if (target && window.location.hash !== `#${target}`) {
      window.history.pushState({ workspace: nextView }, "", `#${target}`);
    }
  }
  window.scrollTo({ top: 0, behavior: updateHistory ? "smooth" : "auto" });
  if (nextView === "company") {
    requestAnimationFrame(() => renderChart(latestCandles));
  }
  if (nextView === "evidence") {
    $("evidenceScopeLabel").textContent = evidenceScope === "global"
      ? "全市场"
      : `${$("evidenceCompanyName").textContent} ${$("evidenceCompanySymbol").textContent}`;
    requestAnimationFrame(() => $("searchInput").focus());
  }
}

$("newResearchButton").addEventListener("click", () => {
  setWorkspace("company");
  $("symbolInput").focus();
});

document.querySelectorAll(".scan-filter").forEach(button => {
  button.addEventListener("click", () => {
    const filter = button.dataset.scanFilter || "all";
    document.querySelectorAll(".scan-filter").forEach(item => {
      const selected = item === button;
      item.classList.toggle("active", selected);
      item.setAttribute("aria-pressed", String(selected));
    });
    document.querySelectorAll("#scanCandidateRows tr").forEach(row => {
      row.hidden = !row.dataset.scanKind?.split(" ").includes(filter);
    });
  });
});

$("retryMarketScan").addEventListener("click", async () => {
  $("retryMarketScan").hidden = true;
  document.querySelector(".scan-data-note").textContent = "正在重新读取今日全市场候选…";
  document.querySelector(".scan-top-picks").setAttribute("aria-busy", "true");
  try {
    const top = await loadDailyRecommendations();
    await applyDailyRecommendation(top);
  } catch (error) {
    showDailyRecommendationError(error);
    scheduleDailyRecommendationRetry();
  }
});

document.querySelectorAll(".scan-research-button[data-symbol]").forEach(button => {
  button.addEventListener("click", async () => {
    const nextSymbol = button.dataset.symbol;
    if (!nextSymbol) return;
    setWorkspace("company");
    await selectSymbol(nextSymbol);
  });
});

document.querySelectorAll(".product-link").forEach(link => {
  link.addEventListener("click", event => {
    event.preventDefault();
    setWorkspace(link.dataset.workspace || "company");
  });
});

document.querySelectorAll("[data-workspace-link]").forEach(link => {
  link.addEventListener("click", event => {
    event.preventDefault();
    setWorkspace(link.dataset.workspaceLink || "company");
  });
});

document.querySelectorAll(".scope-tab").forEach(button => {
  button.addEventListener("click", () => {
    evidenceScope = button.dataset.scope === "global" ? "global" : "company";
    document.querySelectorAll(".scope-tab").forEach(item => {
      const selected = item === button;
      item.classList.toggle("active", selected);
      item.setAttribute("aria-pressed", String(selected));
    });
    $("evidenceScopeLabel").textContent = evidenceScope === "global"
      ? "全市场"
      : `${$("evidenceCompanyName").textContent} ${$("evidenceCompanySymbol").textContent}`;
    $("questionStatus").textContent = "";
    $("questionAnswer").hidden = true;
    $("sourcePanel").hidden = true;
    $("retrievalList").innerHTML = "";
  });
});

document.querySelectorAll(".query-suggestions button").forEach(button => {
  button.addEventListener("click", () => {
    $("searchInput").value = button.textContent.trim();
    search();
  });
});

document.addEventListener("keydown", event => {
  if (event.key === "Escape") {
    if (!$("authDialog").hidden) {
      closeAuthDialog();
    } else {
      setNavigationOpen(false);
    }
  }
  if (event.key === "/" && $("authDialog").hidden && document.activeElement?.tagName !== "INPUT") {
    event.preventDefault();
    $("symbolInput").focus();
  }
});

window.addEventListener("resize", () => {
  if (window.innerWidth > 900 && productNav.classList.contains("open")) {
    setNavigationOpen(false);
  }
  if (document.body.dataset.workspace === "company") {
    renderChart(latestCandles);
  }
});

function workspaceFromHash() {
  return document.querySelector(`.product-link[href="${window.location.hash}"]`)?.dataset.workspace || "marketScan";
}

function syncWorkspaceFromHash() {
  const nextWorkspace = workspaceFromHash();
  if (document.body.dataset.workspace !== nextWorkspace) {
    setWorkspace(nextWorkspace, false);
  }
}

window.addEventListener("popstate", syncWorkspaceFromHash);
window.addEventListener("hashchange", syncWorkspaceFromHash);

const initialWorkspace = workspaceFromHash();
setWorkspace(initialWorkspace, false);

async function initializeAuthenticatedWorkspace() {
  try {
    const top = await loadDailyRecommendations();
    await applyDailyRecommendation(top);
  } catch (error) {
    showDailyRecommendationError(error);
    scheduleDailyRecommendationRetry();
  }
}

async function initializeApp() {
  try {
    await loadSession();
  } catch (error) {
    $("accountStatusDetail").textContent = error.message;
    setAuthGateRequired(true);
    openAuthDialog("login");
    setAuthFeedback(error.message);
    return;
  }
  if (!authUser) {
    openAuthDialog("login");
    return;
  }
  await initializeAuthenticatedWorkspace();
}

initializeApp().catch(error => {
  $("analysisConclusion").textContent = `后端服务未就绪：${error.message}`;
});
