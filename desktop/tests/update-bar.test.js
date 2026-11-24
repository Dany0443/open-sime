import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";
import vm from "node:vm";

const code = fs.readFileSync(new URL("../dist-tauri/update-bar.js", import.meta.url), "utf8");
function load(language = "ro") {
  const attributes = new Map();
  const properties = new Map();
  const bar = {setAttribute: (k, v) => attributes.set(k, v), removeAttribute: k => attributes.delete(k)};
  const area = {hidden: false, classList: {toggle() {}}};
  const caption = {textContent: ""};
  const nodes = {"update-bar": bar, "update-fill": {style: {setProperty: (k, v) => properties.set(k, v)}}, "update-area": area, "update-caption": caption};
  const context = {window: {}, navigator: {language}, document: {getElementById: id => nodes[id], addEventListener() {}}};
  vm.runInNewContext(code, context);
  return {api: context.window.__simeUpdate, attributes, properties, area, caption};
}

test("Update bar: startup and checking are indeterminate, never arbitrary percentages", () => {
  const {api, attributes, caption} = load();
  assert.equal(attributes.get("data-mode"), "indeterminate");
  assert.equal(attributes.get("aria-valuenow"), undefined);
  api.checking();
  assert.equal(caption.textContent, "Se caută actualizări…");
  assert.equal(attributes.get("aria-valuenow"), undefined);
});

test("Update bar: download and installation represent real progress", () => {
  const {api, attributes, properties, caption} = load();
  api.progress(50, 200);
  assert.equal(attributes.get("aria-valuenow"), "25");
  assert.equal(properties.get("--progress"), "0.25");
  assert.equal(caption.textContent, "Se descarcă actualizarea…");
  api.installing();
  assert.equal(attributes.get("aria-valuenow"), "100");
  assert.equal(properties.get("--progress"), "1");
  assert.equal(caption.textContent, "Se instalează…");
});

test("Update bar: invalid totals remain indeterminate and valid ratios clamp", () => {
  const {api, attributes, properties} = load();
  for (const [received, total] of [[1, 0], [1, -1], [NaN, 100], [1, Infinity]]) {
    api.progress(received, total);
    assert.equal(attributes.get("aria-valuenow"), undefined);
  }
  api.progress(-1, 100); assert.equal(properties.get("--progress"), "0");
  api.progress(150, 100); assert.equal(properties.get("--progress"), "1");
});

test("Update bar: update failure continues opening, terminal startup errors stop progress", () => {
  const {api, attributes, area, caption} = load();
  api.progress(50, 100); api.fail();
  assert.equal(area.hidden, false);
  assert.equal(caption.textContent, "");
  assert.equal(attributes.get("aria-valuenow"), undefined);
  api.stop(); assert.equal(area.hidden, true);
});

test("Update bar: localization retains Romanian fallback and real update labels", () => {
  for (const [language, label] of [["ru-RU", "Поиск обновлений…"], ["en-US", "Checking for updates…"], ["de", "Se caută actualizări…"]]) {
    const {api, caption} = load(language);api.checking();assert.equal(caption.textContent, label);
  }
});

test("Update bar: no Canvas or JavaScript animation loop runs during scrolling", () => {
  assert.ok(!code.includes("requestAnimationFrame"));
  assert.ok(!code.includes("getContext"));
  assert.ok(!code.includes("clientWidth"));
  const css = fs.readFileSync(new URL("../dist-tauri/loader.css", import.meta.url), "utf8");
  assert.ok(css.includes("prefers-reduced-motion"));
  assert.ok(css.includes("animation-play-state: paused"));
});

test("Update bar: accessibility announcements are sparse while every real chunk updates the fill", () => {
  const {api, attributes, properties} = load();
  api.progress(50, 100);
  api.progress(51, 100);
  assert.equal(attributes.get("aria-valuenow"), "50");
  assert.equal(properties.get("--progress"), "0.51");
  api.progress(53, 100);
  assert.equal(attributes.get("aria-valuenow"), "53");
  api.progress(99, 100);
  api.progress(100, 100);
  assert.equal(attributes.get("aria-valuenow"), "100");
  api.hide();
  assert.equal(attributes.get("data-mode"), "indeterminate");
  assert.equal(attributes.get("aria-valuenow"), undefined);
});
