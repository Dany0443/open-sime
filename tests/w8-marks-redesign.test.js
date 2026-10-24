import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { test } from "node:test";
import { buildCatalog, parseSemesterReport } from "../server.js";

test("W8: parseSemesterReport detects thesis marks and buildCatalog passes them through", () => {
  const html = `
    <table>
      <thead>
        <tr>
          <th>DISCIPLINA</th>
          <th>NOTE</th>
          <th>TEZA</th>
        </tr>
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
  assert.ok(thesisMark, "Thesis mark must be marked with isThesis: true");
  assert.equal(thesisMark.value, 10);
  assert.equal(thesisMark.date, "2026-12-15");

  const catalog = buildCatalog([{ semester: 1, report }]);
  const catMath = catalog.subjects.find((s) => s.subject === "Matematica");
  assert.ok(catMath);
  assert.ok(catMath.markDetails.some((m) => m.isThesis && m.value === 10));
});

test("W8: app.js renderMarks contains no average stats or average columns in table header", () => {
  const appJs = fs.readFileSync(path.resolve("public/app.js"), "utf8");

  // Locate renderMarks function
  const renderMarksMatch = appJs.match(/function renderMarks\(\)\s*\{([\s\S]*?)\n\}/);
  assert.ok(renderMarksMatch, "renderMarks must exist in app.js");
  const fnBody = renderMarksMatch[1];

  // Verify averages removed from summary and table
  assert.ok(
    !fnBody.includes('stat("Media general'),
    "renderMarks must not render Media generala"
  );
  assert.ok(
    !fnBody.includes('Medie Sem'),
    "renderMarks table headers must not include Medie Sem"
  );
  assert.ok(
    !fnBody.includes('Medie Anual'),
    "renderMarks table headers must not include Medie Anuala"
  );

  // Verify responsive table and cards surfaces are both constructed
  assert.ok(fnBody.includes("marks-table-surface"), "Must generate marks-table-surface");
  assert.ok(fnBody.includes("marks-cards-surface"), "Must generate marks-cards-surface");
});

test("W8: score function styles thesis marks with is-thesis and thesis-tag", () => {
  const appJs = fs.readFileSync(path.resolve("public/app.js"), "utf8");
  const scoreMatch = appJs.match(/function score\(value, size, date, isThesis[\s\S]*?\{([\s\S]*?)\n\}/);
  assert.ok(scoreMatch, "score function with isThesis parameter must exist");
  const fnBody = scoreMatch[1];

  assert.ok(fnBody.includes("is-thesis"), "Must toggle is-thesis class");
  assert.ok(fnBody.includes("thesis-tag"), "Must include thesis-tag element");
});
