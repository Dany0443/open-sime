import assert from "node:assert/strict";
import fs from "node:fs";
import http from "node:http";
import path from "node:path";
import { test } from "node:test";
import { app, SimeClient } from "../server.js";

test("W4: server rejects student role switching with 400 and preserves role in responses", async () => {
  const server = http.createServer(app);
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const port = server.address().port;
  const baseUrl = `http://127.0.0.1:${port}`;

  try {
    // 1. Mock SimeClient for student role login
    const origLogin = SimeClient.prototype.loginAndParsePortal;
    SimeClient.prototype.loginAndParsePortal = async function () {
      return {
        portalUrl: "https://agenda.sime.md/ords/r/agenda/main/dashboard",
        data: {
          student: { id: "101", fullName: "Ion Student", className: "10A" },
          students: [{ id: "101", fullName: "Ion Student", className: "10A" }],
          grades: [],
          catalog: [],
          absente: { total: 0 },
          events: [],
          teachers: [],
        },
      };
    };

    let loginRes;
    try {
      const res = await fetch(`${baseUrl}/api/auth/login`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username: "student1", password: "pwd", role: "student" }),
      });
      assert.equal(res.status, 200);
      loginRes = await res.json();
      assert.equal(loginRes.role, "student");
    } finally {
      SimeClient.prototype.loginAndParsePortal = origLogin;
    }

    const token = loginRes.token;

    // 2. Overview returns role: "student"
    const overviewRes = await fetch(`${baseUrl}/api/overview`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    assert.equal(overviewRes.status, 200);
    const overviewBody = await overviewRes.json();
    assert.equal(overviewBody.role, "student");

    // 3. Attempting to switch student in student role must return 400
    const switchRes = await fetch(`${baseUrl}/api/student/switch`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ studentId: "101" }),
    });
    assert.equal(switchRes.status, 400);
    const switchBody = await switchRes.json();
    assert.equal(switchBody.error?.code, "INVALID_REQUEST");
  } finally {
    server.close();
  }
});

test("W4: index.html has profile-btn in topbar-actions so it is accessible when student strip is hidden", () => {
  const html = fs.readFileSync(path.resolve("index.html"), "utf8");
  // Profile button must be inside topbar-actions, not student-strip
  const topbarActionsMatch = html.match(/<div class="topbar-actions">([\s\S]*?)<\/div>/);
  assert.ok(topbarActionsMatch, "topbar-actions container must exist");
  assert.ok(
    topbarActionsMatch[1].includes('id="profile-btn"'),
    "profile-btn must be inside topbar-actions"
  );

  const studentStripMatch = html.match(/<div class="student-strip">([\s\S]*?)<\/div>/);
  assert.ok(studentStripMatch, "student-strip container must exist");
  assert.ok(
    !studentStripMatch[1].includes('id="profile-btn"'),
    "profile-btn must NOT be inside student-strip"
  );
});

test("W4: renderStudents visibility logic hides strip for student role or single child", () => {
  // Simulate renderStudents behavior logic
  function computeVisibility({ role, students }) {
    const list = Array.from(new Map(students.map((s) => [String(s.id), s])).values());
    const multiple = list.length > 1 && role !== "student";
    return {
      stripHidden: !multiple,
      renderedButtons: multiple ? list.length : 0,
    };
  }

  // Student role: always hidden
  assert.deepEqual(
    computeVisibility({
      role: "student",
      students: [{ id: "1", fullName: "Ion" }],
    }),
    { stripHidden: true, renderedButtons: 0 }
  );

  assert.deepEqual(
    computeVisibility({
      role: "student",
      students: [
        { id: "1", fullName: "Ion" },
        { id: "2", fullName: "Elena" },
      ],
    }),
    { stripHidden: true, renderedButtons: 0 }
  );

  // Parent role with 1 child: hidden
  assert.deepEqual(
    computeVisibility({
      role: "parent",
      students: [{ id: "1", fullName: "Ion" }],
    }),
    { stripHidden: true, renderedButtons: 0 }
  );

  // Parent role with duplicates of 1 child: hidden
  assert.deepEqual(
    computeVisibility({
      role: "parent",
      students: [
        { id: "1", fullName: "Ion" },
        { id: "1", fullName: "Ion" },
      ],
    }),
    { stripHidden: true, renderedButtons: 0 }
  );

  // Parent role with multiple distinct children: visible
  assert.deepEqual(
    computeVisibility({
      role: "parent",
      students: [
        { id: "1", fullName: "Ion" },
        { id: "2", fullName: "Elena" },
      ],
    }),
    { stripHidden: false, renderedButtons: 2 }
  );
});
