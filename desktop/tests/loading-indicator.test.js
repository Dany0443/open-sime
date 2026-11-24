import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";
import vm from "node:vm";

const read = name => fs.readFileSync(new URL(`../dist-tauri/${name}`, import.meta.url), "utf8");

test("Startup: one compact progress treatment replaces the competing Canvas animations", () => {
  const html = read("index.html");
  assert.ok(!html.includes("<canvas"), "Startup must not compete with multiple Canvas animations");
  assert.equal((html.match(/role="progressbar"/g) || []).length, 1);
  assert.ok(html.includes('class="startup-brand"'));
  assert.ok(!html.includes("loading-indicator.js"));
  assert.ok(!/<script>|style=/.test(html), "Keep the strict CSP");
});

test("Startup: the loading surface shares the application colors and supports reduced motion", () => {
  const css = read("loader.css");
  assert.ok(css.includes("#fbf8ff"));
  assert.ok(css.includes("#121318"));
  assert.ok(css.includes("prefers-reduced-motion"));
  assert.ok(!css.includes("blur("));
});


test("Startup: terminal sidecar errors stop progress and expose the actionable error", () => {
  let stopped = 0;
  const card = {hidden: true, classList: {add() {}}};
  const nodes = {"status-card": card, "status-title": {}, "status-detail": {}, "status-code": {hidden: true}};
  const window = {location: {search: ""}, __simeUpdate: {stop() { stopped++; }}};
  vm.runInNewContext(read("loader.js"), {window, document: {getElementById: id => nodes[id]}, URLSearchParams});
  window.__showSimeError("SIDECAR_SPAWN_FAILED", "Controlled failure");
  assert.equal(stopped, 1);
  assert.equal(card.hidden, false);
  assert.equal(nodes["status-detail"].textContent, "Controlled failure");
  assert.equal(nodes["status-code"].textContent, "SIDECAR_SPAWN_FAILED");
});
