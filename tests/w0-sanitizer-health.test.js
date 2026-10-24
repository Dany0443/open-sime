import assert from "node:assert/strict";
import { test } from "node:test";
import { app, inspectParserHealth, sessions } from "../server.js";
import { sanitizeCapture, sanitizeHtml, sanitizeCalendarEvents } from "../scripts/sanitize-capture.mjs";

test("W0: sanitize-capture sanitizes personal data while keeping structure", () => {
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

  // Sensitive values must NOT appear in sanitized output
  assert.ok(!sanitized.includes("Ion Popescu"), "Personal student name must be sanitized");
  assert.ok(!sanitized.includes("12345"), "Student ID must be sanitized");
  assert.ok(!sanitized.includes("Maria Ciobanu"), "Teacher name must be sanitized");
  assert.ok(!sanitized.includes("Str. Stefan cel Mare 10"), "Address must be sanitized");
  assert.ok(!sanitized.includes("15.05.2009"), "Birthdate must be sanitized");
  assert.ok(!sanitized.includes("SECRET_SALT_123"), "Protected items token must be sanitized");
  assert.ok(!sanitized.includes("SECRET_CK_456"), "Item checksum must be sanitized");
  assert.ok(!sanitized.includes("REAL_CS_789"), "URL checksum must be sanitized");

  // Structure and tags must be preserved
  assert.ok(sanitized.includes('id="P1_ELEVE"'));
  assert.ok(sanitized.includes('id="P1_ELEVE_ID"'));
  assert.ok(sanitized.includes('id="t_TreeNav"'));
  assert.ok(sanitized.includes('headers="PROFESOR"'));
  assert.ok(sanitized.includes("Matematica"), "Subject name should be preserved");

  // Test calendar events JSON
  const sampleEvents = [
    {
      start: "2026-10-24T08:00:00",
      end: "2026-10-24T08:45:00",
      title: '<span class="lesson-text-wrap">Fizica</span><span class="lesson-room">402</span><span class="lesson-teacher">Gheorghe Vasile</span>',
    },
  ];
  const sanitizedJson = JSON.parse(sanitizeCapture(JSON.stringify(sampleEvents)));
  assert.ok(!sanitizedJson[0].title.includes("Gheorghe Vasile"));
  assert.ok(sanitizedJson[0].title.includes("Profesor"));
  assert.ok(sanitizedJson[0].title.includes("Fizica"));
});

test("W0: inspectParserHealth returns structured diagnostics and never leaks personal values", () => {
  const sampleHtml =
    '<input id="P1_ELEVE_ID" value="101">' +
    '<input id="P1_ELEVE" value="Ion Ciobanu">' +
    '<input id="P1_CLASS_NAME" value="11B">' +
    '<input id="P1_INSTITUTION_NAME" value="Liceul Gheorghe Asachi">' +
    '<input id="P1_DIRIGINTE" value="Maria Rusu">' +
    '<input id="P1_MOTIVATED" value="2">' +
    '<input id="P1_UNMOTIVATED" value="1">' +
    '<input id="P1_LAST_3_EVAL_LIST" value="&lt;ul&gt;&lt;li&gt;20.10.2026 Matematica: 10&lt;/li&gt;&lt;/ul&gt;">' +
    '<div id="SEMESTER_REG_1"><table class="t-Report-report"><tr>' +
    '<td headers="DISCIPLINA">Matematica</td>' +
    '<td headers="LISTA_EVALUATION"><span title="20.10.2026">10</span></td>' +
    '<td headers="MED_SEM">10,00</td>' +
    '<td headers="PROFESOR">Vasile Lupu</td>' +
    '</tr></table></div>' +
    '<script id="P1_CALENDAR_EVENTS" type="application/json">' +
    JSON.stringify([
      {
        start: "2026-10-24T08:30:00",
        end: "2026-10-24T09:15:00",
        title: '<span class="lesson-text-wrap">Matematica</span><span class="lesson-room">204</span><span class="lesson-teacher">Vasile Lupu</span>',
      },
    ]) +
    '</script>';

  const health = inspectParserHealth(sampleHtml);

  // Check required sections exist
  assert.ok(health.students);
  assert.ok(health.grades);
  assert.ok(health.catalog);
  assert.ok(health.absencesSummary);
  assert.ok(health.calendar);
  assert.ok(health.teachers);

  // Check counts and matched selectors
  assert.equal(health.students.count, 1);
  assert.ok(health.students.selectorsMatched.includes("#P1_ELEVE_ID"));
  assert.deepEqual(health.students.fieldsFound.sort(), ["className", "fullName", "id", "schoolName"]);

  assert.equal(health.grades.count, 1);
  assert.equal(health.catalog.count, 1);
  assert.equal(health.absencesSummary.count, 3);
  assert.equal(health.calendar.count, 1);
  assert.equal(health.teachers.count, 2);

  // Verify NO personal values leaked anywhere in the JSON output
  const str = JSON.stringify(health);
  assert.ok(!str.includes("Ion Ciobanu"), "No student name in health output");
  assert.ok(!str.includes("Maria Rusu"), "No teacher name in health output");
  assert.ok(!str.includes("Vasile Lupu"), "No teacher name in health output");
  assert.ok(!str.includes("Gheorghe Asachi"), "No school name in health output");
});

test("W0: GET /api/debug/parser-health is disabled unless SIME_DEBUG=1", async () => {
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
    // Without token, returns 401
    const noAuthRes = await fetch(`${baseUrl}/api/debug/parser-health`);
    assert.equal(noAuthRes.status, 401);
  } finally {
    if (origEnv !== undefined) process.env.SIME_DEBUG = origEnv;
    else delete process.env.SIME_DEBUG;
    await new Promise((resolve) => server.close(resolve));
  }
});
