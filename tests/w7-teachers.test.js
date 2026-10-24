import assert from "node:assert/strict";
import { test } from "node:test";
import { buildTeachers, SimeClient } from "../server.js";

test("W7: buildTeachers merges surname + initial with full name and keeps fuller name", () => {
  const metadata = { classMaster: "Popescu Maria" };

  const catalogSubjects = [
    { subject: "Matematica", teacher: "Ciobanu I." },
    { subject: "Fizica", teacher: "Rusu Elena" },
  ];

  const calendarEntries = [
    // Same math teacher from catalog, but with full name
    { subject: "Matematica", teacher: "Ciobanu Ion" },
    { subject: "Informatica", teacher: "Ciobanu Ion" },
    // Diriginte teaching Romana
    { subject: "Limba Romana", teacher: "Prof. Popescu M." },
  ];

  const teachers = buildTeachers(calendarEntries, metadata, catalogSubjects);

  // Diriginte should be first and have note: "Diriginte"
  assert.equal(teachers[0].name, "Popescu Maria");
  assert.equal(teachers[0].note, "Diriginte");
  assert.ok(teachers[0].subjects.includes("Limba Romana"));

  // Ciobanu Ion should be merged from "Ciobanu I." and "Ciobanu Ion"
  const ciobanu = teachers.find((t) => t.name === "Ciobanu Ion");
  assert.ok(ciobanu, "Should use full name Ciobanu Ion instead of Ciobanu I.");
  assert.equal(ciobanu.lessons, 2);
  assert.ok(ciobanu.subjects.includes("Matematica"));
  assert.ok(ciobanu.subjects.includes("Informatica"));

  // Rusu Elena from catalog
  const rusu = teachers.find((t) => t.name === "Rusu Elena");
  assert.ok(rusu);
  assert.ok(rusu.subjects.includes("Fizica"));
  assert.equal(rusu.lessons, 0);

  // Assert no duplicate Ciobanu entries exist
  const ciobanuEntries = teachers.filter((t) => t.name.includes("Ciobanu"));
  assert.equal(ciobanuEntries.length, 1);
});

test("W7: buildTeachers distinguishes teachers with same surname but different initials", () => {
  const entries = [
    { subject: "Istorie", teacher: "Moraru Vasile" },
    { subject: "Geografie", teacher: "Moraru Angela" },
  ];

  const teachers = buildTeachers(entries);
  assert.equal(teachers.length, 2);
  assert.ok(teachers.some((t) => t.name === "Moraru Vasile"));
  assert.ok(teachers.some((t) => t.name === "Moraru Angela"));
});

test("W7: getTeachers aggregates from metadata, calendar scan, and catalog", async () => {
  const client = new SimeClient("user1", "pwd", "student");

  client.loadStudent = async () => {
    const cheerio = await import("cheerio");
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

  // Diriginte Grecu Tatiana (merged with Grecu T.)
  const diriginte = teachers.find((t) => t.note === "Diriginte");
  assert.ok(diriginte);
  assert.equal(diriginte.name, "Grecu Tatiana");
  assert.ok(diriginte.subjects.includes("Biologie"));

  // Stati Andrei
  assert.ok(teachers.some((t) => t.name === "Stati Andrei" && t.subjects.includes("Chimie")));

  // Smith John
  assert.ok(teachers.some((t) => t.name === "Smith John" && t.subjects.includes("Engleza")));
});
