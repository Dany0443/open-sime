import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

test("S4: Unified button tokens and variants in style.css", () => {
  const css = fs.readFileSync(path.join(__dirname, "../public/style.css"), "utf-8");

  // .btn declaration
  const btnMatch = css.match(/\.btn\s*\{[^}]+\}/);
  assert.ok(btnMatch, "Must declare .btn");
  assert.ok(btnMatch[0].includes("min-height: 48px") || btnMatch[0].includes("min-height:48px"), ".btn must have min-height 48px");
  assert.ok(btnMatch[0].includes("var(--shape-full)"), ".btn must have fully rounded shape");

  // Variants
  assert.ok(css.includes(".btn-filled"), "Must have .btn-filled variant");
  assert.ok(css.includes(".btn-tonal"), "Must have .btn-tonal variant");
  assert.ok(css.includes(".btn-outlined"), "Must have .btn-outlined variant");
  assert.ok(css.includes(".text-btn"), "Must have .text-btn variant");
  assert.ok(css.includes(".icon-btn"), "Must have .icon-btn variant");

  // .icon-btn 48x48px
  const iconBtnMatch = css.match(/\.icon-btn\s*\{[^}]+\}/);
  assert.ok(iconBtnMatch[0].includes("min-width: 48px") || iconBtnMatch[0].includes("width: 48px"), "icon-btn min-width 48px");
  assert.ok(iconBtnMatch[0].includes("min-height: 48px") || iconBtnMatch[0].includes("height: 48px"), "icon-btn min-height 48px");

  // Segmented control min-height 48px
  const segBtnMatch = css.match(/\.seg-btn\s*\{[^}]+\}/);
  assert.ok(segBtnMatch, "Must find .seg-btn");
  assert.ok(segBtnMatch[0].includes("min-height: 48px") || segBtnMatch[0].includes("min-height:48px") || segBtnMatch[0].includes("height: 48px"), "seg-btn must have min-height 48px");

  // Student chips min-height 48px
  const chipMatch = css.match(/\.student-chip\s*\{[^}]+\}/);
  assert.ok(chipMatch, "Must find .student-chip");
  assert.ok(chipMatch[0].includes("min-height: 48px") || chipMatch[0].includes("min-height:48px") || chipMatch[0].includes("height: 48px"), "student-chip must have min-height 48px");

  // Focus-visible ring
  assert.ok(css.includes(":focus-visible"), "Must have focus-visible styling");
  assert.ok(css.includes("3px") || css.includes("4px"), "Focus ring must be at least 3px");
});

test("S4: Expressive states and reduced-motion protection", () => {
  const css = fs.readFileSync(path.join(__dirname, "../public/style.css"), "utf-8");

  // Pressed state with expressive shape morph
  assert.ok(css.includes(".btn:active") || css.includes(".btn:active::before"), "Must style button active state");
  assert.ok(css.includes("var(--ease-spring)"), "Must use spring easing for shape morph");

  // Reduced motion
  const reducedMotionMatch = css.match(/@media\s*\(prefers-reduced-motion:\s*reduce\)[\s\S]*?\}/);
  assert.ok(reducedMotionMatch, "Must have prefers-reduced-motion media query");
});
