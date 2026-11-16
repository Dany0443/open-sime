import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import vm from "node:vm";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DIST = path.resolve(__dirname, "..", "dist-tauri");
const read = (name) => fs.readFileSync(path.join(DIST, name), "utf8");

function createDom({ language = "ro", reducedMotion = false, dpr = 2 } = {}) {
  const calls = {
    cancelled: [],
    frames: 0,
    strokes: 0,
    fills: 0,
    arcs: 0,
    strokeStyles: [],
    fillStyles: [],
    lastFrame: null,
  };

  const ctx = {
    clearRect() {},
    beginPath() {},
    moveTo() {},
    lineTo() {},
    arc() {
      calls.arcs += 1;
    },
    stroke() {
      calls.strokes += 1;
      calls.strokeStyles.push(this.strokeStyle);
    },
    fill() {
      calls.fills += 1;
      calls.fillStyles.push(this.fillStyle);
    },
  };

  const canvas = {
    width: 0,
    height: 0,
    clientWidth: 280,
    clientHeight: 16,
    getContext: () => ctx,
  };
  const attributes = new Map();
  const bar = {
    setAttribute(name, value) {
      attributes.set(name, String(value));
    },
    removeAttribute(name) {
      attributes.delete(name);
    },
    getAttribute(name) {
      return attributes.has(name) ? attributes.get(name) : null;
    },
  };
  const classes = new Set();
  const area = {
    classList: {
      add(name) {
        classes.add(name);
      },
      remove(name) {
        classes.delete(name);
      },
      contains(name) {
        return classes.has(name);
      },
    },
  };
  const caption = { textContent: "" };
  const elements = {
    "update-canvas": canvas,
    "update-bar": bar,
    "update-area": area,
    "update-caption": caption,
  };
  const window = {
    devicePixelRatio: dpr,
    matchMedia: (query) => ({
      matches: query.indexOf("reduced-motion") !== -1 ? reducedMotion : false,
      addEventListener() {},
    }),
    addEventListener() {},
    requestAnimationFrame(callback) {
      calls.frames += 1;
      calls.lastFrame = callback;
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
    navigator: { language },
    getComputedStyle: () => ({
      getPropertyValue: (name) => (name === "--on-primary-container" ? " #00105c " : " #dee0ff "),
    }),
  });

  return { context, window, bar, area, caption, calls, classes };
}

test("Startup Screen: update slot is reserved and hidden until Rust reveals it", () => {
  const html = read("index.html");
  assert.ok(html.includes('id="update-area"'), "Update area must exist in the loader markup");
  assert.ok(html.includes('id="update-bar"'), "Update bar must exist in the loader markup");
  assert.ok(html.includes('id="update-canvas"'), "Update canvas must exist in the loader markup");
  assert.ok(html.includes('id="update-caption"'), "Update caption must exist in the loader markup");
  assert.ok(html.includes('aria-valuemin="0"'), "Update bar must expose aria-valuemin");
  assert.ok(html.includes('aria-valuemax="100"'), "Update bar must expose aria-valuemax");
  assert.ok(!/<script>|style=/.test(html), "No inline scripts or styles (CSP)");
  assert.ok(
    html.indexOf("loading-indicator.js") < html.indexOf("update-bar.js"),
    "Indicator script loads before update-bar.js",
  );
  assert.ok(
    html.indexOf("update-bar.js") < html.indexOf("loader.js"),
    "Update bar script loads before loader.js",
  );

  const css = read("loader.css").replace(/\s+/g, " ");
  assert.ok(/\.shell \{[^}]*flex-direction: column/.test(css), "Loader shell must be a column");
  assert.ok(
    /\.update \{[^}]*visibility: hidden/.test(css),
    "Update slot must be reserved with visibility, not display",
  );
  assert.ok(css.includes("width: min(280px, 70vw)"), "Bar width must be bounded");
  assert.ok(css.includes("height: 16px"), "Bar slot height must be reserved");
});

test("Update bar: checking, progress, installing, hide and fail drive the block", () => {
  const dom = createDom();
  vm.runInContext(read("update-bar.js"), dom.context);
  const api = dom.window.__simeUpdate;
  assert.ok(api, "window.__simeUpdate must be exposed");
  assert.equal(dom.classes.has("is-visible"), false, "Block stays hidden until Rust calls it");

  api.checking();
  assert.ok(dom.classes.has("is-visible"));
  assert.equal(dom.caption.textContent, "Se caută actualizări…");
  assert.equal(dom.bar.getAttribute("aria-label"), "Se caută actualizări…");
  assert.equal(dom.bar.getAttribute("aria-valuenow"), null, "Checking is indeterminate");
  const framesAfterChecking = dom.calls.frames;

  api.checking();
  assert.equal(dom.calls.frames, framesAfterChecking, "Repeated state calls are idempotent");

  api.progress(50, 200);
  assert.equal(dom.bar.getAttribute("aria-valuenow"), "25");
  assert.equal(dom.caption.textContent, "Se descarcă actualizarea…");

  api.installing();
  assert.equal(dom.bar.getAttribute("aria-valuenow"), "100");
  assert.equal(dom.caption.textContent, "Se instalează…");

  api.fail();
  assert.equal(dom.classes.has("is-visible"), false, "A failed update hides the block");
  assert.equal(dom.bar.getAttribute("aria-valuenow"), null);
  assert.ok(dom.calls.cancelled.length > 0, "Animation stops on failure");
});

