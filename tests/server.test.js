import assert from "node:assert/strict";
import { test } from "node:test";
import {
  CircuitBreaker,
  RateGate,
  SimeAuth,
  SimeHttp,
  absenceRecords,
  app,
  breaker,
  buildCatalog,
  buildTeachers,
  executeHandshake,
  fingerprintHeaders,
  loginGuard,
  parseAbsenceSummary,
  parseCalendarEvents,
  parseDate,
  parseFullPortal,
  parseHomework,
  parseRecentGrades,
  parseSemesterReport,
  parseStudentMetadata,
  pickFingerprint,
  requireSimeUrl,
  resolveSimeUrl,
  sessions,
  unescapeJs,
} from "../server.js";
import * as cheerio from "cheerio";

const TODAY = "2026-10-24";

test("recent grades: primary and fallback selectors", () => {
  const html =
    '<ul><li><span class="eval-date">20.10.2026</span><span class="eval-discipline">Matematica:</span><span class="grade">9</span></li>' +
    "<li>19.10 Istoria: 8</li><li>broken</li></ul>";
  const grades = parseRecentGrades(html, 1, TODAY);
  assert.deepEqual(
    grades.map((g) => [g.subject, g.value, g.date, g.semester]),
    [
      ["Matematica", 9, "2026-10-20", 1],
      ["Istoria", 8, "2026-10-19", 1],
    ],
  );
  assert.deepEqual(parseRecentGrades("", null), []);
  assert.deepEqual(parseRecentGrades("<div><li></li", null), []);
});

test("semester report marks, dates, and catalog aggregation", () => {
  const html =
    '<table class="t-Report-report"><tr>' +
    '<td headers="DISCIPLINA">Fizica</td>' +
    '<td headers="LISTA_EVALUATION"><span title="12.10.2026">8</span>, <span title="19.10.2026">10</span></td>' +
    '<td headers="MED_SEM">9,00</td>' +
    '<td headers="PROFESOR">Ion Popescu</td>' +
    "</tr></table>";
  const report = parseSemesterReport(html);
  assert.deepEqual(report.get("Fizica"), {
    subject: "Fizica",
    marks: [
      { kind: "numeric", value: 8, label: "8", raw: "8", isThesis: false, date: "2026-10-12", semester: null },
      { kind: "numeric", value: 10, label: "10", raw: "10", isThesis: false, date: "2026-10-19", semester: null },
    ],
    semesterAverage: 9,
    teacher: "Ion Popescu",
  });

  const cat = buildCatalog([{ semester: 1, report }]);
  assert.equal(cat.generalAverage, 9);
  assert.equal(cat.subjects[0].teacher, "Ion Popescu");
  assert.equal(cat.subjects[0].markCount, 2);
  assert.deepEqual(cat.disciplines[0].marks, [8, 10]);
  assert.equal(cat.disciplines[0].sem1Average, 9);
  assert.equal(cat.disciplines[0].sem2Average, null);
});

test("homework groups by date", () => {
  const [hw] = parseHomework("<b>25.10.2026</b><ul><li><span>Fizica:</span><span>Ex. 5</span></li></ul>", TODAY);
  assert.deepEqual(hw, { subject: "Fizica", taskDescription: "Ex. 5", dueDate: "2026-10-25", isCompleted: false });
});

test("absence summary falls back on garbage", () => {
  const $ = cheerio.load('<input id="P1_MOTIVATED" value="x"><input id="P1_UNMOTIVATED2" value="3">');
  const s = parseAbsenceSummary($);
  assert.equal(s.sem1Excused, 0);
  assert.equal(s.sem2Unexcused, 3);
  assert.equal(s.presencePercent, 100);
  assert.equal(s.total, 3);
});

test("student metadata extraction", () => {
  const $ = cheerio.load(
    '<input id="P1_ELEVE_ID" value="42">' +
      '<input id="P1_ELEVE" value="Ana Moraru">' +
      '<input id="P1_CLASS_NAME" value="10A">' +
      '<input id="P1_INSTITUTION_NAME" value="Liceul Mircea Eliade">' +
      '<input id="P1_DIRIGINTE" value="Elena Rusu">',
  );
  const meta = parseStudentMetadata($);
  assert.equal(meta.id, "42");
  assert.equal(meta.fullName, "Ana Moraru");
  assert.equal(meta.className, "10A");
  assert.equal(meta.schoolName, "Liceul Mircea Eliade");
  assert.equal(meta.classMaster, "Elena Rusu");
  assert.equal(meta.enrollmentStatus, "Activ");
});

