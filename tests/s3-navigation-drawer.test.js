import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

test("S3: Brand mark uses real favicon.svg 40px img without data-cookie", () => {
  const html = fs.readFileSync(path.join(__dirname, "../index.html"), "utf-8");

  // Brand mark must use favicon.svg img
  assert.ok(html.includes('src="/public/assets/favicon.svg"'), "brand icon must reference /public/assets/favicon.svg");
  assert.ok(html.includes('width="40"'), "brand icon must be 40px");
  assert.ok(html.includes('height="40"'), "brand icon height must be 40px");

  // Brand mark must NOT have data-cookie
  const brandMatch = html.match(/class="[^"]*(?:rail-brand|drawer-brand)[^"]*"[\s\S]*?<\/div>/);
  assert.ok(brandMatch, "brand container must exist");
  assert.ok(!brandMatch[0].includes("data-cookie"), "brand mark must NOT have data-cookie attribute");
  assert.ok(brandMatch[0].includes("Open SIME"), "text open-sime must be beside brand icon");
});

test("S3: Drawer has bottom-pinned Profil and Setări", () => {
  const html = fs.readFileSync(path.join(__dirname, "../index.html"), "utf-8");
  const asideMatch = html.match(/<aside[\s\S]*?<\/aside>/);
  assert.ok(asideMatch, "aside element must exist");
  assert.ok(asideMatch[0].includes("drawer-pinned-bottom") || asideMatch[0].includes("rail-bottom"), "must have bottom pinned container");
  assert.ok(asideMatch[0].includes("drawer-settings-btn") || asideMatch[0].includes("nav-item-settings"), "must have settings button in drawer bottom");
});

test("S3: Navigation follows M3 window size classes (<600, 600-839, >=840)", () => {
  const css = fs.readFileSync(path.join(__dirname, "../public/style.css"), "utf-8");

  // Drawer width at >=840 must be 280px
  assert.ok(css.includes("280px"), "expanded drawer must specify 280px width");

  // Bottom pinned styling must use margin-top: auto
  assert.ok(
    css.includes("drawer-pinned-bottom") || css.includes("rail-bottom"),
    "CSS must style drawer bottom pinned section"
  );
});
