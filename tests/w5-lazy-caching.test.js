import assert from "node:assert/strict";
import http from "node:http";
import { test } from "node:test";
import { app, SimeClient } from "../server.js";

test("W5: overview does not make heavy calendar calls, tab endpoints fetch detailed data and cache properly", async () => {
  const server = http.createServer(app);
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const port = server.address().port;
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

  SimeClient.prototype.calendarRange = async function (...args) {
    calendarRangeCalls += 1;
    return origCalendarRange.apply(this, args);
  };

  SimeClient.prototype.getAbsenceSummary = async function (studentId) {
    return {
      presencePercent: 100,
      sem1Excused: 1,
      sem1Unexcused: 0,
      sem2Excused: 0,
      sem2Unexcused: 0,
      totalExcused: 1,
      totalUnexcused: 0,
      total: 1,
    };
  };

  SimeClient.prototype.getAbsenceRecords = async function (studentId) {
    getAbsenceRecordsCalls += 1;
    return [
      {
        date: "2026-10-10",
        startLocal: "08:30",
        endLocal: "09:15",
        subject: "Matematica",
        discipline: "Matematica",
        status: "motivat",
        semester: 1,
      },
    ];
  };

  SimeClient.prototype.getTeachers = async function (studentId) {
    getTeachersCalls += 1;
    return [
      { name: "Profesor A", subjects: ["Matematica"], lessons: 4, note: null },
      { name: "Profesor B (Diriginte)", subjects: ["Romana"], lessons: 5, note: "Diriginte" },
    ];
  };

  SimeClient.prototype.getWeek = async function (studentId, isoDate) {
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
          records: [], // Empty records in overview
          sem1: { total: 1, motivate: 1, nemotivate: 0, records: [] },
          sem2: { total: 0, motivate: 0, nemotivate: 0, records: [] },
        },
        teachers: [{ name: "Profesor B (Diriginte)", note: "Diriginte", subjects: [] }], // Only class master
        events: [],
      },
    };
  };

  try {
    // 1. Login
    const loginRes = await fetch(`${baseUrl}/api/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: "student1", password: "pwd", role: "student" }),
    });
    assert.equal(loginRes.status, 200);
    const loginData = await loginRes.json();
    const token = loginData.token;

    // 2. Fetch overview: should NOT have made heavy calendarRange or getAbsenceRecords or getTeachers calls
    const ovRes = await fetch(`${baseUrl}/api/overview`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    assert.equal(ovRes.status, 200);
    assert.equal(calendarRangeCalls, 0, "Overview must not make calendarRange calls");
    assert.equal(getAbsenceRecordsCalls, 0, "Overview must not make getAbsenceRecords calls");
    assert.equal(getTeachersCalls, 0, "Overview must not make getTeachers calls");

    // 3. Fetch /api/absences: should call getAbsenceRecords and return detailed records
    const absRes1 = await fetch(`${baseUrl}/api/absences`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    assert.equal(absRes1.status, 200);
    const absData1 = await absRes1.json();
    assert.equal(getAbsenceRecordsCalls, 1, "First absences fetch must call getAbsenceRecords");
    assert.equal(absData1.data.detail, true, "Returned absences must indicate detail: true");
    assert.equal(absData1.data.records.length, 1, "Detailed absences records must be returned");

    // Repeat absences fetch within TTL -> should use cache, count stays 1
    const absRes2 = await fetch(`${baseUrl}/api/absences`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    assert.equal(absRes2.status, 200);
    assert.equal(getAbsenceRecordsCalls, 1, "Second absences fetch within TTL must use cache");

    // 4. Fetch /api/teachers: should call getTeachers (not just return class master shortcut)
    const teachersRes1 = await fetch(`${baseUrl}/api/teachers`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    assert.equal(teachersRes1.status, 200);
    const teachersData1 = await teachersRes1.json();
    assert.equal(getTeachersCalls, 1, "First teachers fetch must call getTeachers");
    assert.equal(teachersData1.data.teachers.length, 2, "Must return full list of teachers");

    // Repeat teachers fetch within TTL -> should use cache, count stays 1
    const teachersRes2 = await fetch(`${baseUrl}/api/teachers`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    assert.equal(teachersRes2.status, 200);
    assert.equal(getTeachersCalls, 1, "Second teachers fetch within TTL must use cache");

    // 5. Fetch /api/schedule: should call getWeek
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
    server.close();
  }
});