test("calendar events, teachers directory, and absence records", () => {
  const events = [
    {
      start: "2026-10-24T08:00:00",
      end: "2026-10-24T08:45:00",
      title: '<span class="lesson-text-wrap">Chimia</span><span class="lesson-room">12</span><span class="lesson-teacher">Maria Ionescu</span>',
    },
    { start: "bad", end: "x" },
    "junk",
    {
      start: "2026-10-24T09:00:00",
      end: "2026-10-24T09:45:00",
      title: '<span class="absent-nemotivat lesson-text-wrap">Fizica</span><span class="lesson-teacher">Ion Popescu</span>',
    },
  ];
  const entries = parseCalendarEvents(events);
  assert.deepEqual(entries.map((e) => e.subject), ["Chimia", "Fizica"]);
  assert.equal(entries[0].classroom, "12");
  assert.equal(entries[0].teacher, "Maria Ionescu");
  assert.equal(entries[0].isAbsence, false);
  assert.ok(entries[1].isAbsence && !entries[1].isExcused);
  assert.equal(new Date(entries[0].start).toISOString(), "2026-10-24T05:00:00.000Z");
  assert.deepEqual(parseCalendarEvents({ not: "a list" }), []);

  const teachers = buildTeachers(entries, { classMaster: "Elena Rusu" });
  assert.equal(teachers.length, 3);
  assert.equal(teachers.find((t) => t.name === "Elena Rusu").note, "Diriginte");

  const abs = absenceRecords(entries);
  assert.equal(abs.length, 1);
  assert.equal(abs[0].subject, "Fizica");
  assert.equal(abs[0].status, "nemotivat");
});

test("date parsing infers year and rejects invalid dates", () => {
  assert.equal(parseDate("19.10", TODAY), "2026-10-19");
  assert.equal(parseDate("30.12", TODAY), "2025-12-30");
  assert.equal(parseDate("31.02.2026", TODAY), null);
  assert.equal(parseDate("2026-10-01", TODAY), "2026-10-01");
});

test("unescapeJs keeps unicode", () => {
  assert.equal(unescapeJs("f?p=\\u002F\\x41 p\\u0103rinte"), "f?p=/A părinte");
});

test("URL guard", () => {
  assert.ok(resolveSimeUrl("/ords/r/agenda/main/x?session=1").startsWith("https://agenda.sime.md/"));
  for (const bad of [
    "https://evil.example/x",
    "http://agenda.sime.md/x",
    "https://agenda.sime.md.evil.example/",
    "https://agenda.sime.md@evil.example/",
    "https://agenda.sime.md:8443/",
  ]) {
    assert.throws(() => requireSimeUrl(bad), /Refused/);
  }
});

test("browser fingerprint headers and rate gate with jitter", async () => {
  const fp = pickFingerprint(() => 0);
  const navHeaders = fingerprintHeaders(fp, { kind: "navigate", hasReferer: false });
  assert.ok(navHeaders["User-Agent"].includes("Chrome/"));
  assert.ok(navHeaders["Sec-CH-UA"].includes("Chromium"));
  assert.equal(navHeaders["Sec-Fetch-Dest"], "document");
  assert.equal(navHeaders.Priority, "u=0, i");

  let clock = 1000;
  const delays = [];
  const gate = new RateGate({
    minIntervalMs: 3000,
    jitterMinMs: 200,
    jitterMaxMs: 800,
    now: () => clock,
    sleep: async (ms) => {
      delays.push(ms);
      clock += ms;
    },
    random: () => 0.5,
  });
  await gate.wait();
  await gate.wait();
  assert.equal(delays[0], 500);
  assert.equal(delays[1], 3500);
});

const redirectBody = (target) =>
  JSON.stringify({ redirectURL: `<script>location=atob("${Buffer.from(target).toString("base64")}")</script>` });
