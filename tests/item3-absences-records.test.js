import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import vm from "node:vm";
import {
  absenceRecords,
  buildSemesterAbsences,
  extractHtmlAbsenceRecords,
  parseCalendarEvents,
} from "../server.js";
import {
  findAbsenceApexRegions,
  scanKnownAbsenceMatches,
} from "../scripts/capture-absence-sources.mjs";
import { getString, STRINGS } from "../public/strings.js";
import * as cheerio from "cheerio";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

test("Item 3: absence record schema contains required fields and normalized status", () => {
  const events = [
    {
      start: "2026-10-14T08:30:00",
      end: "2026-10-14T09:15:00",
      title: '<span class="absent-motivat lesson-text-wrap">Matematică</span><span class="lesson-room">204</span><span class="lesson-teacher">Ion Creangă</span>',
    },
    {
      start: "2026-10-14T09:25:00",
      end: "2026-10-14T10:10:00",
      title: '<span class="absent-nemotivat lesson-text-wrap">Fizică</span><span class="lesson-room">101</span><span class="lesson-teacher">Mihai Eminescu</span>',
    },
  ];

  const entries = parseCalendarEvents(events);
  const records = absenceRecords(entries, "STUDENT_42");

  assert.equal(records.length, 2);

  for (const r of records) {
    // Check all required schema properties exist
    assert.ok("studentKey" in r, "Must have studentKey");
    assert.ok("date" in r, "Must have date");
    assert.ok("lessonNumber" in r, "Must have lessonNumber");
    assert.ok("startTime" in r, "Must have startTime");
    assert.ok("endTime" in r, "Must have endTime");
    assert.ok("subject" in r, "Must have subject");
    assert.ok("room" in r, "Must have room");
    assert.ok("teacher" in r, "Must have teacher");
    assert.ok("status" in r, "Must have status");
    assert.ok("reason" in r, "Must have reason");
    assert.ok("semester" in r, "Must have semester");

    // Status must be one of "excused" | "unexcused" | "unknown"
    assert.ok(
      ["excused", "unexcused", "unknown"].includes(r.status),
      `Status must be excused, unexcused, or unknown; got: ${r.status}`
    );
    assert.equal(r.studentKey, "STUDENT_42");
  }

  assert.equal(records[0].status, "unexcused"); // 09:25
  assert.equal(records[1].status, "excused");   // 08:30
  assert.equal(records[0].room, "101");
  assert.equal(records[1].room, "204");
});

test("Item 3: HTML absence table parser extracts records with schema", () => {
  const html = `
    <table>
      <thead>
        <tr>
          <th id="DATA">DATA</th>
          <th id="DISCIPLINA">DISCIPLINA</th>
          <th id="STATUT">STATUT</th>
          <th id="ORA">ORA</th>
          <th id="SALA">SALA</th>
          <th id="PROFESOR">PROFESOR</th>
          <th id="MOTIV">MOTIV</th>
        </tr>
      </thead>
      <tbody>
        <tr>
          <td headers="DATA">15.10.2026</td>
          <td headers="DISCIPLINA">Chimia</td>
          <td headers="STATUT">Motivat</td>
          <td headers="ORA">3</td>
          <td headers="SALA">305</td>
          <td headers="PROFESOR">Ana Aslan</td>
          <td headers="MOTIV">Certificat medical</td>
        </tr>
      </tbody>
    </table>
  `;

  const $ = cheerio.load(html);
  const records = extractHtmlAbsenceRecords($, "2026-10-25", "STUDENT_99");

  assert.equal(records.length, 1);
  const r = records[0];
  assert.equal(r.studentKey, "STUDENT_99");
  assert.equal(r.date, "2026-10-15");
  assert.equal(r.subject, "Chimia");
  assert.equal(r.status, "excused");
  assert.equal(r.lessonNumber, 3);
  assert.equal(r.room, "305");
  assert.equal(r.teacher, "Ana Aslan");
  assert.equal(r.reason, "Certificat medical");
});

