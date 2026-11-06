import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import vm from "node:vm";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DIST = path.resolve(__dirname, "..", "dist-tauri");
const read = (name) => fs.readFileSync(path.join(DIST, name), "utf8");

function createDom() {
  const calls = { fill: 0, cancelled: [], frames: 0 };
  const ctx = {
    clearRect() {},
    beginPath() {},
    moveTo() {},
    lineTo() {},
    closePath() {},
    fill() {
      calls.fill += 1;
    },
  };
  const canvas = { width: 0, height: 0, clientWidth: 100, getContext: () => ctx };
  const holder = { hidden: false };
  const card = { hidden: true, classList: { add() {} } };
  const elements = { "loading-canvas": canvas, "loading-indicator": holder, "status-card": card };
  const window = {
    location: { search: "" },
    devicePixelRatio: 1,
    matchMedia: () => ({ matches: false, addEventListener() {} }),
    addEventListener() {},
    requestAnimationFrame() {
      calls.frames += 1;
      return calls.frames;
    },
    cancelAnimationFrame(id) {
      calls.cancelled.push(id);
    },
  };
  const document = {
    getElementById: (id) => elements[id] || null,
    documentElement: {},
  };
  const context = vm.createContext({
    window,
    document,
    URLSearchParams,
    getComputedStyle: () => ({ getPropertyValue: () => " #00105c " }),
  });
  return { context, window, canvas, holder, card, calls };
}

test("Startup Screen: old server-starting text is gone and the shape indicator replaces it", () => {
  const html = read("index.html");
  assert.ok(!/serviciul local|conexiunea securizat/i.test(html), "Old startup message must be removed");
  assert.ok(html.includes('id="loading-canvas"'), "Indicator canvas must exist");
  assert.ok(html.includes('role="progressbar"'), "Indicator must be exposed as a progressbar");
  assert.ok(!/<script>|style=/.test(html), "No inline scripts or styles (CSP)");
  assert.ok(html.indexOf("loading-indicator.js") < html.indexOf("loader.js"), "Indicator script loads before loader.js");
});

test("Startup Screen: indicator animates continuously and stops when an error is shown", () => {
  const { context, window, holder, card, calls } = createDom();
  vm.runInContext(read("loading-indicator.js"), context);
  assert.equal(calls.frames, 1, "Animation must schedule a frame on start");
  assert.ok(window.__simeLoadingIndicator, "Indicator must expose stop()");

  vm.runInContext(read("loader.js"), context);
  window.__showSimeError("SIDECAR_SPAWN_FAILED", "detail");
  assert.equal(holder.hidden, true, "Indicator must be hidden on startup error");
  assert.equal(card.hidden, false, "Error card must be shown on startup error");
  assert.ok(calls.cancelled.length > 0, "Animation frame must be cancelled on error");
});

test("Startup Screen: reduced motion draws a single static frame", () => {
  const { context, window, calls } = createDom();
  window.matchMedia = () => ({ matches: true, addEventListener() {} });
  vm.runInContext(read("loading-indicator.js"), context);
  assert.equal(calls.frames, 0, "No animation loop under reduced motion");
  assert.equal(calls.fill, 1, "One static shape must be drawn");
});