const failureBody = (text) => JSON.stringify({ redirectURL: `f?notification_msg=${Buffer.from(text).toString("base64")}` });

test("login response handling and circuit breaker", () => {
  const br = new CircuitBreaker();
  const auth = new SimeAuth("u", "p", "parent", br);
  auth.handleLoginResponse(redirectBody("/ords/r/agenda/main/main-page?session=123456"));
  assert.ok(auth.isAuthenticated);
  assert.equal(auth.sessionId, "123456");
  assert.throws(() => new SimeAuth("u", "p", "parent", br).handleLoginResponse(redirectBody("https://evil.example/?session=1")), /Refused/);

  const a2 = new SimeAuth("u", "p", "parent", br);
  assert.throws(() => a2.handleLoginResponse(failureBody("Invalid credentials")), /Invalid credentials/);
  assert.ok(a2.credentialsRejected);
  assert.throws(() => a2.handleLoginResponse(failureBody("Account blocked, wait 120 seconds")), (e) => e.waitSeconds === 120);
  assert.throws(() => br.ensureClosed(), /suspended/);
  for (const body of ["not json", "[]", "{}"]) {
    assert.throws(() => new SimeAuth("u", "p", "parent", new CircuitBreaker()).handleLoginResponse(body), /Login response/);
  }
});

test("HTTP layer refuses off-host redirects and maps statuses", async () => {
  const real = globalThis.fetch;
  const fastGate = new RateGate({ minIntervalMs: 0, jitterMinMs: 0, jitterMaxMs: 0, sleep: async () => {} });
  const makeHttp = () => new SimeHttp({ gate: fastGate, breaker: new CircuitBreaker() });
  try {
    globalThis.fetch = async () => new Response(null, { status: 302, headers: { location: "https://evil.example/" } });
    await assert.rejects(makeHttp().request("GET", "https://agenda.sime.md/x"), /Refused/);
    globalThis.fetch = async () => new Response("x", { status: 429, headers: { "retry-after": "90" } });
    await assert.rejects(makeHttp().request("GET", "https://agenda.sime.md/x"), (e) => e.waitSeconds === 90);
    globalThis.fetch = async () => new Response("x", { status: 440 });
    await assert.rejects(makeHttp().request("GET", "https://agenda.sime.md/x"), /HTTP 440/);
    globalThis.fetch = async () => new Response("x".repeat(9 * 1024 * 1024), { status: 200 });
    await assert.rejects(makeHttp().request("GET", "https://agenda.sime.md/x"), /too large/);
    await assert.rejects(makeHttp().request("GET", "https://evil.example/"), /Refused/);
  } finally {
    globalThis.fetch = real;
  }
});

const SAMPLE_PORTAL_HTML =
  '<input id="P1_ELEVE_ID" value="101">' +
  '<input id="P1_ELEVE" value="Ion Ciobanu">' +
  '<input id="P1_CLASS_NAME" value="11B">' +
  '<input id="P1_INSTITUTION_NAME" value="Liceul Gheorghe Asachi">' +
  '<input id="P1_DIRIGINTE" value="Maria Rusu">' +
  '<input id="P1_SEMESTER_NUMBER" value="1">' +
  '<input id="P1_MOTIVATED" value="2">' +
  '<input id="P1_UNMOTIVATED" value="1">' +
  '<input id="P1_PROC_PREZ" value="95">' +
  '<input id="P1_LAST_3_EVAL_LIST" value="&lt;ul&gt;&lt;li&gt;20.10.2026 Matematica: 10&lt;/li&gt;&lt;/ul&gt;">' +
  '<input id="P1_HOMEWORK" value="&lt;b&gt;25.10.2026&lt;/b&gt;&lt;ul&gt;&lt;li&gt;&lt;span&gt;Matematica:&lt;/span&gt;&lt;span&gt;Ex. 12&lt;/span&gt;&lt;/li&gt;&lt;/ul&gt;">' +
  '<div id="t_TreeNav"><ul>' +
  '<li data-current="true"><a href="/ords/f?p=AGENDA:1:777888::NO::P1_ELEVE_ID:101&cs=CS101">Ion Ciobanu</a></li>' +
  '<li><a href="/ords/f?p=AGENDA:1:777888::NO::P1_ELEVE_ID:202&cs=CS202">Elena Ciobanu</a></li>' +
  '</ul></div>' +
  '<div id="SEMESTER_REG_1"><table class="t-Report-report"><tr>' +
  '<td headers="DISCIPLINA">Matematica</td>' +
  '<td headers="LISTA_EVALUATION"><span title="20.10.2026">10</span></td>' +
  '<td headers="MED_SEM">10,00</td>' +
  '<td headers="PROFESOR">Vasile Lupu</td>' +
  "</tr></table></div>" +
  '<script id="P1_CALENDAR_EVENTS" type="application/json">' +
  JSON.stringify([
    {
      start: "2026-10-24T08:30:00",
      end: "2026-10-24T09:15:00",
      title: '<span class="lesson-text-wrap">Matematica</span><span class="lesson-room">204</span><span class="lesson-teacher">Vasile Lupu</span>',
    },
    {
      start: "2026-10-24T09:30:00",
      end: "2026-10-24T10:15:00",
      title: '<span class="absent-nemotivat lesson-text-wrap">Fizica</span><span class="lesson-teacher">Ion Popescu</span>',
    },
  ]) +
  "</script>";

