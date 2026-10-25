import assert from "node:assert/strict";
import fs from "node:fs";
import http from "node:http";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import * as cheerio from "cheerio";
import {
  CircuitBreaker,
  RateGate,
  SimeAuth,
  SimeClient,
  SimeError,
  SimeForbiddenError,
  SimeHttp,
  SimeMaintenanceError,
  SimeNotFoundError,
  SimeRateLimitError,
  SimeSessionExpiredError,
  absenceRecords,
  app,
  breaker,
  buildCatalog,
  buildSemesterAbsences,
  buildTeachers,
  executeHandshake,
  extractHtmlAbsenceRecords,
  extractPageFlowInfo,
  findCalendarAjaxId,
  findReportAjaxId,
  fingerprintHeaders,
  inspectParserHealth,
  loginGuard,
  normalizeMark,
  parseAbsenceSummary,
  parseCalendarEvents,
  parseDate,
  parseFullPortal,
  parseHomework,
  parseMarkCell,
  parseRecentGrades,
  parseSemesterReport,
  parseStudentMetadata,
  BROWSER_PROFILE,
  requireSimeUrl,
  resolveSimeUrl,
  scrubLog,
  sessions,
  unescapeJs,
} from "../server.js";
import { sanitizeCapture, sanitizeHtml } from "../scripts/sanitize-capture.mjs";
import { findAbsenceApexRegions, scanKnownAbsenceMatches } from "../scripts/capture-absence-sources.mjs";

const TODAY = "2026-10-24";
const __dirname = path.dirname(fileURLToPath(import.meta.url));

// 1. CHEERIO HTML PARSERS & EXTRACTORS

test("recent grades: primary and fallback selectors with qualifiers", () => {
  const html =
    '<ul><li><span class="eval-date">20.10.2026</span><span class="eval-discipline">Matematica:</span><span class="grade">9</span></li>' +
    '<li>19.10 Istoria: FB</li><li>broken</li></ul>';
  const grades = parseRecentGrades(html, 1, TODAY);
  assert.deepEqual(
    grades.map((g) => [g.subject, g.value, g.label, g.kind, g.date, g.semester]),
    [
      ["Matematica", 9, "9", "numeric", "2026-10-20", 1],
      ["Istoria", null, "FB", "qualifier", "2026-10-19", 1],
    ],
  );
  assert.deepEqual(parseRecentGrades("", null), []);
  assert.deepEqual(parseRecentGrades("<div><li></li", null), []);
});

test("semester report marks, theses, qualifiers, dates, and catalog aggregation", () => {
  const html =
    '<table class="t-Report-report">' +
    '<tr>' +
    '<td headers="DISCIPLINA">Fizica</td>' +
    '<td headers="LISTA_EVALUATION"><span title="12.10.2026">8</span>, <span title="19.10.2026">10</span></td>' +
    '<td headers="TEZA"><span title="15.11.2026" class="nota-teza">9</span></td>' +
    '<td headers="MED_SEM">9,00</td>' +
    '<td headers="PROFESOR">Ion Popescu</td>' +
    '</tr>' +
    '<tr>' +
    '<td headers="DISCIPLINA">Muzica</td>' +
    '<td headers="LISTA_EVALUATION"><span title="10.10.2026">FB</span>, <span title="22.10.2026">B</span></td>' +
    '<td headers="MED_SEM"></td>' +
    '<td headers="PROFESOR">Maria Ionescu</td>' +
    '</tr>' +
    '</table>';
  const report = parseSemesterReport(html);
  
  const fizica = report.get("Fizica");
  assert.equal(fizica.subject, "Fizica");
  assert.equal(fizica.semesterAverage, 9);
  assert.equal(fizica.teacher, "Ion Popescu");
  assert.equal(fizica.marks.length, 3);
  assert.ok(fizica.marks.some((m) => m.isThesis && m.value === 9));

  const muzica = report.get("Muzica");
  assert.equal(muzica.subject, "Muzica");
  assert.equal(muzica.marks.length, 2);
  assert.equal(muzica.marks[0].kind, "qualifier");
  assert.equal(muzica.marks[0].label, "FB");
  assert.equal(muzica.marks[1].label, "B");

  const cat = buildCatalog([{ semester: 1, report }]);
  assert.equal(cat.generalAverage, 9);
  const catMuzica = cat.subjects.find((s) => s.subject === "Muzica");
  assert.equal(catMuzica.average, null, "Qualifiers must be excluded from numeric average");
});

test("homework groups by date", () => {
  const [hw] = parseHomework("<b>25.10.2026</b><ul><li><span>Fizica:</span><span>Ex. 5</span></li></ul>", TODAY);
  assert.deepEqual(hw, { subject: "Fizica", taskDescription: "Ex. 5", dueDate: "2026-10-25", isCompleted: false });
});

