import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";
import vm from "node:vm";
const path = new URL("../public/desktop-startup.js", import.meta.url);
function load({reduced = false, theme = null} = {}) {
  const frames = [];const timers = [];const classes = new Set();
  const splash = {hidden: false, classList: {add: x => classes.add(x)}, addEventListener() {}};
  const document = {documentElement: {dataset: {}}, getElementById: () => splash};
  const window = {__TAURI_INTERNALS__: {}, localStorage: {getItem: key => key === "open-sime.theme" ? theme : null}, matchMedia: () => ({matches: reduced}), requestAnimationFrame: cb => frames.push(cb), setTimeout: cb => timers.push(cb)};
  vm.runInNewContext(fs.readFileSync(path, "utf8"), {window, document});
  return {window, document, splash, classes, frames, timers};
}

test("Desktop startup: stays covered until explicitly ready and reveals the painted application", () => {
  const ui = load({theme: "dark"});
  assert.equal(ui.document.documentElement.dataset.theme, "dark");
  assert.equal(ui.splash.hidden, false);
  assert.equal(ui.frames.length, 0);
  ui.window.__simeStartup.reveal();ui.window.__simeStartup.reveal();
  assert.equal(ui.frames.length, 1, "Only one transition can run");
  ui.frames.shift()();assert.equal(ui.classes.has("is-ready"), false);
  ui.frames.shift()();assert.ok(ui.classes.has("is-ready"));
  ui.timers.shift()();assert.equal(ui.splash.hidden, true);
});

test("Desktop startup: reduced motion reveals immediately after the ready frame", () => {
  const ui = load({reduced: true});ui.window.__simeStartup.reveal();
  ui.frames.shift()();ui.frames.shift()();
  assert.equal(ui.splash.hidden, true);
  assert.equal(ui.classes.has("is-ready"), false);
  assert.equal(ui.timers.length, 0);
});

test("Desktop startup: standalone web does not install a desktop loading transition", () => {
  const window = {};
  vm.runInNewContext(fs.readFileSync(path, "utf8"), {window});
  assert.equal(window.__simeStartup, undefined);
});

test("Desktop startup: blocked storage still uses the system theme", () => {
  const document = {documentElement: {dataset: {}}};
  const window = {__TAURI_INTERNALS__: {}, localStorage: {getItem() { throw new Error("Unavailable"); }}, matchMedia: () => ({matches: true})};
  vm.runInNewContext(fs.readFileSync(path, "utf8"), {window, document});
  assert.equal(document.documentElement.dataset.theme, "dark");
  assert.equal(typeof window.__simeStartup.reveal, "function");
});
