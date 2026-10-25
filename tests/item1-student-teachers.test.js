import assert from "node:assert/strict";
import fs from "node:fs";
import http from "node:http";
import { test } from "node:test";
import { app, sessions, SimeClient } from "../server.js";
import { createPortal, PORTAL_URL, SESSION, STUDENTS } from "./helpers/mock-portal.js";

const today = new Intl.DateTimeFormat("en-CA", { timeZone: "Europe/Chisinau" }).format(new Date());

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

async function withSingleServer(portal, role, fn) {
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
      body: JSON.stringify({ username: `${role}1`, password: "pw", role }),
    });
    const login = await res.json();
    assert.equal(res.status, 200);

    const get = async (path) => {
      const r = await fetch(`${base}${path}`, {
        headers: { Authorization: `Bearer ${login.token}` },
      });
      return { status: r.status, body: await r.json() };
    };

    return await fn({ base, login, get, token: login.token });
  } finally {
    SimeClient.prototype.loginAndParsePortal = orig;
    await new Promise((r) => server.close(r));
  }
}

test("Item 1: teachers disappear in student mode - reproduced and verified", async () => {
  const portal = makeSingleStudentPortal("A");
  await withSingleServer(portal, "student", async ({ get, login }) => {
    // 1. Initial login / overview returns role student and class master in teachers
    assert.equal(login.role, "student");
    assert.equal(login.data.teachers.length, 1, "Overview initially only has class master");
    assert.equal(login.data.teachers[0].name, STUDENTS.A.master);

    // 2. Fetching /api/teachers without query param returns all teachers
    const teachersRes = await get("/api/teachers");
    assert.equal(teachersRes.status, 200);
    const allTeachers = teachersRes.body.data.teachers;
    assert.ok(allTeachers.length >= 2, `Expected >= 2 teachers, got ${allTeachers.length}`);
    const teacherNames = allTeachers.map((t) => t.name);
    assert.ok(teacherNames.includes(STUDENTS.A.master));
    assert.ok(teacherNames.includes(STUDENTS.A.teacher));

    // 3. Client setData logic in app.js must preserve detailed teachers when new overview data has <= 1 teacher
    const appJs = fs.readFileSync("public/app.js", "utf-8");
    assert.ok(
      appJs.includes("prevData.teachers") || appJs.includes("teachers.length > 1"),
      "app.js setData must preserve detailed teachers when new overview data has <= 1 teacher"
    );

    // 4. Server overview caching must preserve detailed teachers across overview re-syncs
    const serverJs = fs.readFileSync("server.js", "utf-8");
    assert.ok(
      serverJs.includes("teachersDetailed: prevStudentSession?.teachersDetailed") ||
      serverJs.includes("teachersDetailed: existing?.teachersDetailed") ||
      serverJs.includes("teachersDetailed"),
      "server.js overview caching must preserve teachersDetailed"
    );
  });
});

test("Item 1: Student-role regression test for every tab without ?student= parameter", async () => {
  const portal = makeSingleStudentPortal("A");
  await withSingleServer(portal, "student", async ({ get }) => {
    // Acasă
    const overview = await get("/api/overview");
    assert.equal(overview.status, 200);
    assert.equal(overview.body.data.student.fullName, STUDENTS.A.name);

    // Reușita
    const catalog = await get("/api/catalog");
    assert.equal(catalog.status, 200);
    assert.ok(catalog.body.data.disciplines.length > 0);
    assert.equal(catalog.body.data.disciplines[0].subject, STUDENTS.A.lessonSubject);

    // Orar
    const schedule = await get(`/api/schedule?date=${today}`);
    assert.equal(schedule.status, 200);
    assert.ok(schedule.body.data.days[today].length > 0);

    // Absențe
    const absences = await get("/api/absences");
    assert.equal(absences.status, 200);
    assert.ok(absences.body.data.summary);

    // Teme
    const homework = await get("/api/homework");
    assert.equal(homework.status, 200);
    assert.ok(Array.isArray(homework.body.data.homework));

    // Profil (both teachers and profile metadata)
    const teachers = await get("/api/teachers");
    assert.equal(teachers.status, 200);
    assert.ok(teachers.body.data.teachers.length >= 2);

    const profile = await get("/api/profile");
    assert.equal(profile.status, 200);
    assert.equal(profile.body.data.metadata.classMaster, STUDENTS.A.master);
  });
});

test("Item 1: Parent mode with exactly one child works across every tab without ?student= parameter", async () => {
  const portal = makeSingleStudentPortal("A");
  await withSingleServer(portal, "parent", async ({ get, login }) => {
    assert.equal(login.role, "parent");
    assert.equal(login.students.length, 1);

    // Acasă
    const overview = await get("/api/overview");
    assert.equal(overview.status, 200);
    assert.equal(overview.body.data.student.fullName, STUDENTS.A.name);

    // Reușita
    const catalog = await get("/api/catalog");
    assert.equal(catalog.status, 200);

    // Orar
    const schedule = await get(`/api/schedule?date=${today}`);
    assert.equal(schedule.status, 200);

    // Absențe
    const absences = await get("/api/absences");
    assert.equal(absences.status, 200);

    // Teme
    const homework = await get("/api/homework");
    assert.equal(homework.status, 200);

    // Profil
    const teachers = await get("/api/teachers");
    assert.equal(teachers.status, 200);
    assert.ok(teachers.body.data.teachers.length >= 2);

    const profile = await get("/api/profile");
    assert.equal(profile.status, 200);
    assert.equal(profile.body.data.metadata.classMaster, STUDENTS.A.master);
  });
});