test("absence summary falls back gracefully on missing or invalid data", () => {
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

test("date parsing infers year and rejects invalid dates", () => {
  assert.equal(parseDate("19.10", TODAY), "2026-10-19");
  assert.equal(parseDate("30.12", TODAY), "2025-12-30");
  assert.equal(parseDate("31.02.2026", TODAY), null);
  assert.equal(parseDate("2026-10-01", TODAY), "2026-10-01");
});

test("unescapeJs keeps unicode characters", () => {
  assert.equal(unescapeJs("f?p=\\u002F\\x41 p\\u0103rinte"), "f?p=/A părinte");
});

test("extractPageFlowInfo reads pFlowId and pFlowStepId from page or falls back", () => {
  const customHtml = '<div><input id="pFlowId" value="108"><input id="pFlowStepId" value="4"></div>';
  const parsed = extractPageFlowInfo(customHtml);
  assert.equal(parsed.flowId, "108");
  assert.equal(parsed.flowStepId, "4");

  const emptyParsed = extractPageFlowInfo("<div>No inputs here</div>");
  assert.equal(emptyParsed.flowId, "104");
  assert.equal(emptyParsed.flowStepId, "1");
});

// 2. CALENDAR EVENTS, TEACHERS & ABSENCE RECORDS SCHEMA

test("calendar events, teachers directory, and absence detection across titles and classes", () => {
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
      title: '<span class="absent-nemotivat lesson-text-wrap">Fizica</span><span class="lesson-room">101</span><span class="lesson-teacher">Ion Popescu</span>',
    },
    {
      start: "2026-10-24T10:00:00",
      end: "2026-10-24T10:45:00",
      className: "motivat",
      title: '<span class="lesson-text-wrap">Biologie</span><span class="lesson-teacher">Elena Rusu</span>',
    },
  ];
  const entries = parseCalendarEvents(events);
  assert.deepEqual(entries.map((e) => e.subject), ["Chimia", "Fizica", "Biologie"]);
  assert.equal(entries[0].classroom, "12");
  assert.equal(entries[0].teacher, "Maria Ionescu");
  assert.equal(entries[0].isAbsence, false);
  
  assert.ok(entries[1].isAbsence && entries[1].isUnexcused);
  assert.ok(entries[2].isAbsence && entries[2].isExcused);

  const teachers = buildTeachers(entries, { classMaster: "Elena Rusu" });
  assert.equal(teachers.length, 3);
  assert.equal(teachers.find((t) => t.name === "Elena Rusu").note, "Diriginte");

  const abs = absenceRecords(entries);
  assert.equal(abs.length, 2);
  assert.equal(abs[0].subject, "Biologie");
  assert.equal(abs[0].status, "excused");
  assert.equal(abs[1].subject, "Fizica");
  assert.equal(abs[1].status, "unexcused");
  assert.equal(abs[1].room, "101");
});

test("absence record schema contains required fields and handles HTML table parser", () => {
  const tableHtml =
    '<table class="t-Report-report">' +
    '<thead><tr><th id="h_data">Data</th><th id="h_disc">Disciplina</th><th id="h_stat">Statut</th><th id="h_ora">Ora</th><th id="h_sala">Sala</th><th id="h_prof">Profesor</th></tr></thead>' +
    '<tbody>' +
    '<tr>' +
    '<td headers="DATA">14.10.2026</td>' +
    '<td headers="DISCIPLINA">Istoria</td>' +
    '<td headers="STATUT">Nemotivat</td>' +
    '<td headers="ORA">Ora 2</td>' +
    '<td headers="SALA">Sala 204</td>' +
    '<td headers="PROFESOR">Vasile Ceban</td>' +
    '</tr>' +
    '</tbody></table>';
  const $ = cheerio.load(tableHtml);
  const records = extractHtmlAbsenceRecords($, "2026-10-24", "101");
  assert.equal(records.length, 1);
  const r = records[0];
  assert.equal(r.studentKey, "101");
  assert.equal(r.date, "2026-10-14");
  assert.equal(r.subject, "Istoria");
  assert.equal(r.status, "unexcused");
  assert.equal(r.room, "Sala 204");
  assert.equal(r.teacher, "Vasile Ceban");
  assert.equal(r.semester, 1);
});

test("absence guarded detection: (a) match equals portal total shows lesson details", () => {
  const summary = { sem1Excused: 1, sem1Unexcused: 1, sem1Total: 2, sem2Total: 0 };
  const events = [
    {
      start: "2026-10-24T08:00:00",
      end: "2026-10-24T08:45:00",
      title: '<span class="absen-motivat lesson-text-wrap">Biologie</span><span class="lesson-room">101</span><span class="lesson-teacher">Ana Munteanu</span>',
    },
    {
      start: "2026-10-24T09:00:00",
      end: "2026-10-24T09:45:00",
      title: '<span class="absen-nemotivat lesson-text-wrap">Fizică</span><span class="lesson-room">102</span><span class="lesson-teacher">Ion Popescu</span>',
    },
  ];
  const entries = parseCalendarEvents(events);
  const records = absenceRecords(entries);
  assert.equal(records.length, 2);

  const detailed = buildSemesterAbsences(summary, records, "101");
  assert.equal(detailed.sem1.total, 2);
  assert.equal(detailed.sem1.records.length, 2, "Full lesson details shown when count equals portal total");
  assert.equal(detailed.sem1.records[0].subject, "Fizică");
  assert.equal(detailed.sem1.records[1].subject, "Biologie");
});

test("absence guarded detection: (b) mismatch shows totals only and honest message", () => {
  const summary = { sem1Excused: 2, sem1Unexcused: 3, sem1Total: 5, sem2Total: 0 };
  const events = [
    {
      start: "2026-10-24T08:00:00",
      end: "2026-10-24T08:45:00",
      title: '<span class="absen-nemotivat lesson-text-wrap">Fizică</span>',
    },
  ];
  const entries = parseCalendarEvents(events);
  const records = absenceRecords(entries);
  assert.equal(records.length, 1);

  const detailed = buildSemesterAbsences(summary, records, "101");
  assert.equal(detailed.sem1.total, 5, "Portal total preserved");
  assert.equal(detailed.sem1.motivate, 2);
  assert.equal(detailed.sem1.nemotivate, 3);
  assert.equal(detailed.sem1.records.length, 0, "No partial records shown on count mismatch");
});

