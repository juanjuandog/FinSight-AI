import { spawn } from "node:child_process";
import { mkdir, rm, writeFile } from "node:fs/promises";
import { setTimeout as delay } from "node:timers/promises";

const chrome = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const outputDir = new URL("../docs/", import.meta.url);
const userDataDir = "/private/tmp/finsight-readme-chrome";
const port = Number(process.env.PORT || 9223);
const baseUrl = `http://127.0.0.1:${port}`;
const pageUrl = process.env.PAGE_URL || "http://localhost:8080";
const connectOnly = process.env.CONNECT_ONLY === "1";
const viewport = { width: 1440, height: 960 };

await mkdir(outputDir, { recursive: true });
await rm(userDataDir, { recursive: true, force: true });

const browser = connectOnly ? null : spawn(chrome, [
  "--headless=new",
  `--remote-debugging-port=${port}`,
  "--remote-debugging-address=127.0.0.1",
  `--user-data-dir=${userDataDir}`,
  "--no-first-run",
  "--no-default-browser-check",
  "--disable-dev-shm-usage",
  "--disable-gpu",
  "--hide-scrollbars",
  "--force-device-scale-factor=1",
  `--window-size=${viewport.width},${viewport.height}`,
  pageUrl
], { stdio: "ignore" });

try {
  const target = await waitForTarget();
  const cdp = await connect(target.webSocketDebuggerUrl);
  await cdp.send("Page.enable");
  await cdp.send("Runtime.enable");
  await cdp.send("Emulation.setDeviceMetricsOverride", {
    ...viewport,
    deviceScaleFactor: 1,
    mobile: false
  });

  await navigate(cdp, `${pageUrl}/#companyWorkspace`);
  await waitForProduct(cdp);
  await captureViewport(cdp, "readme-product-overview.png");

  await activateWorkspace(cdp, "evidence");
  await cdp.send("Runtime.evaluate", {
    awaitPromise: true,
    returnByValue: true,
    expression: `
      (() => {
        const button = document.querySelector("#searchButton");
        if (!button) throw new Error("Evidence search button is missing");
        button.click();
      })()
    `
  });
  await waitForEvidence(cdp);
  await captureViewport(cdp, "readme-evidence-workspace.png");

  await cdp.close();
} finally {
  browser?.kill("SIGTERM");
}

async function waitForTarget() {
  for (let i = 0; i < 160; i++) {
    try {
      const response = await fetch(`${baseUrl}/json`);
      const targets = await response.json();
      const target = targets.find(item => item.type === "page");
      if (target?.webSocketDebuggerUrl) {
        return target;
      }
    } catch {
      // Chrome is still starting.
    }
    await delay(100);
  }
  throw new Error("Chrome DevTools target was not ready");
}

async function connect(url) {
  const ws = new WebSocket(url);
  await new Promise((resolve, reject) => {
    ws.addEventListener("open", resolve, { once: true });
    ws.addEventListener("error", reject, { once: true });
  });
  let id = 0;
  const pending = new Map();
  const listeners = new Map();
  ws.addEventListener("message", event => {
    const message = JSON.parse(event.data);
    if (message.id && pending.has(message.id)) {
      const { resolve, reject } = pending.get(message.id);
      pending.delete(message.id);
      message.error ? reject(new Error(message.error.message)) : resolve(message.result || {});
      return;
    }
    const handlers = listeners.get(message.method) || [];
    handlers.forEach(handler => handler(message.params || {}));
  });
  return {
    send(method, params = {}) {
      const messageId = ++id;
      ws.send(JSON.stringify({ id: messageId, method, params }));
      return new Promise((resolve, reject) => pending.set(messageId, { resolve, reject }));
    },
    once(method) {
      return new Promise(resolve => {
        const handler = params => {
          listeners.set(method, (listeners.get(method) || []).filter(item => item !== handler));
          resolve(params);
        };
        listeners.set(method, [...(listeners.get(method) || []), handler]);
      });
    },
    close() {
      ws.close();
    }
  };
}

async function waitForLoad(cdp) {
  await Promise.race([
    cdp.once("Page.loadEventFired"),
    delay(5000)
  ]);
}

async function navigate(cdp, url) {
  await cdp.send("Page.navigate", { url });
  await waitForLoad(cdp);
}

async function waitForProduct(cdp) {
  for (let attempt = 0; attempt < 160; attempt += 1) {
    const result = await cdp.send("Runtime.evaluate", {
      returnByValue: true,
      expression: `
        (() => {
          const quote = document.querySelector("#marketQuote")?.textContent || "";
          const stats = document.querySelector("#chartStats")?.textContent || "";
          const metrics = document.querySelector("#healthList")?.textContent || "";
          return !quote.includes("加载中")
            && stats.includes("区间涨跌")
            && metrics.trim().length > 0;
        })()
      `
    });
    if (result.result?.value) {
      await delay(700);
      return;
    }
    await delay(100);
  }
  throw new Error("FinSight product data was not ready");
}

async function activateWorkspace(cdp, workspace) {
  await cdp.send("Runtime.evaluate", {
    awaitPromise: true,
    returnByValue: true,
    expression: `
      (() => {
        const link = document.querySelector(${JSON.stringify(`[data-workspace="${workspace}"]`)});
        if (!link) throw new Error(${JSON.stringify(`Workspace link not found: ${workspace}`)});
        link.click();
      })()
    `
  });
  await delay(500);
}

async function waitForEvidence(cdp) {
  for (let attempt = 0; attempt < 80; attempt += 1) {
    const result = await cdp.send("Runtime.evaluate", {
      returnByValue: true,
      expression: `
        !document.querySelector("#sourcePanel")?.hidden
        && document.querySelectorAll("#retrievalList .source-result").length > 0
      `
    });
    if (result.result?.value) {
      await delay(500);
      return;
    }
    await delay(100);
  }
  throw new Error("Evidence results were not ready. Run ./scripts/quick-demo.sh first.");
}

async function captureViewport(cdp, filename) {
  await captureClip(cdp, filename, 0, 0, viewport.width, viewport.height);
}

async function captureClip(cdp, filename, x, y, width, height) {
  const screenshot = await cdp.send("Page.captureScreenshot", {
    format: "png",
    fromSurface: true,
    captureBeyondViewport: true,
    clip: { x, y, width, height, scale: 1 }
  });
  await writeFile(new URL(filename, outputDir), Buffer.from(screenshot.data, "base64"));
  console.log(`wrote docs/${filename}`);
}
