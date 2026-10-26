import assert from "node:assert/strict";
import fs from "node:fs";
import http from "node:http";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import {
  app,
  encryptRememberToken,
  decryptRememberToken,
  sessions,
  SimeClient,
  SimeStudentNotFoundError,
  SimeSessionExpiredError,
} from "../server.js";
import {
  createPortal,
  loggedInClient,
  PORTAL_URL,
  SESSION,
  STUDENTS,
  withPortalServer,
} from "./helpers/mock-portal.js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const today = new Intl.DateTimeFormat("en-CA", { timeZone: "Europe/Chisinau" }).format(new Date());

const keyOf = (login, tag) => login.students.find((s) => s.fullName === STUDENTS[tag].name)?.id;

async function readTab(get, tab, key) {
  switch (tab) {
    case "home": {
      const r = await get("/api/overview", key);
      return { r, facts: { name: r.body.data?.student?.fullName, cls: r.body.data?.student?.className, master: r.body.data?.metadata?.classMaster } };
    }
    case "marks": {
      const r = await get("/api/catalog", key);
      const d = r.body.data?.disciplines?.[0];
      return { r, facts: { subject: d?.subject, marks: d?.marks } };
    }
    case "timetable": {
      const r = await get(`/api/schedule?date=${today}`, key);
      return { r, facts: { lessons: (r.body.data?.days?.[today] || []).map((l) => l.subject).sort() } };
    }
    case "absences": {
      const r = await get("/api/absences", key);
      return { r, facts: { absences: (r.body.data?.records || []).map((x) => x.subject) } };
    }
    case "homework": {
      const r = await get("/api/homework", key);
      return { r, facts: { homework: (r.body.data?.homework || []).map((x) => x.taskDescription) } };
    }
    case "profile": {
      const t = await get("/api/teachers", key);
      const p = await get("/api/profile", key);
      return {
        r: t,
        facts: { teachers: (t.body.data?.teachers || []).map((x) => x.name).sort(), master: p.body.data?.metadata?.classMaster, cls: p.body.data?.metadata?.className },
        extra: [p],
      };
    }
    default:
      throw new Error(tab);
  }
}

function expectedFacts(tag, tab) {
  const s = STUDENTS[tag];
  switch (tab) {
    case "home": return { name: s.name, cls: s.cls, master: s.master };
    case "marks": return { subject: s.lessonSubject, marks: [...s.marks1.split(", "), ...s.marks2.split(", ")].map(Number) };
    case "timetable": return { lessons: [s.absenceSubject, s.lessonSubject].sort() };
    case "absences": return { absences: [s.absenceSubject] };
    case "homework": return { homework: [s.hw] };
    case "profile": return { teachers: [s.master, s.teacher].sort(), master: s.master, cls: s.cls };
    default: throw new Error(tab);
  }
}

const TABS = ["home", "marks", "timetable", "absences", "homework", "profile"];

async function assertTabFor(ctx, tag, tab) {
  const key = keyOf(ctx.login, tag);
  const { r, facts, extra = [] } = await readTab(ctx.get, tab, key);
  for (const x of [r, ...extra]) {
    assert.equal(x.status, 200, `${tab} for ${tag}: ${JSON.stringify(x.body)}`);
    assert.equal(x.body.studentKey, key, `${tab} for ${tag} must carry studentKey ${key}`);
  }
  assert.deepEqual(facts, expectedFacts(tag, tab), `${tab} must show only ${tag}'s data`);
}

// 1. MULTI-STUDENT ISOLATION ACROSS TABS

test("Auth & Isolation: A -> B -> A -> B across all tabs isolates per-student data (cold & warm cache)", async () => {
  const portal = createPortal();
  await withPortalServer(portal, async (ctx) => {
    assert.ok(keyOf(ctx.login, "A") && keyOf(ctx.login, "B"), "login must list both children");
    for (const pass of ["cold", "warm"]) {
      for (const tag of ["A", "B", "A", "B"]) {
        for (const tab of TABS) {
          await assertTabFor(ctx, tag, tab).catch((e) => {
            e.message = `[${pass}] ${e.message}`;
            throw e;
          });
        }
      }
    }
  });
});