test("absence guarded detection: (c) no markers shows totals only", () => {
  const summary = { sem1Excused: 0, sem1Unexcused: 4, sem1Total: 4, sem2Total: 0 };
  const events = [
    {
      start: "2026-10-24T08:00:00",
      end: "2026-10-24T08:45:00",
      title: '<span class="lesson-text-wrap">Matematică</span><span class="lesson-room">101</span>',
    },
  ];
  const entries = parseCalendarEvents(events);
  const records = absenceRecords(entries);
  assert.equal(records.length, 0);

  const detailed = buildSemesterAbsences(summary, records, "101");
  assert.equal(detailed.sem1.total, 4, "Portal total preserved");
  assert.equal(detailed.sem1.records.length, 0, "No detailed records when no markers present");
});

test("absence guarded detection: (d) text containing 'motivat' or 'absen' in homework/event does NOT create absence record", () => {
  const events = [
    {
      start: "2026-10-24T08:00:00",
      end: "2026-10-24T08:45:00",
      title: '<span class="lesson-text-wrap">Limba română</span><span class="lesson-homework">Aduceți motivarea pentru absență la diriginte</span>',
      className: "fc-event-standard",
    },
  ];
  const entries = parseCalendarEvents(events);
  assert.equal(entries.length, 1);
  assert.equal(entries[0].isAbsence, false, "Must not flag as absence from description text");
  assert.equal(entries[0].isExcused, false);
  assert.equal(entries[0].isUnexcused, false);

  const records = absenceRecords(entries);
  assert.equal(records.length, 0, "Must not create absence record from text matching");
});

// 3. HTTP TRANSPORT, SECURITY & APP INTEGRATION

