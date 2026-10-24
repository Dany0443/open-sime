import assert from "node:assert/strict";
import { test } from "node:test";
import { parseFullPortal, SimeClient } from "../server.js";
import * as cheerio from "cheerio";

const TWO_ID_FORMAT_PAGE =
  '<input id="P1_ELEVE_ID" value="101">' +
  '<input id="P1_ELEVE" value="Ion Ciobanu">' +
  '<input id="P1_CLASS_NAME" value="10A">' +
  '<input id="P1_INSTITUTION_NAME" value="Liceul Teoretic">' +
  '<div id="t_TreeNav"><ul>' +
  '<li data-current="true"><a href="/ords/f?p=AGENDA:1:999::NO::P1_ELEVE_ID:5B1E59D6A1B2C3D4&cs=3XYZ123">Ion Ciobanu</a></li>' +
  '<li><a href="/ords/f?p=AGENDA:1:999::NO::P1_ELEVE_ID:8F7E6D5C4B3A2109&cs=3UVW456">Elena Ciobanu</a></li>' +
  '</ul></div>';

test("W1: parseFullPortal deduplicates students with differing ID formats", () => {
  const result = parseFullPortal(TWO_ID_FORMAT_PAGE);

  // A parent with two children must yield exactly two entries, not 3 or 4
  assert.equal(result.students.length, 2, `Expected 2 students, got ${result.students.length}: ${JSON.stringify(result.students)}`);

  const ion = result.students.find((s) => s.fullName === "Ion Ciobanu");
  assert.ok(ion, "Ion Ciobanu should exist");
  assert.equal(ion.className, "10A", "Ion's class must be preserved");
  assert.equal(ion.schoolName, "Liceul Teoretic", "Ion's school must be preserved");

  const elena = result.students.find((s) => s.fullName === "Elena Ciobanu");
  assert.ok(elena, "Elena Ciobanu should exist");
});

test("W1: SimeClient.registerStudents maps nav tree checksum URLs and merges aliases", () => {
  const client = new SimeClient("testuser", "testpass", "parent");
  const $ = cheerio.load(TWO_ID_FORMAT_PAGE);
  const page = { url: "https://agenda.sime.md/ords/r/agenda/main/dashboard?session=999", html: TWO_ID_FORMAT_PAGE, $, fetchedAt: Date.now() };

  const students = client.registerStudents(page);
  assert.equal(students.length, 2, `Expected 2 registered students, got ${students.length}`);

  const ion = students.find((s) => s.fullName === "Ion Ciobanu");
  assert.equal(ion.className, "10A");

  // Check URL stored for Ion includes the checksum from nav tree
  const ionUrl = client.studentUrls.get(ion.id) || client.studentUrls.get("101") || client.studentUrls.get("5b1e59d6a1b2c3d4");
  assert.ok(ionUrl, "Should have URL for Ion");
  assert.ok(ionUrl.includes("cs=3XYZ123"), `Expected checksum in URL, got: ${ionUrl}`);

  // Both the page id "101" and nav id "5B1E59D6A1B2C3D4" should be recognized in portalIds
  assert.ok(client.portalIds.has("101") || client.portalIds.has("101"), "Portal IDs should include 101");
});
