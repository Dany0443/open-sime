import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import { app } from "../server.js";

test("W12: diffNewMarks detects new marks only", async () => {
  // Read diffNewMarks logic or verify pure function
  const appJs = fs.readFileSync(new URL("../public/app.js", import.meta.url), "utf-8");
  assert.ok(appJs.includes("diffNewMarks"), "public/app.js must define diffNewMarks function");

  // Extract and evaluate markKey and diffNewMarks in Node
  const match = appJs.match(/function markKey\([\s\S]*?function diffNewMarks\([\s\S]*?\n\}/);
  assert.ok(match, "diffNewMarks must be findable");
  const fn = new Function(`${match[0]}; return diffNewMarks;`)();

  const prevCatalog = [
    {
      subject: "Matematică",
      semesters: [
        { semester: 1, marks: [{ value: 9, date: "2026-09-15" }] },
        { semester: 2, marks: [] },
      ],
    },
  ];

  const currentCatalog = [
    {
      subject: "Matematică",
      semesters: [
        {
          semester: 1,
          marks: [
            { value: 9, date: "2026-09-15" },
            { value: 10, date: "2026-10-02" }, // NEW
          ],
        },
        { semester: 2, marks: [] },
      ],
    },
    {
      subject: "Fizică", // NEW subject
      semesters: [
        { semester: 1, marks: [{ value: 8, date: "2026-09-20" }] },
      ],
    },
  ];

  fn(currentCatalog, prevCatalog);

  const mathSem1 = currentCatalog[0].semesters[0].marks;
  assert.equal(mathSem1[0].isNew, false);
  assert.equal(mathSem1[1].isNew, true);

  const physicsSem1 = currentCatalog[1].semesters[0].marks;
  assert.equal(physicsSem1[0].isNew, true);
});

test("W12: public/manifest.json exists and is linked in index.html", () => {
  const manifestRaw = fs.readFileSync(new URL("../public/manifest.json", import.meta.url), "utf-8");
  const manifest = JSON.parse(manifestRaw);
  assert.equal(manifest.name, "open-sime");
  assert.ok(manifest.start_url);
  assert.ok(Array.isArray(manifest.icons) && manifest.icons.length > 0);

  const indexHtml = fs.readFileSync(new URL("../index.html", import.meta.url), "utf-8");
  assert.ok(indexHtml.includes('rel="manifest"'), "index.html must link web manifest");
});

test("W12: service worker public/sw.js never caches /api/auth and caches shell", () => {
  const swCode = fs.readFileSync(new URL("../public/sw.js", import.meta.url), "utf-8");
  assert.ok(swCode.includes("/api/auth"), "Must explicitly mention /api/auth exclusion");
  assert.ok(swCode.includes("style.css"), "Must cache style.css");
  assert.ok(swCode.includes("app.js"), "Must cache app.js");
  assert.ok(swCode.includes("strings.js"), "Must cache strings.js");
});

test("W12: server serves /sw.js with Service-Worker-Allowed header", async () => {
  const server = await new Promise((resolve) => {
    const s = app.listen(0, "127.0.0.1", () => resolve(s));
  });
  const port = server.address().port;
  try {
    const res = await fetch(`http://127.0.0.1:${port}/sw.js`);
    assert.equal(res.status, 200);
    assert.ok(res.headers.get("service-worker-allowed") === "/");
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});