test("Item 3: absence record reconciliation does not invent fake records", () => {
  const summary = {
    total: 3,
    sem1Excused: 2,
    sem1Unexcused: 1,
    sem2Excused: 0,
    sem2Unexcused: 0,
  };
  const prodAbsences = buildSemesterAbsences(summary, [], "STUDENT_1");
  assert.equal(prodAbsences.records.length, 0, "Portal reports totals but no hourly details, must not invent records");
});

test("Item 3: capture diagnostic tooling helper functions operate correctly", () => {
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

test("Item 3: UI Absențe tab renders synthetic fixture with breakdown by subject and tokens", () => {
  // Test fixture: 3 motivated, 2 unmotivated across 2 subjects (Matematică: 2 mot, 1 nemot; Fizică: 1 mot, 1 nemot)
  const syntheticRecords = [
    {
      studentKey: "101",
      date: "2026-10-14",
      lessonNumber: 1,
      startTime: "08:30",
      endTime: "09:15",
      subject: "Matematică",
      room: "204",
      teacher: "Prof. Mate",
      status: "excused",
      reason: "Medical",
      semester: 1,
    },
    {
      studentKey: "101",
      date: "2026-10-15",
      lessonNumber: 2,
      startTime: "09:25",
      endTime: "10:10",
      subject: "Matematică",
      room: "204",
      teacher: "Prof. Mate",
      status: "excused",
      reason: "Olimpiadă",
      semester: 1,
    },
    {
      studentKey: "101",
      date: "2026-10-16",
      lessonNumber: 3,
      startTime: "10:20",
      endTime: "11:05",
      subject: "Matematică",
      room: "204",
      teacher: "Prof. Mate",
      status: "unexcused",
      reason: null,
      semester: 1,
    },
    {
      studentKey: "101",
      date: "2026-10-19",
      lessonNumber: 1,
      startTime: "08:30",
      endTime: "09:15",
      subject: "Fizică",
      room: "101",
      teacher: "Prof. Fizică",
      status: "excused",
      reason: "Învoire",
      semester: 1,
    },
    {
      studentKey: "101",
      date: "2026-10-20",
      lessonNumber: 4,
      startTime: "11:15",
      endTime: "12:00",
      subject: "Fizică",
      room: "101",
      teacher: "Prof. Fizică",
      status: "unexcused",
      reason: null,
      semester: 1,
    },
  ];

  class Node {
    constructor(nodeType, nodeName) {
      this.nodeType = nodeType;
      this.nodeName = nodeName;
      this.childNodes = [];
    }
    appendChild(child) {
      this.childNodes.push(child);
      return child;
    }
    prepend(child) {
      this.childNodes.unshift(child);
      return child;
    }
    replaceChildren(...nodes) {
      this.childNodes = [];
      for (const n of nodes) {
        if (n instanceof Node) this.childNodes.push(n);
        else this.childNodes.push(new Text(String(n)));
      }
    }
    get textContent() {
      return this.childNodes.map((c) => c.textContent || c.nodeValue || "").join("");
    }
    set textContent(v) {
      this.childNodes = [new Text(String(v))];
    }
  }

  class Element extends Node {
    constructor(tag) {
      super(1, tag.toUpperCase());
      this.tagName = tag.toUpperCase();
      this.attributes = {};
      this.dataset = {};
      this.style = { setProperty() {}, getPropertyValue() { return "#ffffff"; } };
      this.classList = {
        classes: new Set(),
        add(...cls) { for (const c of cls) this.classes.add(c); },
        remove(...cls) { for (const c of cls) this.classes.delete(c); },
        toggle(c, force) {
          if (force === true) this.classes.add(c);
          else if (force === false) this.classes.delete(c);
          else if (this.classes.has(c)) this.classes.delete(c);
          else this.classes.add(c);
        },
        contains(c) { return this.classes.has(c); },
      };
    }
    setAttribute(k, v) { this.attributes[k] = String(v); }
    getAttribute(k) { return this.attributes[k]; }
    addEventListener() {}
    removeEventListener() {}
    querySelectorAll() { return []; }
    querySelector() { return null; }
  }

  class Text extends Node {
    constructor(text) {
      super(3, "#text");
      this.nodeValue = String(text);
    }
    get textContent() { return this.nodeValue; }
  }

  const elements = {};
  const doc = {
    createElement(tag) { return new Element(tag); },
    createElementNS(ns, tag) { return new Element(tag); },
    createTextNode(text) { return new Text(text); },
    getElementById(id) {
      if (!elements[id]) elements[id] = new Element("div");
      return elements[id];
    },
    querySelectorAll() { return []; },
    querySelector() { return null; },
    addEventListener() {},
    removeEventListener() {},
    documentElement: new Element("html"),
  };

  const sandbox = {
    Node,
    Element,
    Text,
    document: doc,
    doc,
    elements,
    window: {
      Node,
      Element,
      Text,
      document: doc,
      location: { hash: "", href: "http://localhost/" },
      scrollTo() {},
      setInterval() {},
      setTimeout() {},
      clearTimeout() {},
      addEventListener() {},
      matchMedia() { return { matches: false, addEventListener() {} }; },
      getComputedStyle() { return { getPropertyValue() { return "#ffffff"; } }; },
      localStorage: { getItem() { return null; }, setItem() {}, removeItem() {} },
    },
    navigator: {},
    fetch: async () => ({ status: 404, ok: false, json: async () => ({}) }),
    Intl,
    Date,
    Math,
    String,
    Number,
    Boolean,
    Array,
    Object,
    Map,
    Set,
    getString,
    STRINGS,
    console,
  };
  sandbox.window.document = doc;
  sandbox.globalThis = sandbox;

  const appCode = fs.readFileSync(path.join(__dirname, "../public/app.js"), "utf8");
  vm.runInNewContext(
    appCode + "\n; globalThis.appEnv = { state, renderAbsences, absenceSemester, elements, setChildren };",
    sandbox
  );

  const { state, renderAbsences } = sandbox.appEnv;
  state.semester = 1;
  state.data = {
    student: { id: "101", fullName: "Ion Popescu" },
    absences: {
      summary: { total: 5, sem1Excused: 3, sem1Unexcused: 2, totalExcused: 3, totalUnexcused: 2 },
      sem1: {
        total: 5,
        motivate: 3,
        nemotivate: 2,
        records: syntheticRecords,
      },
      sem2: { total: 0, motivate: 0, nemotivate: 0, records: [] },
      records: syntheticRecords,
    },
  };

  renderAbsences();

  const statsEl = elements["abs-stats"];
  const listEl = elements["abs-list"];

  assert.ok(statsEl, "abs-stats element must exist");
  assert.ok(listEl, "abs-list element must exist");

  const statsText = statsEl.textContent;
  assert.ok(statsText.includes("Total") && statsText.includes("5"), "Stats must show total 5");
  assert.ok(statsText.includes("Motivate") && statsText.includes("3"), "Stats must show motivate 3");
  assert.ok(statsText.includes("Nemotivate") && statsText.includes("2"), "Stats must show nemotivate 2");

  const listText = listEl.textContent;
  assert.ok(listText.includes("Pe discipline"), "Must render Pe discipline section");
  assert.ok(listText.includes("Cronologic"), "Must render Cronologic section");
  assert.ok(listText.includes("Matematică"), "Must show Matematică");
  assert.ok(listText.includes("Fizică"), "Must show Fizică");

  // Verify Matematică (3 absences) appears before Fizică (2 absences) in the subject breakdown
  const matPos = listText.indexOf("Matematică");
  const fizPos = listText.indexOf("Fizică");
  assert.ok(matPos < fizPos, "Subject with most absences (Matematică) must appear before Fizică in breakdown");

  // Verify CSS tokens in style.css for absence status chips and cards
  const css = fs.readFileSync(path.join(__dirname, "../public/style.css"), "utf8");
  assert.ok(css.includes(".chip-success"), "style.css must define .chip-success");
  assert.ok(css.includes(".chip-error"), "style.css must define .chip-error");
  assert.ok(css.includes("--success-container"), "style.css must use --success-container token");
  assert.ok(css.includes("--error-container"), "style.css must use --error-container token");
});