test("parseFullPortal bundles all portal sections in a single Cheerio pass", () => {
  const full = parseFullPortal(SAMPLE_PORTAL_HTML, TODAY);
  assert.equal(full.student.id, "101");
  assert.equal(full.metadata.fullName, "Ion Ciobanu");
  assert.equal(full.metadata.classMaster, "Maria Rusu");
  assert.equal(full.grades.length, 1);
  assert.equal(full.grades[0].subject, "Matematica");
  assert.equal(full.grades[0].value, 10);
  assert.equal(full.generalAverage, 10);
  assert.equal(full.catalog.subjects.length, 1);
  assert.equal(full.averages[0].average, 10);
  assert.equal(full.absenceSummary.total, 3);
  assert.equal(full.absences.records.length, 1);
  assert.equal(full.absences.records[0].subject, "Fizica");
  assert.equal(full.timetable.length, 2);
  assert.equal(full.timetable[0].subject, "Matematica");
  assert.equal(full.homework.length, 1);
  assert.equal(full.homework[0].taskDescription, "Ex. 12");
  assert.equal(full.teachers.length, 3);
});

test("executeHandshake runs multi-step login without inter-step RateGate delays", async () => {
  const realFetch = globalThis.fetch;
  let gateCalls = 0;
  const spyGate = new RateGate({
    minIntervalMs: 3000,
    jitterMinMs: 200,
    jitterMaxMs: 500,
    sleep: async () => {
      gateCalls += 1;
    },
  });
  const http = new SimeHttp({ gate: spyGate, breaker: new CircuitBreaker() });
  const cookieJar = new Map();

  const publicHtml = `<a href="javascript:apex.navigation.dialog('/ords/r/agenda/main/login_desktop?p101_user_type=1')">Parent</a>`;
  const dialogHtml =
    '<form action="wwv_flow.accept">' +
    '<input id="pFlowId" value="100">' +
    '<input id="pFlowStepId" value="101">' +
    '<input id="pInstance" value="999">' +
    '<input id="pPageSubmissionId" value="111">' +
    '<input id="pSalt" value="222">' +
    '<input id="pPageItemsProtected" value="prot">' +
    "</form>";

  try {
    globalThis.fetch = async (url, init) => {
      const u = String(url);
      if (u.endsWith("/public")) {
        return new Response(publicHtml, { status: 200, headers: { "set-cookie": "ORA_WWV_APP_100=pubcookie; Path=/" } });
      }
      if (u.includes("login_desktop")) {
        return new Response(dialogHtml, { status: 200 });
      }
      if (init?.method === "POST") {
        return new Response(redirectBody("/ords/r/agenda/main/dashboard?session=777888"), { status: 200 });
      }
      return new Response(SAMPLE_PORTAL_HTML, { status: 200 });
    };

    const res = await executeHandshake(cookieJar, { username: "parent1", password: "secret", role: "parent", http });
    assert.equal(gateCalls, 0);
    assert.equal(res.sessionId, "777888");
    assert.equal(cookieJar.get("ORA_WWV_APP_100"), "pubcookie");
    assert.ok(res.html.includes("Ion Ciobanu"));
  } finally {
    globalThis.fetch = realFetch;
  }
});