test("Auth & Isolation: Concurrent requests with delayed reordered answers stay isolated", async () => {
  const portal = createPortal({
    delayFor: ({ ajax, current }) => (ajax.includes("CALAJAX") ? (current === "A" ? 60 : 5) : 0),
  });
  await withPortalServer(portal, async (ctx) => {
    const [a, b] = [keyOf(ctx.login, "A"), keyOf(ctx.login, "B")];
    const results = await Promise.all([
      ctx.get("/api/absences", a), ctx.get("/api/absences", b),
      ctx.get("/api/teachers", b), ctx.get("/api/teachers", a),
      ctx.get(`/api/schedule?date=${today}`, a), ctx.get(`/api/schedule?date=${today}`, b),
    ]);
    const subj = (r) => (r.body.data.records || []).map((x) => x.subject);
    assert.deepEqual(subj(results[0]), [STUDENTS.A.absenceSubject]);
    assert.deepEqual(subj(results[1]), [STUDENTS.B.absenceSubject]);
    assert.ok(results[2].body.data.teachers.some((t) => t.name === STUDENTS.B.master));
    assert.ok(!results[2].body.data.teachers.some((t) => t.name.startsWith("Alpha")));
    assert.ok(results[3].body.data.teachers.some((t) => t.name === STUDENTS.A.master));
    assert.ok(!results[3].body.data.teachers.some((t) => t.name.startsWith("Beta")));
    assert.ok(results[4].body.data.days[today].some((l) => l.subject === STUDENTS.A.lessonSubject));
    assert.ok(results[5].body.data.days[today].some((l) => l.subject === STUDENTS.B.lessonSubject));
  });
});

