import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import vm from "node:vm";
import { getString, STRINGS } from "../public/strings.js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

function createMockDom() {
  class Node {
    constructor(nodeType, nodeName) {
      this.nodeType = nodeType;
      this.nodeName = nodeName;
      this.childNodes = [];
      this.parentNode = null;
    }
    appendChild(child) {
      child.parentNode = this;
      this.childNodes.push(child);
      return child;
    }
    prepend(child) {
      child.parentNode = this;
      this.childNodes.unshift(child);
      return child;
    }
    replaceChildren(...nodes) {
      for (const c of this.childNodes) c.parentNode = null;
      this.childNodes = [];
      for (const n of nodes) {
        if (n instanceof Node) {
          n.parentNode = this;
          this.childNodes.push(n);
        } else {
          // Standard DOM ToString coercion
          const t = new Text(String(n));
          t.parentNode = this;
          this.childNodes.push(t);
        }
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
      this.style = {
        setProperty() {},
        getPropertyValue() {
          return "#ffffff";
        },
      };
      this.dataset = {};
      this.classList = {
        add() {},
        remove() {},
        toggle() {},
        contains() {
          return false;
        },
      };
    }
    setAttribute(k, v) {
      this.attributes[k] = String(v);
    }
    getAttribute(k) {
      return this.attributes[k];
    }
    addEventListener() {}
    removeEventListener() {}
    querySelectorAll() {
      return [];
    }
    querySelector() {
      return null;
    }
  }

  class Text extends Node {
    constructor(text) {
      super(3, "#text");
      this.nodeValue = String(text);
    }
    get textContent() {
      return this.nodeValue;
    }
    set textContent(v) {
      this.nodeValue = String(v);
    }
  }

  const elements = {};
  const doc = {
    createElement(tag) {
      return new Element(tag);
    },
    createElementNS(ns, tag) {
      return new Element(tag);
    },
    createTextNode(text) {
      return new Text(text);
    },
    getElementById(id) {
      if (!elements[id]) elements[id] = new Element("div");
      return elements[id];
    },
    querySelectorAll() {
      return [];
    },
    querySelector() {
      return null;
    },
    addEventListener() {},
    removeEventListener() {},
    documentElement: new Element("html"),
  };

  const sandbox = {
    Node,
    Element,
    Text,
    document: doc,
    window: {
      Node,
      Element,
      Text,
      location: { hash: "", href: "http://localhost/" },
      scrollTo() {},
      setInterval() {},
      setTimeout() {},
      clearTimeout() {},
      addEventListener() {},
      matchMedia() {
        return { matches: false, addEventListener() {} };
      },
      getComputedStyle() {
        return {
          getPropertyValue() {
            return "#ffffff";
          },
        };
      },
      localStorage: {
        getItem() {
          return null;
        },
        setItem() {},
        removeItem() {},
      },
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
  sandbox.document = doc;
  sandbox.doc = doc;
  sandbox.elements = elements;
  sandbox.window.document = doc;
  sandbox.globalThis = sandbox;

  const code = fs.readFileSync(path.join(__dirname, "../public/app.js"), "utf8");
  vm.runInNewContext(
    code +
      `\n; globalThis.appEnv = {
        state,
        renderLessons,
        renderProfile,
        renderAbsences,
        renderMarks,
        renderHome,
        renderHomework,
        weekDays,
        todayIso,
        lessonList,
        doc,
        elements,
        getString: typeof getString !== "undefined" ? getString : null,
      };`,
    sandbox
  );

  return sandbox.appEnv;
}

function collectTextNodes(node, acc = []) {
  if (!node) return acc;
  if (node.nodeType === 3) {
    acc.push(node);
  } else if (node.childNodes) {
    for (const kid of node.childNodes) {
      collectTextNodes(kid, acc);
    }
  }
  return acc;
}

test("Item 2: Orar day heading does not render 'null' when viewing days other than today", () => {
  const env = createMockDom();
  const { state, renderLessons, weekDays, todayIso, elements } = env;

  const today = todayIso();
  const days = weekDays();
  const notToday = days.find((d) => d !== today) || "2026-10-19";

  state.day = notToday;
  state.data = {
    student: { id: "1", fullName: "Ion Popescu" },
    schedule: {
      days: {
        [notToday]: [
          {
            start: `${notToday}T08:00:00Z`,
            end: `${notToday}T08:45:00Z`,
            subject: "Matematică",
            startLocal: "08:00",
            endLocal: "08:45",
            periodIndex: 1,
          },
          {
            start: `${notToday}T09:00:00Z`,
            end: `${notToday}T09:45:00Z`,
            subject: "Română",
            startLocal: "09:00",
            endLocal: "09:45",
            periodIndex: 2,
          },
        ],
      },
    },
  };

  renderLessons();

  const heading = elements["day-heading"];
  assert.ok(heading, "day-heading element must exist");

  const textNodes = collectTextNodes(heading);
  for (const t of textNodes) {
    assert.notEqual(t.nodeValue.trim(), "null", "Text node in day-heading must not be 'null'");
    assert.notEqual(t.nodeValue.trim(), "undefined", "Text node in day-heading must not be 'undefined'");
    assert.notEqual(t.nodeValue.trim(), "NaN", "Text node in day-heading must not be 'NaN'");
    assert.notEqual(t.nodeValue.trim(), "[object Object]", "Text node in day-heading must not be '[object Object]'");
  }

  assert.ok(!heading.textContent.includes("null"), `day-heading textContent must not contain 'null', got: "${heading.textContent}"`);
  assert.ok(!heading.textContent.includes("undefined"), `day-heading textContent must not contain 'undefined', got: "${heading.textContent}"`);
  assert.ok(!heading.textContent.includes("NaN"), `day-heading textContent must not contain 'NaN', got: "${heading.textContent}"`);
});

test("Item 2: Orar day heading renders clean heading when day has 0 lessons", () => {
  const env = createMockDom();
  const { state, renderLessons, weekDays, todayIso, elements } = env;

  const today = todayIso();
  const days = weekDays();
  const notToday = days.find((d) => d !== today) || "2026-10-19";

  state.day = notToday;
  state.data = {
    student: { id: "1", fullName: "Ion Popescu" },
    schedule: { days: { [notToday]: [] } },
  };

  renderLessons();

  const heading = elements["day-heading"];
  assert.ok(!heading.textContent.includes("null"), `day-heading with 0 lessons must not contain 'null', got: "${heading.textContent}"`);
  assert.ok(!heading.textContent.includes("undefined"), `day-heading with 0 lessons must not contain 'undefined', got: "${heading.textContent}"`);
});

test("Item 2: No view in any tab renders 'null', 'undefined', 'NaN', or '[object Object]'", () => {
  const env = createMockDom();
  const {
    state,
    renderHome,
    renderMarks,
    renderLessons,
    renderAbsences,
    renderHomework,
    renderProfile,
    elements,
  } = env;

  // Render with missing/sparse/empty data
  state.data = {
    student: { id: "1", fullName: "Elena Test" },
    metadata: {
      schoolName: null,
      classMaster: null,
      studyYear: null,
      birthDate: null,
      enrollmentStatus: null,
    },
    generalAverage: null,
    catalog: {
      subjects: [
        {
          discipline: "Istorie",
          teacher: null,
          marks: [
            { value: null, label: "FB", kind: "qualifier", date: null },
            { value: 10, label: "10", date: null, isThesis: true },
          ],
        },
      ],
    },
    absences: {
      summary: { total: 0, totalExcused: null, totalUnexcused: null },
      sem1: { total: 0, motivate: 0, nemotivate: 0, records: [] },
      sem2: { total: 0, motivate: 0, nemotivate: 0, records: [] },
      records: [
        {
          date: null,
          startLocal: null,
          endLocal: null,
          teacher: null,
          note: null,
          status: "motivat",
          subject: null,
        },
      ],
    },
    schedule: {
      days: {
        "2026-10-19": [
          {
            start: "2026-10-19T08:00:00Z",
            end: "2026-10-19T08:45:00Z",
            subject: null,
            classroom: null,
            teacher: null,
            topic: null,
            periodIndex: null,
          },
        ],
      },
    },
    homework: [
      {
        subject: null,
        taskDescription: null,
        due: null,
      },
    ],
    teachers: [],
  };

  renderHome();
  renderMarks();
  renderLessons();
  renderAbsences();
  renderHomework();
  renderProfile();

  const containerIds = [
    "home-metrics",
    "home-live",
    "home-marks",
    "home-homework",
    "marks-list",
    "day-heading",
    "lesson-list",
    "abs-stats",
    "abs-list",
    "homework-list",
    "profile-card",
    "profile-grid",
  ];

  for (const id of containerIds) {
    const el = elements[id];
    if (!el) continue;
    const textNodes = collectTextNodes(el);
    for (const t of textNodes) {
      const val = t.nodeValue.trim();
      assert.notEqual(val, "null", `Container #${id} has literal 'null' text node`);
      assert.notEqual(val, "undefined", `Container #${id} has literal 'undefined' text node`);
      assert.notEqual(val, "NaN", `Container #${id} has literal 'NaN' text node`);
      assert.notEqual(val, "[object Object]", `Container #${id} has literal '[object Object]' text node`);
    }

    const text = el.textContent;
    // Disallow standalone occurrences or concatenated occurrences like "null null" or "undefined"
    assert.ok(
      !/\bnull\b/.test(text) && !text.includes("nullnull"),
      `Container #${id} textContent contains 'null': "${text}"`
    );
    assert.ok(
      !/\bundefined\b/.test(text),
      `Container #${id} textContent contains 'undefined': "${text}"`
    );
    assert.ok(
      !/\bNaN\b/.test(text),
      `Container #${id} textContent contains 'NaN': "${text}"`
    );
    assert.ok(
      !text.includes("[object Object]"),
      `Container #${id} textContent contains '[object Object]': "${text}"`
    );
  }
});

test("Item 2: getString does not interpolate null or undefined into templates", () => {
  assert.equal(getString("today"), "Astăzi");
  const period = getString("lesson_period", { period: null });
  assert.ok(!period.includes("null"), `getString with null param must not contain 'null', got: "${period}"`);
  const breakStr = getString("break_text", { time: undefined });
  assert.ok(!breakStr.includes("undefined"), `getString with undefined param must not contain 'undefined', got: "${breakStr}"`);
});