test("Update bar: progress ratio clamps and tolerates unknown totals", () => {
  const dom = createDom();
  vm.runInContext(read("update-bar.js"), dom.context);
  const { progressRatio } = dom.window.__simeUpdateGeometry;

  assert.equal(progressRatio(0, 100), 0);
  assert.equal(progressRatio(50, 100), 0.5);
  assert.equal(progressRatio(100, 100), 1);
  assert.equal(progressRatio(150, 100), 1, "received > total clamps to 100%");
  assert.equal(progressRatio(-5, 100), 0, "negative received clamps to 0%");
  assert.equal(progressRatio(10, 0), null, "unknown total stays indeterminate");
  assert.equal(progressRatio(10, -1), null);
  assert.equal(progressRatio(10, NaN), null);
  assert.equal(progressRatio(10, Infinity), null);
  assert.equal(progressRatio(NaN, 100), null);
});

test("Update bar: unknown total keeps the bar indeterminate", () => {
  const dom = createDom();
  vm.runInContext(read("update-bar.js"), dom.context);
  dom.window.__simeUpdate.progress(1024, 0);
  assert.ok(dom.classes.has("is-visible"));
  assert.equal(dom.bar.getAttribute("aria-valuenow"), null);
  assert.equal(dom.caption.textContent, "Se descarcă actualizarea…");
});

test("Update bar: aria-valuenow is refreshed every few percent, not every chunk", () => {
  const dom = createDom();
  vm.runInContext(read("update-bar.js"), dom.context);
  const originalSet = dom.bar.setAttribute.bind(dom.bar);
  let valueWrites = 0;
  dom.bar.setAttribute = (name, value) => {
    if (name === "aria-valuenow") valueWrites += 1;
    originalSet(name, value);
  };

  for (let received = 0; received <= 1000; received += 1) {
    dom.window.__simeUpdate.progress(received, 1000);
  }

  assert.ok(valueWrites >= 10, `progress must still be announced, got ${valueWrites} updates`);
  assert.ok(valueWrites <= 40, `aria updates must be sparse, got ${valueWrites} updates`);
  assert.equal(dom.bar.getAttribute("aria-valuenow"), "100");
});

test("Update bar: drawing math keeps the wave inside the bar", () => {
  const dom = createDom();
  vm.runInContext(read("update-bar.js"), dom.context);
  const { barGeometry, activeSegment, wavePoints } = dom.window.__simeUpdateGeometry;

  const geometry = barGeometry(560, 32);
  assert.ok(geometry.thickness > 0);
  assert.ok(geometry.amplitude > 0);
  assert.ok(
    geometry.amplitude + geometry.thickness / 2 <= 16,
    "wave plus stroke must fit inside half the bar height",
  );

  const points = wavePoints(0, 560, geometry, Math.PI / 3);
  assert.ok(points.length > 10);
  assert.equal(points[points.length - 1].x, 560);
  for (const point of points) {
    assert.ok(point.y <= geometry.amplitude + 1e-9, "wave stays below the top edge");
    assert.ok(point.y >= -geometry.amplitude - 1e-9, "wave stays above the bottom edge");
  }

  const determinate = activeSegment(560, 0.5, 0);
  assert.equal(determinate.start, 0);
  assert.equal(determinate.end, 280);

  const sweepStart = activeSegment(560, null, 0);
  const sweepEnd = activeSegment(560, null, 1);
  assert.ok(sweepStart.end - sweepStart.start > 0);
  assert.ok(sweepEnd.start > sweepStart.start, "indeterminate wave travels");
});

test("Update bar: reduced motion draws a static bar and never animates", () => {
  const dom = createDom({ reducedMotion: true });
  vm.runInContext(read("update-bar.js"), dom.context);

  dom.window.__simeUpdate.checking();
  assert.equal(dom.calls.frames, 0, "No animation frames under reduced motion");
  assert.ok(dom.calls.strokes > 0, "A static bar must still be drawn");

  const before = dom.calls.strokes;
  dom.window.__simeUpdate.progress(10, 100);
  assert.ok(dom.calls.strokes > before, "A determinate state redraws once");
  assert.equal(dom.calls.frames, 0);
});

test("Update bar: strings follow navigator.language with Romanian fallback", () => {
  const cases = [
    ["ro-RO", "Se caută actualizări…"],
    ["ru-RU", "Поиск обновлений…"],
    ["en-GB", "Checking for updates…"],
    ["de-DE", "Se caută actualizări…"],
  ];
  for (const [language, expected] of cases) {
    const dom = createDom({ language });
    vm.runInContext(read("update-bar.js"), dom.context);
    dom.window.__simeUpdate.checking();
    assert.equal(dom.caption.textContent, expected, language);
  }
});

test("Update bar: colors come from the loader design tokens", () => {
  const dom = createDom();
  vm.runInContext(read("update-bar.js"), dom.context);
  dom.window.__simeUpdate.progress(500, 1000);

  assert.ok(dom.calls.strokeStyles.includes("#00105c"), "Active wave uses --on-primary-container");
  assert.ok(dom.calls.strokeStyles.includes("#dee0ff"), "Track uses --primary-container");
  assert.ok(dom.calls.fillStyles.includes("#00105c"), "End dot uses --on-primary-container");
});