test("Auth & Isolation: Portal switching refusal returns STUDENT_MISMATCH and student routes require explicit student", async () => {
  const portal = createPortal({ stuck: true });
  await withPortalServer(portal, async (ctx) => {
    const b = keyOf(ctx.login, "B");
    const sw = await fetch(`${ctx.base}/api/student/switch`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${ctx.token}` },
      body: JSON.stringify({ studentId: b }),
    });
    assert.ok(sw.status === 409 || sw.status === 502);
    const swBody = await sw.json();
    assert.equal(swBody.error?.code, "STUDENT_MISMATCH");

    const unknown = await ctx.get("/api/overview", "NONEXISTENT_ID");
    assert.equal(unknown.status, 404);
  });
});

function makeSingleStudentPortal(tag = "A") {
  const portal = createPortal();
  const s = STUDENTS[tag];
  const studentPage = (
    `<input id="pFlowId" value="104"><input id="pFlowStepId" value="1"><input id="pSalt" value="SALT"><input id="pPageItemsProtected" value="PROT">` +
    `<input id="P1_ELEVE_ID" value="${s.pid}"><input type="hidden" data-for="P1_ELEVE_ID" value="CK_${s.pid}">` +
    `<input id="P1_ELEVE" value="${s.name}"><input id="P1_CLASS_NAME" value="${s.cls}">` +
    `<input id="P1_INSTITUTION_NAME" value="${s.school}"><input id="P1_DIRIGINTE" value="${s.master}">` +
    `<input id="P1_MOTIVATED" value="0"><input id="P1_UNMOTIVATED" value="1">` +
    `<input id="P1_HOMEWORK" value="${s.hw}">` +
    `<script>apex.widget.fullCalendar({"x":1,"ajaxIdentifier":"CALAJAX"});` +
    `apex.widget.report.init("sem1","SEM1AJAX",{});apex.widget.report.init("sem2","SEM2AJAX",{});</script>`
  );

  portal.request = async (method, url, opts = {}) => {
    if (method === "POST") {
      const ajax = String(opts.data?.p_request || "");
      if (ajax.includes("CALAJAX")) {
        return {
          status: 200,
          url,
          text: JSON.stringify([
            { start: `${today}T08:00:00`, end: `${today}T08:45:00`, title: `<span class="lesson-text-wrap">${s.lessonSubject}</span><span class="lesson-teacher">${s.teacher}</span>` },
            { start: `${today}T09:00:00`, end: `${today}T09:45:00`, title: `<span class="lesson-text-wrap">${s.absenceSubject}</span><span class="lesson-teacher">${s.teacher}</span>` },
          ]),
        };
      }
      if (ajax.includes("SEM1AJAX")) {
        return {
          status: 200,
          url,
          text: `<table><tr><td headers="DISCIPLINA">${s.lessonSubject}</td><td headers="LISTA_EVALUATION">${s.marks1}</td></tr></table>`,
        };
      }
      if (ajax.includes("SEM2AJAX")) {
        return { status: 200, url, text: `<table><tr><td headers="DISCIPLINA">${s.lessonSubject}</td><td headers="LISTA_EVALUATION">${s.marks2}</td></tr></table>` };
      }
      return { status: 200, url, text: "" };
    }
    return { status: 200, url, text: studentPage };
  };

  return portal;
}

test("Auth & Isolation: Student role rejects switching and maintains teachers in single-child mode", async () => {
  const portal = makeSingleStudentPortal("A");
  const orig = SimeClient.prototype.loginAndParsePortal;
  SimeClient.prototype.loginAndParsePortal = async function () {
    this.auth.sessionId = SESSION;
    this.auth.portalUrl = PORTAL_URL;
    this.http.request = portal.request;
    const page = await this.fetchDashboard(PORTAL_URL, { bypassCache: true });
    this.registerStudents(page);
    const data = await this.enrichPortalPage(page);
    return { html: page.html, data };
  };

  const server = http.createServer(app);
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  const base = `http://127.0.0.1:${server.address().port}`;

  try {
    const loginRes = await fetch(`${base}/api/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: "student1", password: "pw", role: "student" }),
    });
    assert.equal(loginRes.status, 200);
    const login = await loginRes.json();
    const auth = { Authorization: `Bearer ${login.token}` };

    const swRes = await fetch(`${base}/api/student/switch`, {
      method: "POST",
      headers: { ...auth, "Content-Type": "application/json" },
      body: JSON.stringify({ studentId: STUDENTS.B.nav }),
    });
    assert.equal(swRes.status, 400);

    const overview = await (await fetch(`${base}/api/overview`, { headers: auth })).json();
    assert.equal(overview.data.student.fullName, STUDENTS.A.name);

    const teachers = await (await fetch(`${base}/api/teachers`, { headers: auth })).json();
    assert.ok(teachers.data.teachers.length >= 2, "Full teacher list must load in student mode");
    assert.ok(teachers.data.teachers.some((t) => t.note === "Diriginte"));
  } finally {
    SimeClient.prototype.loginAndParsePortal = orig;
    await new Promise((r) => server.close(r));
  }
});

// 2. SECURE REMEMBER-ME, COOKIE AUDIT & ROTATION

test("Auth & Isolation: Browser storage audit verifies no passwords or tokens in browser storage", () => {
  const appJs = fs.readFileSync(path.join(__dirname, "../public/app.js"), "utf8");
  const html = fs.readFileSync(path.join(__dirname, "../index.html"), "utf8");

  assert.ok(!appJs.match(/localStorage\.setItem\([^)]*password/i), "localStorage must not store password");
  assert.ok(!appJs.match(/sessionStorage\.setItem\([^)]*password/i), "sessionStorage must not store password");
  assert.ok(!appJs.match(/store\.set\([^)]*password/i), "store helper must not store password");
  assert.ok(!appJs.match(/document\.cookie\s*=\s*[^;]*password/i), "document.cookie must not store password");

  assert.ok(!appJs.match(/store\.set\([^)]*sime_remember/i), "Client must not store sime_remember");
  assert.ok(!appJs.match(/localStorage\.setItem\([^)]*sime_remember/i), "Client must not store sime_remember");

  const checkboxMatch = html.match(/<input[^>]*id="auth-remember"[^>]*>/i);
  assert.ok(checkboxMatch, "Remember-me checkbox must exist in index.html");
  assert.ok(!checkboxMatch[0].includes("checked"), "Remember-me checkbox must NOT be checked by default");
  assert.ok(html.includes('id="forget-device-btn"'), "forget-device-btn must exist in settings dialog");
});

test("Auth & Isolation: Token security, resume single-use rotation, expiration and revocation", async () => {
  const portal = createPortal();
  const orig = SimeClient.prototype.loginAndParsePortal;
  SimeClient.prototype.loginAndParsePortal = async function () {
    this.auth.sessionId = SESSION;
    this.auth.portalUrl = PORTAL_URL;
    this.http.request = portal.request;
    const page = await this.fetchDashboard(PORTAL_URL, { bypassCache: true });
    this.registerStudents(page);
    const data = await this.enrichPortalPage(page);
    return { html: page.html, data };
  };

  const server = http.createServer(app);
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  const base = `http://127.0.0.1:${server.address().port}`;

  try {
    // 1. Login with remember=true
    const loginRes = await fetch(`${base}/api/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: "student1", password: "pw", role: "student", remember: true }),
    });
    assert.equal(loginRes.status, 200);
    const cookie1 = loginRes.headers.get("set-cookie");
    assert.ok(cookie1.includes("sime_remember="));
    assert.ok(cookie1.includes("HttpOnly"));
    assert.ok(/SameSite=Lax/i.test(cookie1));
    assert.ok(cookie1.includes("Path=/api/auth"));

    const token1 = decodeURIComponent(cookie1.match(/sime_remember=([^;]+)/)[1]);
    assert.ok(token1.length >= 32);
    assert.ok(token1.split(".").length < 3, "Token must be opaque, not client-side ciphertext");

    // 2. Resume session with token1 -> should succeed and issue rotated token2
    const resumeRes1 = await fetch(`${base}/api/auth/resume`, {
      method: "POST",
      headers: {
        Cookie: `sime_remember=${encodeURIComponent(token1)}`,
        Origin: base,
      },
    });
    assert.equal(resumeRes1.status, 200);
    const resumeBody1 = await resumeRes1.json();
    assert.ok(resumeBody1.token);
    assert.equal(resumeBody1.role, "student");

    const cookie2 = resumeRes1.headers.get("set-cookie");
    assert.ok(cookie2, "Resume must rotate cookie");
    const token2 = decodeURIComponent(cookie2.match(/sime_remember=([^;]+)/)[1]);
    assert.notEqual(token1, token2, "Token must rotate on resume");

    // 3. Replay with old token1 must fail with 401
    const replayRes = await fetch(`${base}/api/auth/resume`, {
      method: "POST",
      headers: {
        Cookie: `sime_remember=${encodeURIComponent(token1)}`,
        Origin: base,
      },
    });
    assert.equal(replayRes.status, 401, "Replaying rotated token must fail");

    // 4. Token2 succeeds
    const resumeRes2 = await fetch(`${base}/api/auth/resume`, {
      method: "POST",
      headers: {
        Cookie: `sime_remember=${encodeURIComponent(token2)}`,
        Origin: base,
      },
    });
    assert.equal(resumeRes2.status, 200);
    const token3 = decodeURIComponent(resumeRes2.headers.get("set-cookie").match(/sime_remember=([^;]+)/)[1]);

    // 5. Revocation via forget-device
    const forgetRes = await fetch(`${base}/api/auth/forget-device`, {
      method: "POST",
      headers: {
        Cookie: `sime_remember=${encodeURIComponent(token3)}`,
        Origin: base,
      },
    });
    assert.equal(forgetRes.status, 200);
    const clearCookie = forgetRes.headers.get("set-cookie");
    assert.ok(clearCookie && clearCookie.includes("Max-Age=0"));

    // 6. Resume with revoked token fails
    const postRevokeRes = await fetch(`${base}/api/auth/resume`, {
      method: "POST",
      headers: {
        Cookie: `sime_remember=${encodeURIComponent(token3)}`,
        Origin: base,
      },
    });
    assert.equal(postRevokeRes.status, 401);
  } finally {
    SimeClient.prototype.loginAndParsePortal = orig;
    await new Promise((r) => server.close(r));
  }
});

test("Auth & Isolation: Origin enforcement blocks cross-site resume requests with 403", async () => {
  const portal = createPortal();
  const orig = SimeClient.prototype.loginAndParsePortal;
  SimeClient.prototype.loginAndParsePortal = async function () {
    this.auth.sessionId = SESSION;
    this.auth.portalUrl = PORTAL_URL;
    this.http.request = portal.request;
    const page = await this.fetchDashboard(PORTAL_URL, { bypassCache: true });
    this.registerStudents(page);
    const data = await this.enrichPortalPage(page);
    return { html: page.html, data };
  };

  const server = http.createServer(app);
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  const base = `http://127.0.0.1:${server.address().port}`;

  try {
    const loginRes = await fetch(`${base}/api/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: "student1", password: "pw", role: "student", remember: true }),
    });
    const cookie = loginRes.headers.get("set-cookie");
    const token = decodeURIComponent(cookie.match(/sime_remember=([^;]+)/)[1]);

    const crossOriginRes = await fetch(`${base}/api/auth/resume`, {
      method: "POST",
      headers: {
        Cookie: `sime_remember=${encodeURIComponent(token)}`,
        Origin: "https://evil-attacker.example",
      },
    });
    assert.equal(crossOriginRes.status, 403, "Cross-origin resume must be blocked with 403");

    const crossSiteRes = await fetch(`${base}/api/auth/resume`, {
      method: "POST",
      headers: {
        Cookie: `sime_remember=${encodeURIComponent(token)}`,
        Origin: base,
        "Sec-Fetch-Site": "cross-site",
      },
    });
    assert.equal(crossSiteRes.status, 403, "Sec-Fetch-Site cross-site must be blocked with 403");
  } finally {
    SimeClient.prototype.loginAndParsePortal = orig;
    await new Promise((r) => server.close(r));
  }
});

test("Auth & Isolation: AES-256-GCM remember secret encryption roundtrip", () => {
  const secret = "a-secure-master-key-32-characters";
  const creds = { username: "student_user", password: "secret_password", role: "student" };
  const encrypted = encryptRememberToken(creds, secret);
  assert.ok(encrypted && typeof encrypted === "string");
  assert.equal(encrypted.split(".").length, 3);

  const decrypted = decryptRememberToken(encrypted, secret);
  assert.deepEqual(decrypted, creds);

  assert.equal(decryptRememberToken(encrypted, "wrong-key"), null);
});

// 3. RESTORED CONSOLIDATION TESTS: STUDENT ROLE, SWITCHING, PERSISTENCE & CACHING

test("Student Role: regression test for every tab without ?student= parameter", async () => {
  const portal = makeSingleStudentPortal("A");
  const orig = SimeClient.prototype.loginAndParsePortal;
  SimeClient.prototype.loginAndParsePortal = async function () {
    this.auth.sessionId = SESSION;
    this.auth.portalUrl = PORTAL_URL;
    this.http.request = portal.request;
    const page = await this.fetchDashboard(PORTAL_URL, { bypassCache: true });
    this.registerStudents(page);
    const data = await this.enrichPortalPage(page);
    return { html: page.html, data };
  };

  const server = http.createServer(app);
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  const base = `http://127.0.0.1:${server.address().port}`;

  try {
    const res = await fetch(`${base}/api/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: "student1", password: "pw", role: "student" }),
    });
    const login = await res.json();
    assert.equal(res.status, 200);
    assert.equal(login.role, "student");
    const auth = { Authorization: `Bearer ${login.token}` };

    const get = async (path) => {
      const r = await fetch(`${base}${path}`, { headers: auth });
      return { status: r.status, body: await r.json() };
    };

    assert.equal((await get("/api/overview")).status, 200);
    assert.equal((await get("/api/catalog")).status, 200);
    assert.equal((await get(`/api/schedule?date=${today}`)).status, 200);
    assert.equal((await get("/api/absences")).status, 200);
    assert.equal((await get("/api/homework")).status, 200);
    assert.equal((await get("/api/teachers")).status, 200);
    assert.equal((await get("/api/profile")).status, 200);
  } finally {
    SimeClient.prototype.loginAndParsePortal = orig;
    await new Promise((r) => server.close(r));
  }
});

test("Single Child: parent mode with exactly one child works across every tab without ?student= parameter", async () => {
  const portal = makeSingleStudentPortal("A");
  const orig = SimeClient.prototype.loginAndParsePortal;
  SimeClient.prototype.loginAndParsePortal = async function () {
    this.auth.sessionId = SESSION;
    this.auth.portalUrl = PORTAL_URL;
    this.http.request = portal.request;
    const page = await this.fetchDashboard(PORTAL_URL, { bypassCache: true });
    this.registerStudents(page);
    const data = await this.enrichPortalPage(page);
    return { html: page.html, data };
  };

  const server = http.createServer(app);
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  const base = `http://127.0.0.1:${server.address().port}`;

  try {
    const res = await fetch(`${base}/api/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: "parent1", password: "pw", role: "parent" }),
    });
    const login = await res.json();
    assert.equal(res.status, 200);
    assert.equal(login.role, "parent");
    assert.equal(login.students.length, 1);
    const auth = { Authorization: `Bearer ${login.token}` };

    const get = async (path) => {
      const r = await fetch(`${base}${path}`, { headers: auth });
      return { status: r.status, body: await r.json() };
    };

    assert.equal((await get("/api/overview")).status, 200);
    assert.equal((await get("/api/catalog")).status, 200);
    assert.equal((await get(`/api/schedule?date=${today}`)).status, 200);
    assert.equal((await get("/api/absences")).status, 200);
    assert.equal((await get("/api/homework")).status, 200);
    assert.equal((await get("/api/teachers")).status, 200);
    assert.equal((await get("/api/profile")).status, 200);
  } finally {
    SimeClient.prototype.loginAndParsePortal = orig;
    await new Promise((r) => server.close(r));
  }
});

test("Student Switching: switching back and forth never emits checksum-less URLs and handles switches cleanly", async () => {
  const requestedUrls = [];
  const client = new SimeClient("testparent", "pass", "parent");
  client.auth.sessionId = "777888";
  client.auth.portalUrl = "https://agenda.sime.md/ords/r/agenda/main/dashboard?session=777888";

  client.http.request = async (method, url) => {
    requestedUrls.push(url);
    if (url.includes("CS_ELENA")) {
      return { status: 200, url, text: '<input id="P1_ELEVE_ID" value="202"><input id="P1_ELEVE" value="Elena Ciobanu"><div id="t_TreeNav"><ul><li><a href="/ords/f?p=AGENDA:1:555666::NO::P1_ELEVE_ID:5B1E59D6A1&cs=CS_ION">Ion</a></li><li data-current="true"><a href="/ords/f?p=AGENDA:1:555666::NO::P1_ELEVE_ID:8F7E6D5C4B&cs=CS_ELENA">Elena</a></li></ul></div>' };
    }
    return { status: 200, url, text: '<input id="P1_ELEVE_ID" value="101"><input id="P1_ELEVE" value="Ion Ciobanu"><div id="t_TreeNav"><ul><li data-current="true"><a href="/ords/f?p=AGENDA:1:555666::NO::P1_ELEVE_ID:5B1E59D6A1&cs=CS_ION">Ion</a></li><li><a href="/ords/f?p=AGENDA:1:555666::NO::P1_ELEVE_ID:8F7E6D5C4B&cs=CS_ELENA">Elena</a></li></ul></div>' };
  };

  await client.loadPortal();

  requestedUrls.length = 0;
  const elenaData = await client.switchStudent("8F7E6D5C4B");
  assert.equal(elenaData.student.fullName, "Elena Ciobanu");
  assert.ok(requestedUrls.length > 0);
  for (const u of requestedUrls) {
    assert.ok(u.includes("&cs="), `URL must include checksum: ${u}`);
  }

  requestedUrls.length = 0;
  const ionData = await client.switchStudent("5B1E59D6A1");
  assert.equal(ionData.student.fullName, "Ion Ciobanu");
  for (const u of requestedUrls) {
    assert.ok(u.includes("&cs="), `URL must include checksum: ${u}`);
  }
});

test("Student Switching: failing switch throws clear student error, never session expired", async () => {
  const client = new SimeClient("testparent", "pass", "parent");
  client.auth.sessionId = "777888";
  client.auth.portalUrl = "https://agenda.sime.md/ords/r/agenda/main/dashboard?session=777888";
  client.http.request = async (method, url) => ({ status: 200, url, text: '<input id="P1_ELEVE_ID" value="101"><input id="P1_ELEVE" value="Ion Ciobanu"><div id="t_TreeNav"><ul><li data-current="true"><a href="/ords/f?p=AGENDA:1:555666::NO::P1_ELEVE_ID:5B1E59D6A1&cs=CS_ION">Ion</a></li></ul></div>' });

  await client.loadPortal();

  await assert.rejects(
    () => client.switchStudent("NONEXISTENT_ID"),
    (err) => err instanceof SimeStudentNotFoundError && !(err instanceof SimeSessionExpiredError)
  );
});

test("Student Isolation: portal global current student moves, A is re-asserted before AJAX", async () => {
  const portal = createPortal();
  await withPortalServer(portal, async (ctx) => {
    const a = keyOf(ctx.login, "A");
    await assertTabFor(ctx, "B", "absences");
    assert.equal(portal.current, "B");
    const before = portal.requests.length;
    await assertTabFor(ctx, "A", "absences");
    const sent = portal.requests.slice(before);
    const switchToA = sent.findIndex((r) => r.method === "GET" && r.url.includes("P1_ELEVE_ID:5B1E59D6A1"));
    const calendar = sent.findIndex((r) => r.ajax.includes("CALAJAX"));
    assert.ok(switchToA !== -1 && switchToA < calendar, "A must be re-asserted as portal-current before calendar AJAX");
    assert.ok(a);
  });
});

test("Student Isolation: class label for second child available without opening their tabs", async () => {
  await withPortalServer(createPortal(), async (ctx) => {
    const [a, b] = [keyOf(ctx.login, "A"), keyOf(ctx.login, "B")];
    assert.equal(ctx.login.students.find((s) => s.id === a).className, "11R");
    const p = await ctx.get("/api/profile", b);
    assert.equal(p.status, 200);
    assert.equal(p.body.students.find((s) => s.id === b).className, "8B");
    assert.equal(p.body.students.find((s) => s.id === a).className, "11R");
  });
});

test("Student Isolation: logout drops every cache of the account", async () => {
  await withPortalServer(createPortal(), async (ctx) => {
    const b = keyOf(ctx.login, "B");
    await ctx.get("/api/absences", b);
    const session = ctx.session;
    assert.ok(session.cache.size > 0);
    const res = await fetch(`${ctx.base}/api/auth/logout`, { method: "POST", headers: { Authorization: `Bearer ${ctx.token}` } });
    assert.equal(res.status, 204);
    assert.equal(session.cache.size, 0);
    assert.deepEqual(Object.keys(session.students), []);
    assert.equal(session.client.studentPages.size, 0);
  });
});

test("Auth & Isolation: logout without active session still revokes remember cookie and prevents silent resume", async () => {
  const orig = SimeClient.prototype.loginAndParsePortal;
  SimeClient.prototype.loginAndParsePortal = async function () {
    this.auth.sessionId = SESSION;
    this.auth.portalUrl = PORTAL_URL;
    const portal = createPortal();
    this.http.request = portal.request;
    const page = await this.fetchDashboard(PORTAL_URL, { bypassCache: true });
    this.registerStudents(page);
    const data = await this.enrichPortalPage(page);
    return { html: page.html, data };
  };

  const server = http.createServer(app);
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  const base = `http://127.0.0.1:${server.address().port}`;

  try {
    const loginRes = await fetch(`${base}/api/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: "student1", password: "pw", role: "student", remember: true }),
    });
    assert.equal(loginRes.status, 200);
    const cookie = loginRes.headers.get("set-cookie");
    assert.ok(cookie?.includes("sime_remember="));
    const token = decodeURIComponent(cookie.match(/sime_remember=([^;]+)/)[1]);

    const logoutRes = await fetch(`${base}/api/auth/logout`, {
      method: "POST",
      headers: {
        Authorization: "Bearer invalid_expired_token_1234567890",
        Cookie: `sime_remember=${encodeURIComponent(token)}`,
        Origin: base,
      },
    });
    assert.equal(logoutRes.status, 204);
    const clearCookie = logoutRes.headers.get("set-cookie");
    assert.ok(clearCookie?.includes("Max-Age=0"), "Logout must clear remember cookie");

    const resumeRes = await fetch(`${base}/api/auth/resume`, {
      method: "POST",
      headers: {
        Cookie: `sime_remember=${encodeURIComponent(token)}`,
        Origin: base,
      },
    });
    assert.equal(resumeRes.status, 401);
  } finally {
    SimeClient.prototype.loginAndParsePortal = orig;
    await new Promise((r) => server.close(r));
  }
});

test("Student Role: server rejects student role switching with 400 and preserves role in responses", async () => {
  const portal = makeSingleStudentPortal("A");
  const orig = SimeClient.prototype.loginAndParsePortal;
  SimeClient.prototype.loginAndParsePortal = async function () {
    this.auth.sessionId = SESSION;
    this.auth.portalUrl = PORTAL_URL;
    this.http.request = portal.request;
    const page = await this.fetchDashboard(PORTAL_URL, { bypassCache: true });
    this.registerStudents(page);
    const data = await this.enrichPortalPage(page);
    return { html: page.html, data };
  };

  const server = http.createServer(app);
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  const base = `http://127.0.0.1:${server.address().port}`;

  try {
    const res = await fetch(`${base}/api/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: "student1", password: "pw", role: "student" }),
    });
    const login = await res.json();
    assert.equal(res.status, 200);
    assert.equal(login.role, "student");
    const auth = { Authorization: `Bearer ${login.token}` };

    const switchRes = await fetch(`${base}/api/student/switch`, {
      method: "POST",
      headers: { ...auth, "Content-Type": "application/json" },
      body: JSON.stringify({ studentId: "101" }),
    });
    assert.equal(switchRes.status, 400);
    const body = await switchRes.json();
    assert.equal(body.error?.code, "INVALID_REQUEST");
  } finally {
    SimeClient.prototype.loginAndParsePortal = orig;
    await new Promise((r) => server.close(r));
  }
});