test("URL guard blocks untrusted destinations", () => {
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
  const fp = BROWSER_PROFILE;
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
    {
      start: "2026-10-22T10:30:00",
      end: "2026-10-22T11:15:00",
      title: '<span class="absen-motivat lesson-text-wrap">Biologie</span><span class="lesson-teacher">Ion Popescu</span>',
    },
    {
      start: "2026-10-23T11:30:00",
      end: "2026-10-23T12:15:00",
      title: '<span class="absen-motivat lesson-text-wrap">Chimia</span><span class="lesson-teacher">Vasile Lupu</span>',
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
  assert.equal(full.absences.records.length, 3);
  assert.ok(full.absences.records.some((r) => r.subject === "Fizica"));
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

// 4. SANITIZATION AND DIAGNOSTICS (W0)

test("sanitizeCapture sanitizes personal data while keeping structure", () => {
  const sampleHtml =
    '<input id="P1_ELEVE" value="Ion Popescu">' +
    '<input id="P1_ELEVE_ID" value="12345">' +
    '<input id="P1_INSTITUTION_NAME" value="Liceul Teoretic Real">' +
    '<input id="P1_CLASS_NAME" value="11B">' +
    '<input id="P1_DIRIGINTE" value="Maria Ciobanu">' +
    '<input id="P1_BIRTH_DATE" value="15.05.2009">' +
    '<input id="P1_ADDRESS" value="Str. Stefan cel Mare 10">' +
    '<input id="P1_ELEVE_UK" value="UK_REAL_999">' +
    '<input id="pInstance" value="888777">' +
    '<input id="pPageSubmissionId" value="333222">' +
    '<input id="pSalt" value="444555">' +
    '<input id="pPageItemsProtected" value="SECRET_SALT_123">' +
    '<input data-for="P1_ELEVE_ID" value="SECRET_CK_456">' +
    '<div id="t_TreeNav"><ul><li><a href="f?p=AGENDA:1:888777::NO::P1_ELEVE_ID:12345&cs=REAL_CS_789">Ion Popescu</a></li></ul></div>' +
    '<table class="t-Report-report"><tr><td headers="PROFESOR">Maria Ciobanu</td><td headers="DISCIPLINA">Matematica</td></tr></table>';

  const sanitized = sanitizeHtml(sampleHtml);

  assert.ok(!sanitized.includes("Ion Popescu"), "Personal student name must be sanitized");
  assert.ok(!sanitized.includes("12345"), "Student ID must be sanitized");
  assert.ok(!sanitized.includes("Maria Ciobanu"), "Teacher name must be sanitized");
  assert.ok(!sanitized.includes("Str. Stefan cel Mare 10"), "Address must be sanitized");
  assert.ok(!sanitized.includes("15.05.2009"), "Birthdate must be sanitized");
  assert.ok(!sanitized.includes("SECRET_SALT_123"), "Protected items token must be sanitized");
  assert.ok(!sanitized.includes("SECRET_CK_456"), "Item checksum must be sanitized");
  assert.ok(!sanitized.includes("REAL_CS_789"), "URL checksum must be sanitized");

  assert.ok(sanitized.includes('id="P1_ELEVE"'));
  assert.ok(sanitized.includes('id="P1_ELEVE_ID"'));
  assert.ok(sanitized.includes('id="t_TreeNav"'));
  assert.ok(sanitized.includes('headers="PROFESOR"'));
  assert.ok(sanitized.includes("Matematica"), "Subject name should be preserved");
});

test("inspectParserHealth returns structured diagnostics", () => {
  const health = inspectParserHealth(SAMPLE_PORTAL_HTML);

  assert.ok(health.students);
  assert.ok(health.grades);
  assert.ok(health.catalog);
  assert.ok(health.absencesSummary);
  assert.ok(health.calendar);
  assert.ok(health.teachers);

  assert.equal(health.students.count, 2);
  assert.ok(health.students.selectorsMatched.includes("#P1_ELEVE_ID"));
  assert.deepEqual(health.students.fieldsFound.sort(), ["className", "fullName", "id", "schoolName"]);

  assert.equal(health.grades.count, 1);
  assert.equal(health.catalog.count, 1);
  assert.equal(health.absencesSummary.count, 3);
});

// 4. RESTORED CONSOLIDATION TESTS: PARSER HEALTH, ERROR TAXONOMY, CACHING, TEACHERS, QUALIFIERS, SECURITY, SW

test("Parser Health: GET /api/debug/parser-health is disabled unless SIME_DEBUG=1", async () => {
  const origEnv = process.env.SIME_DEBUG;
  delete process.env.SIME_DEBUG;

  const server = app.listen(0, "127.0.0.1");
  await new Promise((resolve) => server.once("listening", resolve));
  const { port } = server.address();
  const baseUrl = `http://127.0.0.1:${port}`;

  try {
    const disabledRes = await fetch(`${baseUrl}/api/debug/parser-health`);
    assert.equal(disabledRes.status, 404);

    process.env.SIME_DEBUG = "1";
    const noAuthRes = await fetch(`${baseUrl}/api/debug/parser-health`);
    assert.equal(noAuthRes.status, 401);
  } finally {
    if (origEnv !== undefined) process.env.SIME_DEBUG = origEnv;
    else delete process.env.SIME_DEBUG;
    await new Promise((resolve) => server.close(resolve));
  }
});

const TWO_ID_FORMAT_PAGE =
  '<input id="P1_ELEVE_ID" value="101">' +
  '<input id="P1_ELEVE" value="Ion Ciobanu">' +
  '<input id="P1_CLASS_NAME" value="10A">' +
  '<input id="P1_INSTITUTION_NAME" value="Liceul Teoretic">' +
  '<div id="t_TreeNav"><ul>' +
  '<li data-current="true"><a href="/ords/f?p=AGENDA:1:999::NO::P1_ELEVE_ID:5B1E59D6A1B2C3D4&cs=3XYZ123">Ion Ciobanu</a></li>' +
  '<li><a href="/ords/f?p=AGENDA:1:999::NO::P1_ELEVE_ID:8F7E6D5C4B3A2109&cs=3UVW456">Elena Ciobanu</a></li>' +
  '</ul></div>';

test("Student Identity: parseFullPortal deduplicates students with differing ID formats", () => {
  const result = parseFullPortal(TWO_ID_FORMAT_PAGE);
  assert.equal(result.students.length, 2);
  const ion = result.students.find((s) => s.fullName === "Ion Ciobanu");
  assert.ok(ion);
  assert.equal(ion.className, "10A");
  assert.equal(ion.schoolName, "Liceul Teoretic");
  const elena = result.students.find((s) => s.fullName === "Elena Ciobanu");
  assert.ok(elena);
});

test("Student Identity: SimeClient.registerStudents maps nav tree checksum URLs and merges aliases", () => {
  const client = new SimeClient("testuser", "testpass", "parent");
  const $ = cheerio.load(TWO_ID_FORMAT_PAGE);
  const page = { url: "https://agenda.sime.md/ords/r/agenda/main/dashboard?session=999", html: TWO_ID_FORMAT_PAGE, $, fetchedAt: Date.now() };

  const students = client.registerStudents(page);
  assert.equal(students.length, 2);

  const ion = students.find((s) => s.fullName === "Ion Ciobanu");
  assert.equal(ion.className, "10A");

  const ionUrl = client.studentUrls.get(ion.id) || client.studentUrls.get("101") || client.studentUrls.get("5b1e59d6a1b2c3d4");
  assert.ok(ionUrl);
  assert.ok(ionUrl.includes("cs=3XYZ123"));
  assert.ok(client.portalIds.has("101"));
});

test("Error Taxonomy: HTTP status codes and error taxonomy mapping in raiseForStatus", () => {
  const http = new SimeHttp({ gate: new RateGate({ sleep: async () => {} }), breaker: new CircuitBreaker() });

  assert.throws(() => http.raiseForStatus(new Response(null, { status: 403 })), (err) => {
    assert.ok(err instanceof SimeForbiddenError);
    assert.ok(!(err instanceof SimeSessionExpiredError));
    return true;
  });

  assert.throws(() => http.raiseForStatus(new Response(null, { status: 404 })), (err) => {
    assert.ok(err instanceof SimeNotFoundError);
    assert.ok(!(err instanceof SimeSessionExpiredError));
    return true;
  });

  assert.throws(() => http.raiseForStatus(new Response(null, { status: 429, headers: { "retry-after": "45" } })), (err) => {
    assert.ok(err instanceof SimeRateLimitError);
    assert.equal(err.waitSeconds, 45);
    return true;
  });

  assert.throws(() => http.raiseForStatus(new Response(null, { status: 503, headers: { "retry-after": "60" } })), (err) => {
    assert.ok(err instanceof SimeRateLimitError || err instanceof SimeMaintenanceError);
    return true;
  });

  assert.throws(() => http.raiseForStatus(new Response(null, { status: 401 })), (err) => err instanceof SimeSessionExpiredError);
  assert.throws(() => http.raiseForStatus(new Response(null, { status: 440 })), (err) => err instanceof SimeSessionExpiredError);
});

test("Error Taxonomy: simulated upstream 404 does not trigger re-login", async () => {
  const client = new SimeClient("testparent", "pass", "parent");
  let reloginCount = 0;
  client.relogin = async () => {
    reloginCount += 1;
  };

  await assert.rejects(
    () =>
      client.withRetry(async () => {
        client.http.raiseForStatus(new Response(null, { status: 404 }));
      }),
    (err) => err instanceof SimeNotFoundError
  );

  assert.equal(reloginCount, 0, "404 must not trigger re-login");
});

test("Error Taxonomy: simulated expiry triggers exactly one re-login for N concurrent requests", async () => {
  const client = new SimeClient("testparent", "pass", "parent");
  let reloginCalls = 0;
  let attempt = 0;

  client.executeHandshake = async () => {
    reloginCalls += 1;
    await new Promise((r) => setTimeout(r, 20));
  };

  const operation = async () => {
    if (attempt < 5) {
      attempt += 1;
      throw new SimeSessionExpiredError("Portal session expired");
    }
    return "success";
  };

  const results = await Promise.all([
    client.withRetry(operation),
    client.withRetry(operation),
    client.withRetry(operation),
    client.withRetry(operation),
    client.withRetry(operation),
  ]);

  assert.deepEqual(results, ["success", "success", "success", "success", "success"]);
  assert.equal(reloginCalls, 1, `Expected exactly 1 re-login for 5 concurrent requests, got ${reloginCalls}`);
});

test("Circuit Breaker: per-account circuit breaker does not block other users", () => {
  const brA = new CircuitBreaker();
  const brB = new CircuitBreaker();

  brA.trip(300);
  assert.ok(brA.remainingSeconds() > 0);
  assert.throws(() => brA.ensureClosed(), /suspended/);

  assert.equal(brB.remainingSeconds(), 0);
  assert.doesNotThrow(() => brB.ensureClosed());
});

test("Error Response: server returns standard error shape { error: { code, message, retryAfter } }", async () => {
  const server = app.listen(0, "127.0.0.1");
  await new Promise((resolve) => server.once("listening", resolve));
  const { port } = server.address();
  const baseUrl = `http://127.0.0.1:${port}`;

  try {
    const noAuth = await fetch(`${baseUrl}/api/overview`);
    assert.equal(noAuth.status, 401);
    const noAuthBody = await noAuth.json();
    assert.ok(noAuthBody.error);
    assert.equal(noAuthBody.error.code, "APP_SESSION_INVALID");
    assert.ok(noAuthBody.error.message.includes("Sesiunea a expirat"));

    const badLogin = await fetch(`${baseUrl}/api/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: "", password: "", role: "parent" }),
    });
    assert.equal(badLogin.status, 400);
    const badLoginBody = await badLogin.json();
    assert.ok(badLoginBody.error);
    assert.equal(badLoginBody.error.code, "INVALID_REQUEST");
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});

test("Lazy Caching: overview does not make heavy calendar calls, tab endpoints fetch detailed data and cache properly", async () => {
  const server = app.listen(0, "127.0.0.1");
  await new Promise((resolve) => server.once("listening", resolve));
  const { port } = server.address();
  const baseUrl = `http://127.0.0.1:${port}`;

  let calendarRangeCalls = 0;
  let getAbsenceRecordsCalls = 0;
  let getTeachersCalls = 0;
  let getWeekCalls = 0;

  const origCalendarRange = SimeClient.prototype.calendarRange;
  const origGetAbsenceSummary = SimeClient.prototype.getAbsenceSummary;
  const origGetAbsenceRecords = SimeClient.prototype.getAbsenceRecords;
  const origGetTeachers = SimeClient.prototype.getTeachers;
  const origGetWeek = SimeClient.prototype.getWeek;
  const origLogin = SimeClient.prototype.loginAndParsePortal;

  SimeClient.prototype.calendarRange = async function () {
    calendarRangeCalls += 1;
    return [];
  };

  SimeClient.prototype.getAbsenceSummary = async function () {
    return { presencePercent: 100, sem1Excused: 1, sem1Unexcused: 0, total: 1 };
  };

  SimeClient.prototype.getAbsenceRecords = async function () {
    getAbsenceRecordsCalls += 1;
    return [
      {
        date: "2026-10-14",
        subject: "Matematica",
        status: "excused",
        startTime: "08:30",
        endTime: "09:15",
        room: "101",
        teacher: "Profesor",
        semester: 1,
      },
    ];
  };

  SimeClient.prototype.getTeachers = async function () {
    getTeachersCalls += 1;
    return [
      { name: "Profesor A", subjects: ["Matematica"], lessons: 4, note: null },
      { name: "Profesor B (Diriginte)", subjects: ["Romana"], lessons: 5, note: "Diriginte" },
    ];
  };

  SimeClient.prototype.getWeek = async function () {
    getWeekCalls += 1;
    return {
      weekStart: "2026-10-19",
      days: { "2026-10-19": [{ subject: "Istorie", startLocal: "08:30", endLocal: "09:15" }] },
    };
  };

  SimeClient.prototype.loginAndParsePortal = async function () {
    return {
      portalUrl: "https://agenda.sime.md/ords/r/agenda/main/dashboard",
      data: {
        student: { id: "101", fullName: "Ion Student", className: "10A" },
        students: [{ id: "101", fullName: "Ion Student", className: "10A" }],
        metadata: { id: "101", fullName: "Ion Student", classMaster: "Profesor B (Diriginte)" },
        grades: [],
        catalog: { subjects: [] },
        absente: {
          summary: { total: 1, sem1Excused: 1, sem1Unexcused: 0 },
          records: [],
          sem1: { total: 1, motivate: 1, nemotivate: 0, records: [] },
          sem2: { total: 0, motivate: 0, nemotivate: 0, records: [] },
        },
        teachers: [{ name: "Profesor B (Diriginte)", note: "Diriginte", subjects: [] }],
        events: [],
      },
    };
  };

  try {
    const loginRes = await fetch(`${baseUrl}/api/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: "student1", password: "pwd", role: "student" }),
    });
    assert.equal(loginRes.status, 200);
    const loginData = await loginRes.json();
    const token = loginData.token;

    const ovRes = await fetch(`${baseUrl}/api/overview`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    assert.equal(ovRes.status, 200);
    assert.equal(calendarRangeCalls, 0, "Overview must not make calendarRange calls");
    assert.equal(getAbsenceRecordsCalls, 0, "Overview must not make getAbsenceRecords calls");
    assert.equal(getTeachersCalls, 0, "Overview must not make getTeachers calls");

    const absRes1 = await fetch(`${baseUrl}/api/absences`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    assert.equal(absRes1.status, 200);
    const absData1 = await absRes1.json();
    assert.equal(getAbsenceRecordsCalls, 1, "First absences fetch must call getAbsenceRecords");
    assert.equal(absData1.data.detail, true);
    assert.equal(absData1.data.records.length, 1);

    const absRes2 = await fetch(`${baseUrl}/api/absences`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    assert.equal(absRes2.status, 200);
    assert.equal(getAbsenceRecordsCalls, 1, "Second absences fetch within TTL must use cache");

    const teachersRes1 = await fetch(`${baseUrl}/api/teachers`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    assert.equal(teachersRes1.status, 200);
    const teachersData1 = await teachersRes1.json();
    assert.equal(getTeachersCalls, 1, "First teachers fetch must call getTeachers");
    assert.equal(teachersData1.data.teachers.length, 2);

    const teachersRes2 = await fetch(`${baseUrl}/api/teachers`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    assert.equal(teachersRes2.status, 200);
    assert.equal(getTeachersCalls, 1, "Second teachers fetch within TTL must use cache");

    const schedRes = await fetch(`${baseUrl}/api/schedule?date=2026-10-19`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    assert.equal(schedRes.status, 200);
    assert.equal(getWeekCalls, 1, "Schedule fetch must call getWeek");
  } finally {
    SimeClient.prototype.calendarRange = origCalendarRange;
    SimeClient.prototype.getAbsenceSummary = origGetAbsenceSummary;
    SimeClient.prototype.getAbsenceRecords = origGetAbsenceRecords;
    SimeClient.prototype.getTeachers = origGetTeachers;
    SimeClient.prototype.getWeek = origGetWeek;
    SimeClient.prototype.loginAndParsePortal = origLogin;
    await new Promise((resolve) => server.close(resolve));
  }
});

test("Absences Backend: getAbsenceRecords combines calendar events and HTML table absences", async () => {
  const client = new SimeClient("user1", "pwd", "student");

  client.calendarRange = async () => [
    {
      start: Date.parse("2026-10-12T08:30:00Z"),
      end: Date.parse("2026-10-12T09:15:00Z"),
      date: "2026-10-12",
      startLocal: "08:30",
      endLocal: "09:15",
      subject: "Matematica",
      discipline: "Matematica",
      teacher: "Profesor Mate",
      isExcused: true,
      isAbsence: true,
      note: null,
    },
  ];

  const mockHtml = `
    <table>
      <thead>
        <tr><th id="DATA">DATA</th><th id="DISCIPLINA">DISCIPLINA</th><th id="STATUT">STATUT</th><th id="ORA">ORA</th><th id="PROFESOR">PROFESOR</th></tr>
      </thead>
      <tbody>
        <tr>
          <td headers="DATA">14.10.2026</td>
          <td headers="DISCIPLINA">Limba Romana</td>
          <td headers="STATUT">Nemotivat</td>
          <td headers="ORA">09:25</td>
          <td headers="PROFESOR">Profesor Romana</td>
        </tr>
      </tbody>
    </table>
  `;

  client.loadStudent = async () => {
    return {
      url: "https://agenda.sime.md/ords/r/agenda/main/dashboard",
      html: mockHtml,
      $: cheerio.load(mockHtml),
    };
  };

  const records = await client.getAbsenceRecords("101");
  assert.equal(records.length, 2, "Must contain both calendar and HTML table absences");
  const subjNames = records.map((r) => r.subject || r.discipline);
  assert.ok(subjNames.includes("Matematica"));
  assert.ok(subjNames.includes("Limba Romana"));
});

test("Absences Backend: per-subject grouping logic groups records and counts excused/unexcused correctly", () => {
  const records = [
    { date: "2026-10-01", subject: "Matematica", status: "motivat" },
    { date: "2026-10-08", subject: "Matematica", status: "nemotivat" },
    { date: "2026-10-15", subject: "Romana", status: "nemotivat" },
  ];

  const bySubject = new Map();
  for (const r of records) {
    const subj = r.subject || r.discipline || "Disciplină";
    if (!bySubject.has(subj)) bySubject.set(subj, []);
    bySubject.get(subj).push(r);
  }

  assert.equal(bySubject.size, 2);
  const math = bySubject.get("Matematica");
  assert.equal(math.length, 2);
  assert.equal(math.filter((r) => r.status === "motivat").length, 1);
  assert.equal(math.filter((r) => r.status === "nemotivat").length, 1);

  const ro = bySubject.get("Romana");
  assert.equal(ro.length, 1);
  assert.equal(ro.filter((r) => r.status === "nemotivat").length, 1);
});

test("Teachers: buildTeachers merges surname + initial with full name and keeps fuller name", () => {
  const metadata = { classMaster: "Popescu Maria" };
  const catalogSubjects = [
    { subject: "Matematica", teacher: "Ciobanu I." },
    { subject: "Fizica", teacher: "Rusu Elena" },
  ];
  const calendarEntries = [
    { subject: "Matematica", teacher: "Ciobanu Ion" },
    { subject: "Informatica", teacher: "Ciobanu Ion" },
    { subject: "Limba Romana", teacher: "Prof. Popescu M." },
  ];

  const teachers = buildTeachers(calendarEntries, metadata, catalogSubjects);
  assert.equal(teachers[0].name, "Popescu Maria");
  assert.equal(teachers[0].note, "Diriginte");
  assert.ok(teachers[0].subjects.includes("Limba Romana"));

  const ciobanu = teachers.find((t) => t.name === "Ciobanu Ion");
  assert.ok(ciobanu);
  assert.equal(ciobanu.lessons, 2);
  assert.ok(ciobanu.subjects.includes("Matematica"));
  assert.ok(ciobanu.subjects.includes("Informatica"));

  const rusu = teachers.find((t) => t.name === "Rusu Elena");
  assert.ok(rusu);
  assert.ok(rusu.subjects.includes("Fizica"));
  assert.equal(rusu.lessons, 0);

  const ciobanuEntries = teachers.filter((t) => t.name.includes("Ciobanu"));
  assert.equal(ciobanuEntries.length, 1);
});

test("Teachers: buildTeachers distinguishes teachers with same surname but different initials", () => {
  const entries = [
    { subject: "Istorie", teacher: "Moraru Vasile" },
    { subject: "Geografie", teacher: "Moraru Angela" },
  ];

  const teachers = buildTeachers(entries);
  assert.equal(teachers.length, 2);
  assert.ok(teachers.some((t) => t.name === "Moraru Vasile"));
  assert.ok(teachers.some((t) => t.name === "Moraru Angela"));
});

test("Teachers: getTeachers aggregates from metadata, calendar scan, and catalog", async () => {
  const client = new SimeClient("user1", "pwd", "student");
  client.loadStudent = async () => {
    const html = '<input id="P1_DIRIGINTE" value="Grecu Tatiana">';
    return { html, $: cheerio.load(html), url: "https://agenda.sime.md" };
  };

  client.calendarRange = async () => [
    { subject: "Biologie", teacher: "Grecu T." },
    { subject: "Chimie", teacher: "Stati Andrei" },
  ];

  client.getCatalog = async () => ({
    subjects: [{ subject: "Engleza", teacher: "Smith John" }],
  });

  const teachers = await client.getTeachers("101");
  assert.equal(teachers.length, 3);
  const diriginte = teachers.find((t) => t.note === "Diriginte");
  assert.ok(diriginte);
  assert.equal(diriginte.name, "Grecu Tatiana");
  assert.ok(diriginte.subjects.includes("Biologie"));
  assert.ok(teachers.some((t) => t.name === "Stati Andrei" && t.subjects.includes("Chimie")));
  assert.ok(teachers.some((t) => t.name === "Smith John" && t.subjects.includes("Engleza")));
});

test("Marks Redesign: parseSemesterReport detects thesis marks and buildCatalog passes them through", () => {
  const html = `
    <table>
      <thead>
        <tr><th>DISCIPLINA</th><th>NOTE</th><th>TEZA</th></tr>
      </thead>
      <tbody>
        <tr>
          <td headers="DISCIPLINA">Matematica</td>
          <td headers="NOTE">
            <span class="mark" title="10.10.2026">9</span>
            <span class="mark" title="18.10.2026">10</span>
          </td>
          <td headers="TEZA">
            <span class="mark teza" title="15.12.2026">10</span>
          </td>
        </tr>
      </tbody>
    </table>
  `;

  const report = parseSemesterReport(html);
  const math = report.get("Matematica");
  assert.ok(math);
  assert.equal(math.marks.length, 3);

  const thesisMark = math.marks.find((m) => m.isThesis);
  assert.ok(thesisMark);
  assert.equal(thesisMark.value, 10);
  assert.equal(thesisMark.date, "2026-12-15");

  const catalog = buildCatalog([{ semester: 1, report }]);
  const catMath = catalog.subjects.find((s) => s.subject === "Matematica");
  assert.ok(catMath);
  assert.ok(catMath.markDetails.some((m) => m.isThesis && m.value === 10));
});

test("Qualifiers: parseMarkCell extracts qualifier marks (FB, B, S, I) with kind: qualifier", () => {
  const html = `
    <table><tr>
      <td headers="LISTA_EVALUATION">
        <span title="05.10.2026">fb</span>,
        <span title="12.10.2026">B</span>,
        <span title="19.10.2026">s</span>,
        <span title="26.10.2026">i</span>,
        <span title="02.11.2026">10</span>
      </td>
    </tr></table>
  `;
  const $ = cheerio.load(html);
  const marks = parseMarkCell($, $("td"));

  assert.equal(marks.length, 5);
  assert.deepEqual(marks[0], { kind: "qualifier", value: null, label: "FB", raw: "fb", isThesis: false, date: "2026-10-05", semester: null });
  assert.deepEqual(marks[1], { kind: "qualifier", value: null, label: "B", raw: "B", isThesis: false, date: "2026-10-12", semester: null });
  assert.deepEqual(marks[2], { kind: "qualifier", value: null, label: "S", raw: "s", isThesis: false, date: "2026-10-19", semester: null });
  assert.deepEqual(marks[3], { kind: "qualifier", value: null, label: "I", raw: "i", isThesis: false, date: "2026-10-26", semester: null });
  assert.deepEqual(marks[4], { kind: "numeric", value: 10, label: "10", raw: "10", isThesis: false, date: "2026-11-02", semester: null });
});

test("Qualifiers: parseRecentGrades handles qualifiers in recent grades list", () => {
  const html = `
    <ul>
      <li><span class="eval-date">20.10.2026</span><span class="eval-discipline">Educatia Fizica:</span><span class="grade">FB</span></li>
      <li><span class="eval-date">19.10.2026</span><span class="eval-discipline">Muzica:</span><span class="grade">B</span></li>
    </ul>
  `;

  const grades = parseRecentGrades(html, 1, "2026-10-24");
  assert.equal(grades.length, 2);
  assert.equal(grades[0].subject, "Educatia Fizica");
  assert.equal(grades[0].kind, "qualifier");
  assert.equal(grades[0].label, "FB");
  assert.equal(grades[0].value, null);

  assert.equal(grades[1].subject, "Muzica");
  assert.equal(grades[1].kind, "qualifier");
  assert.equal(grades[1].label, "B");
});

test("Qualifiers: buildCatalog excludes qualifiers from numeric averages and avoids NaN", () => {
  const html = `
    <table>
      <tr>
        <td headers="DISCIPLINA">Educatia Fizica</td>
        <td headers="LISTA_EVALUATION"><span>FB</span>, <span>B</span></td>
        <td headers="PROFESOR">Profesor Sport</td>
      </tr>
      <tr>
        <td headers="DISCIPLINA">Desen</td>
        <td headers="LISTA_EVALUATION"><span>10</span>, <span>FB</span>, <span>8</span></td>
        <td headers="PROFESOR">Profesor Desen</td>
      </tr>
    </table>
  `;

  const report = parseSemesterReport(html);
  const catalog = buildCatalog([{ semester: 1, report }]);

  const pe = catalog.subjects.find((s) => s.subject === "Educatia Fizica");
  assert.ok(pe);
  assert.equal(pe.sem1Average, null);
  assert.equal(pe.average, null);

  const art = catalog.subjects.find((s) => s.subject === "Desen");
  assert.ok(art);
  assert.equal(art.sem1Average, 9);
  assert.equal(art.average, 9);
});

test("Security: scrubLog strips passwords, tokens, cookies, and checksums", () => {
  const sampleJson = JSON.stringify({ password: "mySuperSecret123", token: "tok-abc", user: "elev1" });
  const scrubbedJson = scrubLog(sampleJson);
  assert.ok(!scrubbedJson.includes("mySuperSecret123"));
  assert.ok(scrubbedJson.includes("[REDACTED]"));

  const sampleUrl = "https://agenda.sime.md/ords/f?p=104:1:123::NO::&cs=3A9F5E12B7C80&password=secret";
  const scrubbedUrl = scrubLog(sampleUrl);
  assert.ok(!scrubbedUrl.includes("3A9F5E12B7C80"));
  assert.ok(!scrubbedUrl.includes("password=secret"));

  const sampleObj = { username: "parent1", password: "secretPassword", token: "session-xyz", cookie: "ora_wwv_apex=abc123", safeData: "valid data" };
  const scrubbedObj = scrubLog(sampleObj);
  assert.equal(scrubbedObj.password, "[REDACTED]");
  assert.equal(scrubbedObj.token, "[REDACTED]");
  assert.equal(scrubbedObj.cookie, "[REDACTED]");
  assert.equal(scrubbedObj.safeData, "valid data");
  assert.equal(scrubbedObj.username, "parent1");
});

test("Security: single fixed BROWSER_PROFILE replaces rotation", () => {
  const serverJs = fs.readFileSync(path.join(__dirname, "../server.js"), "utf-8");
  assert.ok(!serverJs.includes("FINGERPRINTS ="), "FINGERPRINTS array must be removed");
  assert.ok(!serverJs.includes("function pickFingerprint"), "pickFingerprint must be removed");
  assert.ok(BROWSER_PROFILE && typeof BROWSER_PROFILE === "object");
  assert.ok(BROWSER_PROFILE.userAgent.includes("Chrome/"));
});

test("Security: headers include X-Frame-Options and HSTS on HTTPS", async () => {
  const server = app.listen(0, "127.0.0.1");
  await new Promise((resolve) => server.once("listening", resolve));
  const { port } = server.address();
  try {
    const res = await fetch(`http://127.0.0.1:${port}/`, {
      headers: { "X-Forwarded-Proto": "https" },
    });
    assert.equal(res.headers.get("x-frame-options"), "DENY");
    assert.equal(res.headers.get("x-content-type-options"), "nosniff");
    assert.ok(res.headers.get("strict-transport-security")?.includes("max-age="));
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});

test("Security: server responses return UTF-8 in Content-Type header", async () => {
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
    await new Promise((resolve) => server.close(resolve));
  }
});

test("Sync: diffNewMarks detects new marks only", () => {
  const appJs = fs.readFileSync(path.join(__dirname, "../public/app.js"), "utf-8");
  assert.ok(appJs.includes("diffNewMarks"), "public/app.js must define diffNewMarks function");

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
            { value: 10, date: "2026-10-02" },
          ],
        },
        { semester: 2, marks: [] },
      ],
    },
    {
      subject: "Fizică",
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

test("Service Worker: server serves /sw.js with Service-Worker-Allowed header", async () => {
  const server = app.listen(0, "127.0.0.1");
  await new Promise((resolve) => server.once("listening", resolve));
  const { port } = server.address();
  try {
    const res = await fetch(`http://127.0.0.1:${port}/sw.js`);
    assert.equal(res.status, 200);
    assert.ok(res.headers.get("service-worker-allowed") === "/");
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});

test("Capture Tooling: capture diagnostic tooling helper functions operate correctly", () => {
  const mockHtml = `
    <div id="wrapper">
      <div id="region-absente" class="t-Region">
        <h2 class="t-Region-title">Absențe elev</h2>
        <table>
          <tr><th id="TH_DATA">DATA</th><th id="TH_ABS">ABSENTA</th></tr>
          <tr><td headers="TH_DATA">2026-10-14</td><td headers="TH_ABS">Istorie</td></tr>
        </table>
      </div>
    </div>
  `;
  const $ = cheerio.load(mockHtml);
  const regions = findAbsenceApexRegions($);
  assert.ok(regions.length >= 1, "Must detect region with 'absen'");

  const known = [{ date: "2026-10-14", subject: "Istorie" }];
  const calEvents = [
    {
      start: "2026-10-14T10:00:00",
      end: "2026-10-14T10:45:00",
      className: "fc-event-absence fc-absent-unexcused",
      title: "Istorie",
    },
  ];

  const highlights = scanKnownAbsenceMatches(known, calEvents, regions, []);
  assert.ok(highlights.length >= 1, "Must find match for known absence");
  const calMatch = highlights.find((h) => h.source === "calendar");
  assert.ok(calMatch);
  assert.ok(calMatch.className.includes("fc-absent-unexcused"));
});
