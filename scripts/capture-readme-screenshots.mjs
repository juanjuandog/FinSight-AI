import { spawn } from "node:child_process";
import { mkdir, rm, writeFile } from "node:fs/promises";
import { setTimeout as delay } from "node:timers/promises";

const chrome = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const outputDir = new URL("../docs/", import.meta.url);
const userDataDir = "/private/tmp/finsight-readme-chrome";
const port = Number(process.env.PORT || 9223);
const baseUrl = `http://127.0.0.1:${port}`;
const pageUrl = "http://localhost:8080";
const connectOnly = process.env.CONNECT_ONLY === "1";

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
  "--window-size=1440,1100",
  pageUrl
], { stdio: "ignore" });

try {
  const target = await waitForTarget();
  const cdp = await connect(target.webSocketDebuggerUrl);
  await cdp.send("Page.enable");
  await cdp.send("Runtime.enable");
  await cdp.send("Emulation.setDeviceMetricsOverride", {
    width: 1440,
    height: 1100,
    deviceScaleFactor: 1,
    mobile: false
  });
  await cdp.send("Page.navigate", { url: pageUrl });
  await waitForLoad(cdp);
  await delay(2400);
  await cdp.send("Runtime.evaluate", {
    awaitPromise: true,
    returnByValue: true,
    expression: "typeof runWorkflow === 'function' ? runWorkflow() : Promise.resolve()"
  });
  await delay(800);

  await captureClip(cdp, "readme-ui-overview.png", 0, 0, 1440, 1040);
  await captureElementRange(cdp, "readme-ui-workflow-trace.png", "#openSourceProof", "#researchDesk", 1440);
  await captureElementRange(cdp, "readme-ui-evidence-quality.png", "#health", "#evidence", 1440);

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

async function captureElementRange(cdp, filename, startSelector, endSelector, width) {
  const result = await cdp.send("Runtime.evaluate", {
    awaitPromise: true,
    returnByValue: true,
    expression: `
      (() => {
        const start = document.querySelector(${JSON.stringify(startSelector)});
        const end = document.querySelector(${JSON.stringify(endSelector)});
        if (!start || !end) return null;
        const scrollY = window.scrollY;
        const first = start.getBoundingClientRect();
        const last = end.getBoundingClientRect();
        return {
          y: Math.max(0, first.top + scrollY - 28),
          height: Math.min(1120, last.bottom - first.top + 56)
        };
      })()
    `
  });
  const rect = result.result?.value;
  if (!rect) {
    throw new Error(`Could not find ${startSelector}..${endSelector}`);
  }
  await captureClip(cdp, filename, 0, rect.y, width, rect.height);
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