test("zero-waterfall login, <5ms unified overview cache, 4-strike login lockout, and multi-student switching", async () => {
  const realFetch = globalThis.fetch;
  breaker.reset();
  loginGuard.clear();
  sessions.clear();

  const publicHtml = `<a href="javascript:apex.navigation.dialog('/ords/r/agenda/main/login_desktop?p101_user_type=1')">Parent</a>`;
  const dialogHtml =
    '<form action="wwv_flow.accept">' +
    '<input id="pFlowId" value="100">' +
    '<input id="pFlowStepId" value="101">' +
    '<input id="pInstance" value="999">' +
    '<input id="pPageSubmissionId" value="111">' +
    '<input id="pSalt" value="222">' +
    '<input id="pPageItemsProtected" value="prot">' +
    "</form>";

  const SECOND_STUDENT_HTML =
    '<div id="t_TreeNav"><ul>' +
    '<li><a href="/ords/f?p=AGENDA:1:777888::NO::P1_ELEVE_ID:101&cs=CS101">Ion Ciobanu</a></li>' +
    '<li data-current="true"><a href="/ords/f?p=AGENDA:1:777888::NO::P1_ELEVE_ID:202&cs=CS202">Elena Ciobanu</a></li>' +
    '</ul></div>' +
    '<select id="P1_ELEVE_ID"><option value="101">Ion Ciobanu</option><option value="202" selected>Elena Ciobanu</option></select>' +
    '<span id="P1_ELEVE">Elena Ciobanu</span>' +
    '<span id="P1_CLASS_NAME">8A</span>' +
    '<input id="P1_INSTITUTION_NAME" value="Liceul Gheorghe Asachi">' +
    '<input id="P1_DIRIGINTE" value="Andrei Moraru">' +
    '<input id="P1_SEMESTER_NUMBER" value="2">' +
    '<input id="P1_MOTIVATED" value="1">' +
    '<input id="P1_UNMOTIVATED" value="0">' +
    '<input id="P1_MOTIVATED2" value="2">' +
    '<input id="P1_UNMOTIVATED2" value="1">' +
    '<div id="SEMESTER_REG_1"><table class="t-Report-report"><tr>' +
    '<td headers="DISCIPLINA">Biologia</td>' +
    '<td headers="LISTA_EVALUATION"><span title="15.10.2026">9</span>, <span title="12.11.2026">10</span></td>' +
    '<td headers="MED_SEM">9,50</td>' +
    "</tr></table></div>" +
    '<div id="SEMESTER_REG_2"><table class="t-Report-report"><tr>' +
    '<td headers="DISCIPLINA">Biologia</td>' +
    '<td headers="LISTA_EVALUATION"><span title="18.02.2027">10</span></td>' +
    '<td headers="MED_SEM">10,00</td>' +
    "</tr></table></div>" +
    '<script id="P1_CALENDAR_EVENTS" type="application/json">' +
    JSON.stringify([
      {
        start: "2026-10-14T08:30:00",
        end: "2026-10-14T09:15:00",
        title: '<span class="absent-motivat lesson-text-wrap">Biologia</span><span class="lesson-teacher">Andrei Moraru</span>',
      },
      {
        start: "2027-02-10T09:30:00",
        end: "2027-02-10T10:15:00",
        title: '<span class="absent-nemotivat lesson-text-wrap">Chimia</span><span class="lesson-teacher">Maria Ionescu</span>',
      },
    ]) +
    "</script>";

  let acceptFailure = false;
  let portalFetches = 0;
  const requestedUrls = [];

  const server = app.listen(0, "127.0.0.1");
  await new Promise((resolve) => server.once("listening", resolve));
  const { port } = server.address();
  const baseUrl = `http://127.0.0.1:${port}`;

  try {
    globalThis.fetch = async (url, init) => {
      const u = String(url);
      if (u.startsWith(baseUrl)) return realFetch(url, init);
      requestedUrls.push(u);
      if (u.endsWith("/public")) return new Response(publicHtml, { status: 200 });
      if (u.includes("login_desktop")) return new Response(dialogHtml, { status: 200 });
      if (init?.method === "POST") {
        if (acceptFailure) return new Response(failureBody("Invalid credentials"), { status: 200 });
        return new Response(redirectBody("/ords/r/agenda/main/dashboard?session=777888"), { status: 200 });
      }
      portalFetches += 1;
      if (u.includes("P1_ELEVE_ID:202")) {
        return new Response(SECOND_STUDENT_HTML, { status: 200 });
      }
      return new Response(SAMPLE_PORTAL_HTML, { status: 200 });
    };

    const loginRes = await realFetch(`${baseUrl}/api/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: "fastuser", password: "ok", role: "parent" }),
    });
    assert.equal(loginRes.status, 200);
    const loginPayload = await loginRes.json();
    assert.ok(loginPayload.token);
    assert.equal(loginPayload.data.student.id, "101");
    assert.equal(loginPayload.data.grades.length, 1);
    assert.equal(loginPayload.data.catalog.generalAverage, 10);
    assert.deepEqual(loginPayload.data.reusita.disciplines[0].marks, [10]);
    assert.equal(loginPayload.data.absente.sem1.records.length, 1);
    assert.equal(loginPayload.data.homework.length, 1);
    assert.equal(portalFetches, 1);

    const t0 = performance.now();
    const ovRes = await realFetch(`${baseUrl}/api/overview`, {
      headers: { Authorization: `Bearer ${loginPayload.token}` },
    });
    const elapsedMs = performance.now() - t0;
    assert.equal(ovRes.status, 200);
    const ovPayload = await ovRes.json();
    assert.equal(ovPayload.data.student.fullName, "Ion Ciobanu");
    assert.equal(ovPayload.cache.stale, false);
    assert.equal(portalFetches, 1);
    assert.ok(elapsedMs < 50);

    const switchRes = await realFetch(`${baseUrl}/api/student/switch`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${loginPayload.token}`,
      },
      body: JSON.stringify({ studentId: "202" }),
    });
    assert.equal(switchRes.status, 200);
    const switchPayload = await switchRes.json();
    assert.ok(requestedUrls.some((u) => u.includes("f?p=AGENDA:1:777888::NO::P1_ELEVE_ID:202") && u.includes("&cs=CS202")));
    assert.equal(switchPayload.studentId, "202");
    assert.equal(switchPayload.data.student.id, "202");
    assert.equal(switchPayload.data.student.fullName, "Elena Ciobanu");
    assert.equal(switchPayload.data.student.className, "8A");
    assert.deepEqual(switchPayload.data.reusita.disciplines[0].marks, [9, 10, 10]);
    assert.equal(switchPayload.data.reusita.disciplines[0].sem1Average, 9.5);
    assert.equal(switchPayload.data.reusita.disciplines[0].sem2Average, 10);
    assert.equal(switchPayload.data.reusita.disciplines[0].annualAverage, 9.75);
    assert.equal(switchPayload.data.absente.sem1.records.length, 1);
    assert.equal(switchPayload.data.absente.sem1.records[0].discipline, "Biologia");
    assert.equal(switchPayload.data.absente.sem2.records.length, 1);
    assert.equal(switchPayload.data.absente.sem2.records[0].discipline, "Chimia");

    const sessionRecord = sessions.get(loginPayload.token);
    assert.ok(sessionRecord.students["101"]);
    assert.ok(sessionRecord.students["202"]);
    assert.equal(sessionRecord.students["101"].metadata.fullName, "Ion Ciobanu");
    assert.equal(sessionRecord.students["202"].metadata.fullName, "Elena Ciobanu");

    acceptFailure = true;
    for (let i = 0; i < 4; i += 1) {
      const bad = await realFetch(`${baseUrl}/api/auth/login`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username: "lockeduser", password: "bad", role: "parent" }),
      });
      assert.equal(bad.status, 401);
    }

    const locked = await realFetch(`${baseUrl}/api/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: "lockeduser", password: "bad", role: "parent" }),
    });
    assert.equal(locked.status, 429);
    const lockedBody = await locked.json();
    assert.ok(lockedBody.waitSeconds <= 60);
  } finally {
    globalThis.fetch = realFetch;
    await new Promise((resolve) => server.close(resolve));
  }
});
