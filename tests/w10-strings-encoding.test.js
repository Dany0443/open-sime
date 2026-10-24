import assert from "node:assert/strict";
import fs from "node:fs";
import http from "node:http";
import path from "node:path";
import { test } from "node:test";
import { app } from "../server.js";
import { getString, STRINGS } from "../public/strings.js";

test("W10: codebase contains no U+FFFD or letter-?-letter corrupted diacritic patterns", () => {
  const allFiles = [
    "public/app.js",
    "public/strings.js",
    "public/style.css",
    "index.html",
    "server.js",
  ];

  for (const relPath of allFiles) {
    const fullPath = path.resolve(relPath);
    if (!fs.existsSync(fullPath)) continue;
    const content = fs.readFileSync(fullPath, "utf8");

    // 1. Check for U+FFFD (replacement character) in all files
    assert.ok(
      !content.includes("\uFFFD"),
      `File ${relPath} contains U+FFFD replacement character`
    );
  }

  // 2. Check for letter-?-letter corrupted diacritic patterns in UI files
  const uiFiles = ["public/app.js", "public/strings.js", "index.html"];
  for (const relPath of uiFiles) {
    const content = fs.readFileSync(path.resolve(relPath), "utf8");
    const stringLiteralRegex = /"([^"\\]*)"|'([^'\\]*)'|`([^`\\]*)`/g;
    let strMatch;
    while ((strMatch = stringLiteralRegex.exec(content)) !== null) {
      const literal = strMatch[1] || strMatch[2] || strMatch[3] || "";
      if (literal.startsWith("http") || literal.startsWith("?") || literal.includes("?=") || literal.includes("f?p=")) {
        continue;
      }
      if (/[a-zA-ZăâîșțĂÂÎȘȚ]\?[a-zA-ZăâîșțĂÂÎȘȚ]|\b[a-zA-ZăâîșțĂÂÎȘȚ]+\?(?:[\s.,!]|$)/.test(literal)) {
        assert.fail(`File ${relPath} contains corrupted diacritic in string literal: "${literal}"`);
      }
    }
  }
});

test("W10: public/strings.js provides complete ro, ru, en dictionaries and interpolation", () => {
  assert.ok(STRINGS.ro, "Romanian dictionary must exist");
  assert.ok(STRINGS.ru, "Russian dictionary must exist");
  assert.ok(STRINGS.en, "English dictionary must exist");

  const roKeys = Object.keys(STRINGS.ro);
  assert.ok(roKeys.length >= 30, "Must have substantial string coverage");

  for (const k of roKeys) {
    assert.ok(STRINGS.ru[k], `Russian dictionary missing key: ${k}`);
    assert.ok(STRINGS.en[k], `English dictionary missing key: ${k}`);
  }

  // Test getString helper with interpolation and fallback
  assert.equal(
    getString("today", {}, "ro"),
    "Astăzi"
  );
  assert.equal(
    getString("today", {}, "ru"),
    "Сегодня"
  );
  assert.equal(
    getString("today", {}, "en"),
    "Today"
  );

  assert.equal(
    getString("break_text", { time: "10 min" }, "ro"),
    "Pauză 10 min"
  );
  assert.equal(
    getString("break_text", { time: "10 мин" }, "ru"),
    "Перемена 10 мин"
  );
});

test("W10: server responses return UTF-8 in Content-Type header", async () => {
  const server = http.createServer(app);
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const port = server.address().port;
  const baseUrl = `http://127.0.0.1:${port}`;

  try {
    const endpoints = [
      { path: "/", expectedType: "text/html; charset=utf-8" },
      { path: "/public/app.js", expectedType: "application/javascript; charset=utf-8" },
      { path: "/public/strings.js", expectedType: "application/javascript; charset=utf-8" },
      { path: "/public/style.css", expectedType: "text/css; charset=utf-8" },
    ];

    for (const ep of endpoints) {
      const res = await fetch(`${baseUrl}${ep.path}`);
      assert.equal(res.status, 200, `Endpoint ${ep.path} must return 200`);
      const contentType = res.headers.get("content-type") || "";
      assert.ok(
        contentType.toLowerCase().includes("utf-8"),
        `Endpoint ${ep.path} Content-Type "${contentType}" must include charset=utf-8`
      );
    }
  } finally {
    server.close();
  }
});
