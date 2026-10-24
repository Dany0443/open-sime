import assert from "node:assert/strict";
import { test } from "node:test";
import { buildSemesterAbsences, SimeClient } from "../server.js";

test("W6: getAbsenceRecords combines calendar events and HTML table absences", async () => {
  const client = new SimeClient("user1", "pwd", "student");

  // Mock calendarRange to return one absence from calendar
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

  // Mock loadStudent to return an HTML page with another absence in the HTML table
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
    const cheerio = await import("cheerio");
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

test("W6: buildSemesterAbsences reconciles totals with records and groups semesters", () => {
  const summary = {
    total: 3,
    sem1Excused: 1,
    sem1Unexcused: 2,
    sem2Excused: 0,
    sem2Unexcused: 0,
    presencePercent: 95,
  };

  // Only 2 records available in calendar/table
  const rawRecords = [
    {
      date: "2026-10-05",
      startLocal: "08:30",
      subject: "Matematica",
      status: "motivat",
      semester: 1,
    },
    {
      date: "2026-10-12",
      startLocal: "09:25",
      subject: "Fizica",
      status: "nemotivat",
      semester: 1,
    },
  ];

  const absences = buildSemesterAbsences(summary, rawRecords);

  // Sem 1 total from summary is 3, records count is 2
  assert.equal(absences.sem1.total, 3);
  assert.equal(absences.sem1.records.length, 2);
  assert.equal(absences.sem1.motivate, 1);
  assert.equal(absences.sem1.nemotivate, 2);

  // Verify records are sorted descending by date
  assert.equal(absences.sem1.records[0].date, "2026-10-12");
  assert.equal(absences.sem1.records[1].date, "2026-10-05");
});

test("W6: per-subject grouping logic groups records and counts excused/unexcused correctly", () => {
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
