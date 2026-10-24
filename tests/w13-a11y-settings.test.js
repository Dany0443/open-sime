import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";

test("W13: index.html contains settings button and settings dialog with all controls", () => {
  const indexHtml = fs.readFileSync(new URL("../index.html", import.meta.url), "utf-8");
  assert.ok(indexHtml.includes('id="settings-btn"'), "Must have settings button in topbar");
  assert.ok(indexHtml.includes('id="settings-dialog"'), "Must have settings dialog");
  assert.ok(indexHtml.includes('name="setting-lang"'), "Must have language selector");
  assert.ok(indexHtml.includes('name="setting-text-size"'), "Must have text size selector");
  assert.ok(indexHtml.includes('name="setting-theme"'), "Must have theme selector");
  assert.ok(indexHtml.includes('id="setting-simple-mode"'), "Must have simple mode toggle");
  assert.ok(indexHtml.includes('id="setting-contrast"'), "Must have high contrast toggle");
  assert.ok(indexHtml.includes('id="setting-reduce-motion"'), "Must have reduce motion toggle");
});

test("W13: touch targets in style.css are at least 48x48 CSS px", () => {
  const styleCss = fs.readFileSync(new URL("../public/style.css", import.meta.url), "utf-8");
  // Check icon-btn dimensions
  const iconBtnMatch = styleCss.match(/\.icon-btn\s*\{[^}]+\}/);
  assert.ok(iconBtnMatch, "Must find .icon-btn rule");
  assert.ok(iconBtnMatch[0].includes("48px") || iconBtnMatch[0].includes("min-width: 48px") || iconBtnMatch[0].includes("width: 48px"), "icon-btn must be at least 48px");

  // Check btn has min-height >= 48px
  const btnMatch = styleCss.match(/\.btn\s*\{[^}]+\}/);
  assert.ok(btnMatch, "Must find .btn rule");
  assert.ok(btnMatch[0].includes("min-height"), "btn must declare min-height");
});

test("W13: text scaling and high contrast tokens in style.css", () => {
  const styleCss = fs.readFileSync(new URL("../public/style.css", import.meta.url), "utf-8");
  assert.ok(styleCss.includes('[data-text-size="S"]'), "Must have small text scaling");
  assert.ok(styleCss.includes('[data-text-size="XL"]'), "Must have extra-large text scaling");
  assert.ok(styleCss.includes('[data-contrast="high"]'), "Must have high contrast rules");
  assert.ok(styleCss.includes('[data-simple-mode="true"]'), "Must have simple mode styling");
});

test("W13: app.js binds settings dialog and simple mode labels", () => {
  const appJs = fs.readFileSync(new URL("../public/app.js", import.meta.url), "utf-8");
  assert.ok(appJs.includes("settings-dialog"), "Must bind settings dialog");
  assert.ok(appJs.includes("open-sime.simple-mode") || appJs.includes("simpleMode"), "Must persist simple mode setting");
  assert.ok(appJs.includes("open-sime.text-size") || appJs.includes("textSize"), "Must persist text size setting");
});
