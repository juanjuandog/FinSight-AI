let symbol = "600519";
let companies = [];
let suggestionTimer = null;
let chartLimit = 120;
let latestQuote = null;
let latestCandles = [];
let latestAiAnalysis = null;
let latestEvents = [];
let latestWatchlist = [];
let eventsLoadError = false;
let watchlistLoadError = false;
let evidenceScope = "company";
let refreshGeneration = 0;
let chartGeneration = 0;

const $ = (id) => document.getElementById(id);

function cssToken(name, fallback) {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || fallback;
}

async function request(path, options = {}) {
  const response = await fetch(path, options);
  if (!response.ok) {
    throw new Error(`${response.status} ${response.statusText}`);
  }
  return response.json();
}

async function refresh() {
  const requestGeneration = ++refreshGeneration;
  const requestedSymbol = symbol;
  const requestedChartGeneration = ++chartGeneration;
  const [
    nextCompanies,
    quote,
    candles,
    metrics,
    risks,
    aiAnalysis,
    eventsResult,
    watchlistResult
  ] = await Promise.all([
    request("/api/companies?limit=200").catch(() => companies),
    request(`/api/market/quotes/${requestedSymbol}`).catch(error => ({
      symbol: requestedSymbol,
      name: `股票 ${requestedSymbol}`,
      exchange: "CN",
      realtime: false,
      source: "LOCAL_ERROR",
      message: error.message
    })),
    request(`/api/market/history/${requestedSymbol}?limit=${chartLimit}`).catch(() => []),
    request(`/api/metrics/${requestedSymbol}`).catch(() => []),
    request(`/api/metrics/${requestedSymbol}/risks`).catch(() => []),
    request(`/api/companies/${requestedSymbol}/ai-analysis/latest`).catch(() => null),
    request(`/api/intelligence/${requestedSymbol}/timeline`)
      .then(data => ({ ok: true, data }))
      .catch(error => ({ ok: false, error })),
    request("/api/watchlist")
      .then(data => ({ ok: true, data }))
      .catch(error => ({ ok: false, error }))
  ]);

  if (requestGeneration !== refreshGeneration || requestedSymbol !== symbol) {
    return;
  }

  companies = nextCompanies;
  latestQuote = quote;
  latestAiAnalysis = aiAnalysis;
  eventsLoadError = !eventsResult.ok;
  watchlistLoadError = !watchlistResult.ok;
  latestEvents = eventsResult.ok && Array.isArray(eventsResult.data) ? eventsResult.data : [];
  latestWatchlist = watchlistResult.ok && Array.isArray(watchlistResult.data) ? watchlistResult.data : [];
  if (requestedChartGeneration === chartGeneration) {
    latestCandles = candles;
  }

  renderUniverseStatus();
  renderWatchlist();
  renderEvents();
  updateCompanyCard(quote);
  renderAnalysis(metrics, risks, quote, aiAnalysis);
  renderChart(latestCandles);
  renderChartStats(latestCandles, quote);
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
    const displayRating = aiAnalysis?.rating || "等待分析";
    $("ratingBadge").textContent = displayRating;
    $("ratingBadge").className = `rating ${ratingClass(displayRating)}`;
    $("analysisConclusion").textContent = aiAnalysis?.summary || "点击“生成分析”后，系统会回答这只股票能不能看、为什么、风险在哪里、证据来自哪里。";
    $("positivePoints").innerHTML = decisionList(aiAnalysis?.positivePoints, "暂无核心理由。");
    $("negativePoints").innerHTML = decisionList(aiAnalysis?.riskPoints, "暂无主要风险。");
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
  const rating = warningCount >= 4 || quoteWeak ? "谨慎" : warningCount >= 2 ? "中性" : "积极";
  const confidence = Math.max(68, Math.min(92, 86 - warningCount * 4 + (quote?.realtime ? 4 : 0)));
  const company = quote?.name || companies.find(item => item.symbol === symbol)?.name || `股票 ${symbol}`;
  const displayRating = aiAnalysis?.rating || rating;
  const displayConfidence = aiAnalysis?.confidence ?? confidence;

  $("ratingBadge").textContent = displayRating;
  $("ratingBadge").className = `rating ${ratingClass(displayRating)}`;
  $("analysisConclusion").textContent = aiAnalysis?.summary || conclusionText(company, rating, checks, risks, quote);
  $("positivePoints").innerHTML = decisionList(aiAnalysis?.positivePoints, positiveText(checks));
  $("negativePoints").innerHTML = decisionList(aiAnalysis?.riskPoints, negativeText(checks, risks, quote));
  $("confidenceScore").textContent = `${displayConfidence}%`;
  $("confidenceScore").classList.remove("pending");
  $("confidenceScore").hidden = false;
  $("analysisUpdatedAt").textContent = aiAnalysis ? analysisMeta(aiAnalysis) : quote?.tradeDate && quote?.tradeTime ? `${quote.tradeDate} ${quote.tradeTime}` : "基于当前数据";
  $("analysisUpdatedAt").hidden = false;
  $("healthList").innerHTML = checks.map(healthCard).join("");
}

