import assert from "node:assert/strict";
import { test } from "node:test";
import { buildCatalog, parseMarkCell, parseRecentGrades, parseSemesterReport } from "../server.js";

test("W9: parseMarkCell extracts qualifier marks (FB, B, S, I) with kind: qualifier", async () => {
  const cheerio = await import("cheerio");
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

  assert.deepEqual(marks[0], {
    kind: "qualifier",
    value: null,
    label: "FB",
    raw: "fb",
    isThesis: false,
    date: "2026-10-05",
    semester: null,
  });

  assert.deepEqual(marks[1], {
    kind: "qualifier",
    value: null,
    label: "B",
    raw: "B",
    isThesis: false,
    date: "2026-10-12",
    semester: null,
  });

  assert.deepEqual(marks[2], {
    kind: "qualifier",
    value: null,
    label: "S",
    raw: "s",
    isThesis: false,
    date: "2026-10-19",
    semester: null,
  });

  assert.deepEqual(marks[3], {
    kind: "qualifier",
    value: null,
    label: "I",
    raw: "i",
    isThesis: false,
    date: "2026-10-26",
    semester: null,
  });

  assert.deepEqual(marks[4], {
    kind: "numeric",
    value: 10,
    label: "10",
    raw: "10",
    isThesis: false,
    date: "2026-11-02",
    semester: null,
  });
});

test("W9: parseRecentGrades handles qualifiers in recent grades list", () => {
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

test("W9: buildCatalog excludes qualifiers from numeric averages and avoids NaN", () => {
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

  // Educatia Fizica has only qualifiers -> average is null (not NaN)
  const pe = catalog.subjects.find((s) => s.subject === "Educatia Fizica");
  assert.ok(pe);
  assert.equal(pe.sem1Average, null);
  assert.equal(pe.average, null);

  // Desen has 10, FB, 8 -> average is 9 (only 10 and 8 averaged)
  const art = catalog.subjects.find((s) => s.subject === "Desen");
  assert.ok(art);
  assert.equal(art.sem1Average, 9);
  assert.equal(art.average, 9);
});
