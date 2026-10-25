import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

test("S5: GPA card is explicitly labeled as estimată", () => {
  const appJs = fs.readFileSync(path.join(__dirname, "../public/app.js"), "utf-8");
  const stringsJs = fs.readFileSync(path.join(__dirname, "../public/strings.js"), "utf-8");

  // Strings dictionary must define general average estimated in ro, ru, en
  assert.ok(stringsJs.includes("Media generală (estimată)"), "ro strings must include Media generală (estimată)");
  assert.ok(stringsJs.includes("Средний балл (расчётный)") || stringsJs.includes("расчётный"), "ru strings must include estimated gpa");
  assert.ok(stringsJs.includes("General GPA (estimated)") || stringsJs.includes("estimated"), "en strings must include estimated gpa");

  // GPA metric card in app.js must render the estimated label
  assert.ok(
    appJs.includes('Media generală (estimată)') || appJs.includes('general_average_estimated'),
    "app.js renderMetrics must label GPA as estimată"
  );
});

test("S5: Homework short month abbreviation uses nov to avoid ambiguity with noi (new)", () => {
  const appJs = fs.readFileSync(path.join(__dirname, "../public/app.js"), "utf-8");

  // Verify MONTHS_SHORT does not use "noi" for November
  const monthsShortMatch = appJs.match(/const MONTHS_SHORT = \[(.*?)\];/);
  assert.ok(monthsShortMatch, "Must find MONTHS_SHORT declaration");
  assert.ok(
    monthsShortMatch[1].includes('"nov"') || monthsShortMatch[1].includes("'nov'"),
    "MONTHS_SHORT must use 'nov' instead of 'noi' for November"
  );
});

test("S5: recentMarks and Ultima notă preserve qualifier marks (FB, B, S, I)", () => {
  const appJs = fs.readFileSync(path.join(__dirname, "../public/app.js"), "utf-8");

  // recentMarks must not drop marks when value is null but kind is qualifier or label is present
  const recentMarksMatch = appJs.match(/function recentMarks\([\s\S]*?\{([\s\S]*?)\n\}/);
  assert.ok(recentMarksMatch, "recentMarks must exist");
  const recentBody = recentMarksMatch[1];

  // Must retain qualifier marks
  assert.ok(
    recentBody.includes("qualifier") || recentBody.includes("QUALIFIER_NAMES") || recentBody.includes("label"),
    "recentMarks must handle qualifier marks and not drop value == null qualifiers"
  );

  // In renderMetrics, Ultima notă must handle qualifier without formatting null/NaN with fmtMark
  const renderMetricsMatch = appJs.match(/function renderMetrics\([\s\S]*?\{([\s\S]*?)\n\}/);
  assert.ok(renderMetricsMatch, "renderMetrics must exist");
  const metricsBody = renderMetricsMatch[1];
  assert.ok(
    metricsBody.includes("bandOf(last.value, last.label)") || metricsBody.includes("last.label") || metricsBody.includes("last.kind"),
    "renderMetrics must pass label or qualifier to bandOf / cookie formatting"
  );

  // In renderRecent, score must be called with mark object (r)
  const renderRecentMatch = appJs.match(/function renderRecent\([\s\S]*?\{([\s\S]*?)\n\}/);
  assert.ok(renderRecentMatch, "renderRecent must exist");
  const recentRenderBody = renderRecentMatch[1];
  assert.ok(
    recentRenderBody.includes("score(r,") || recentRenderBody.includes("score(r)"),
    "renderRecent must pass row object r to score()"
  );
});