function renderChart(candles) {
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
    drawEmptyChart(ctx, width, height);
    return;
  }

  const data = candles.map(candle => ({
    date: candle.tradeDate,
    close: numeric(candle.close),
    changePercent: numeric(candle.changePercent)
  })).filter(candle => candle.close > 0);

  if (data.length < 2) {
    drawEmptyChart(ctx, width, height);
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

function drawEmptyChart(ctx, width, height) {
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
  ctx.fillText("暂无历史行情数据，等待行情源返回 K 线。", 28, 42);
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

function renderChartStats(candles, quote) {
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
  if (rating === "积极") {
    return `${company} 当前基本面指标较稳，${good.slice(0, 2).join("、")}表现较好。${priceText} 可以继续关注盈利持续性和估值安全边际。`;
  }
  if (rating === "谨慎") {
    return `${company} 当前需要谨慎观察，${weak.slice(0, 2).join("、") || "部分核心指标"}存在压力，系统识别到 ${risks.length} 条风险信号。${priceText}`;
  }
  return `${company} 当前处于中性观察状态，基本面有支撑，但仍需关注${(weak[0] || "行业变化")}和短期价格波动。${priceText}`;
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
  button.disabled = true;
  button.textContent = "正在分析";
  $("analysisUpdatedAt").hidden = false;
  $("analysisUpdatedAt").textContent = "正在准备研究任务";
  renderUniverseStatus(`正在分析 ${symbol}...`);
  try {
    const requestedSymbol = resolveSymbol($("symbolInput").value);
    if (requestedSymbol !== symbol) {
      await selectSymbol(requestedSymbol);
    } else {
      $("symbolInput").value = symbol;
    }
    const task = await request("/api/research/tasks", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ symbol })
    });
    await waitForResearchTask(task);
    await refresh();
    $("analysisUpdatedAt").textContent = `分析已更新，${formatDateTime(latestAiAnalysis?.generatedAt) || "刚刚"}`;
  } catch (error) {
    $("analysisUpdatedAt").textContent = `分析失败：${error.message}`;
  } finally {
    button.disabled = false;
    button.textContent = "生成分析";
  }
}

async function waitForResearchTask(task) {
  let currentTask = task;
  const terminalStates = new Set(["SUCCEEDED", "FAILED", "DEAD_LETTER"]);
  for (let attempt = 0; attempt < 45; attempt += 1) {
    const status = String(currentTask?.status || "");
    if (terminalStates.has(status)) {
      if (status !== "SUCCEEDED") {
        throw new Error(currentTask?.errorMessage || "分析任务未能完成");
      }
      return currentTask;
    }
    const stage = String(currentTask?.stage || "处理中").replaceAll("_", " ").toLowerCase();
    $("analysisUpdatedAt").textContent = `分析进行中：${stage}`;
    await delay(900);
    currentTask = await request(`/api/research/tasks/${encodeURIComponent(currentTask.taskId)}`);
  }
  throw new Error("分析仍在后台运行，请稍后查看");
}

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

async function selectSymbol(nextSymbol) {
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
  symbol = next;
  $("symbolInput").value = symbol;
  await refresh();
  $("questionStatus").textContent = "";
  $("questionAnswer").hidden = true;
  $("sourcePanel").hidden = true;
  $("retrievalList").innerHTML = "";
  return true;
}

function ratingClass(rating) {
  return rating === "积极" ? "positive" : rating === "谨慎" ? "cautious" : "neutral";
}

function analysisMeta(aiAnalysis) {
  return `${aiAnalysis.aiGenerated ? "Ollama 本地模型" : "规则兜底"}，${aiAnalysis.cacheHit ? "缓存命中" : "新报告"}，${formatDateTime(aiAnalysis.generatedAt) || aiAnalysis.source || "AI 分析"}`;
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

async function refreshChart() {
  const requestedSymbol = symbol;
  const requestedLimit = chartLimit;
  const requestGeneration = ++chartGeneration;
  try {
    const candles = await request(`/api/market/history/${requestedSymbol}?limit=${requestedLimit}`);
    if (
      requestGeneration !== chartGeneration
      || requestedSymbol !== symbol
      || requestedLimit !== chartLimit
    ) {
      return;
    }
    latestCandles = Array.isArray(candles) ? candles : [];
    renderChart(latestCandles);
    renderChartStats(latestCandles, latestQuote);
  } catch {
    if (requestGeneration === chartGeneration) {
      $("chartStats").innerHTML = '<p class="chart-error">价格历史暂时无法加载</p>';
      renderChart([]);
    }
  }
}

const productNav = $("productNav");
const mobileMenuButton = $("mobileMenuButton");
const navBackdrop = $("navBackdrop");
const workspaceLabels = {
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
  const nextView = workspaceViews[view] ? view : "company";
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
    setNavigationOpen(false);
  }
  if (event.key === "/" && document.activeElement?.tagName !== "INPUT") {
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
  return document.querySelector(`.product-link[href="${window.location.hash}"]`)?.dataset.workspace || "company";
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

refresh().then(() => suggestStocks(symbol)).catch(error => {
  $("analysisConclusion").textContent = `后端服务未就绪：${error.message}`;
});
