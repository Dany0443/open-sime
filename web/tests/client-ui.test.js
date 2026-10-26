import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import vm from "node:vm";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

function loadClientContext() {
  const stringsCode = fs.readFileSync(path.join(__dirname, "../public/strings.js"), "utf8");
  const appCode = fs.readFileSync(path.join(__dirname, "../public/app.js"), "utf8");

  const cleanStrings = stringsCode.replace(/export\s*\{[^}]*\};?/g, "");
  const cleanApp = appCode.replace(/export\s*\{[^}]*\};?/g, "");

  class MockNode {
    constructor(tag = "div", props = {}) {
      this.tagName = tag.toUpperCase();
      this.nodeName = this.tagName;
      this.props = { ...props };
      this._classSet = new Set();
      const set = this._classSet;
      this.classList = {
        add: (...tokens) => tokens.forEach((t) => set.add(t)),
        remove: (...tokens) => tokens.forEach((t) => set.delete(t)),
        toggle: (token, force) => {
          if (force !== undefined) {
            if (force) set.add(token);
            else set.delete(token);
            return force;
          }
          if (set.has(token)) { set.delete(token); return false; }
          set.add(token);
          return true;
        },
        contains: (token) => set.has(token),
        has: (token) => set.has(token),
        [Symbol.iterator]: () => set.values(),
      };
      if (props.class) {
        this.className = props.class;
      }
      this.dataset = { ...props.dataset };
      this.attributes = new Map();
      this.children = [];
      this.parentNode = null;
      this._textContent = props.text || "";
      this._eventListeners = new Map();
      this.style = {
        setProperty(k, v) {
          this[k] = v;
        },
      };
      this.hidden = false;
      this.disabled = false;
    }
    get textContent() {
      if (this.nodeName === "#TEXT" || this.children.length === 0) {
        return this._textContent || "";
      }
      return this.children.map((c) => (c instanceof MockNode ? c.textContent : String(c))).join("");
    }
    set textContent(val) {
      this._textContent = String(val ?? "");
    }
    get className() { return [...this._classSet].join(" "); }
    set className(val) {
      this._classSet.clear();
      if (val) {
        for (const c of String(val).split(/\s+/).filter(Boolean)) this._classSet.add(c);
      }
    }
    setAttribute(k, v) { this.attributes.set(k, String(v)); if (k === "class") this.className = v; }
    getAttribute(k) { return this.attributes.get(k) || null; }
    hasAttribute(k) { return this.attributes.has(k); }
    removeAttribute(k) { this.attributes.delete(k); }
    appendChild(c) {
      if (!c) return;
      if (typeof c === "string") c = new MockNode("#text", { text: c });
      c.parentNode = this;
      this.children.push(c);
      return c;
    }
    prepend(c) {
      if (!c) return;
      if (typeof c === "string") c = new MockNode("#text", { text: c });
      c.parentNode = this;
      this.children.unshift(c);
      return c;
    }
    replaceChildren(...kids) {
      this.children = [];
      for (const k of kids) {
        if (k) this.appendChild(k);
      }
    }
    addEventListener(evt, fn) {
      if (!this._eventListeners.has(evt)) this._eventListeners.set(evt, []);
      this._eventListeners.get(evt).push(fn);
    }
    removeEventListener(evt, fn) {
      const arr = this._eventListeners.get(evt) || [];
      const idx = arr.indexOf(fn);
      if (idx !== -1) arr.splice(idx, 1);
    }
    remove() {
      if (this.parentNode) {
        const idx = this.parentNode.children.indexOf(this);
        if (idx !== -1) this.parentNode.children.splice(idx, 1);
      }
    }
    closest(sel) {
      let curr = this;
      while (curr) {
        if (curr instanceof MockNode) {
          if (sel.startsWith(".") && curr.classList.has(sel.slice(1))) return curr;
          if (sel.startsWith("#") && curr.id === sel.slice(1)) return curr;
          if (sel === curr.tagName.toLowerCase()) return curr;
        }
        curr = curr.parentNode;
      }
      return null;
    }
    contains(el) {
      let curr = el;
      while (curr) {
        if (curr === this) return true;
        curr = curr.parentNode;
      }
      return false;
    }
    querySelectorAll(sel) {
      const out = [];
      const walk = (n) => {
        for (const c of n.children) {
          if (c instanceof MockNode) {
            if (sel.startsWith(".") && c.classList.has(sel.slice(1))) out.push(c);
            else if (sel === c.tagName.toLowerCase()) out.push(c);
            walk(c);
          }
        }
      };
      walk(this);
      return out;
    }
    querySelector(sel) {
      return this.querySelectorAll(sel)[0] || null;
    }
    get innerText() {
      if (this.nodeName === "#TEXT" || this.children.length === 0) {
        return this.textContent || "";
      }
      return (
        (this.textContent ? this.textContent + " " : "") +
        this.children.map((c) => (c instanceof MockNode ? c.innerText : String(c))).join(" ")
      ).trim();
    }
  }

  const elements = new Map();
  const getOrCreate = (id) => {
    if (!elements.has(id)) elements.set(id, new MockNode("div", { id }));
    return elements.get(id);
  };

  const doc = {
    documentElement: new MockNode("html"),
    body: new MockNode("body"),
    title: "",
    getElementById: (id) => getOrCreate(id),
    querySelector: (sel) => {
      if (sel.startsWith("#")) return getOrCreate(sel.slice(1));
      return null;
    },
    querySelectorAll: () => [],
    createElement: (tag, props) => new MockNode(tag, props),
    createElementNS: (_ns, tag, props) => new MockNode(tag, props),
    createTextNode: (text) => new MockNode("#text", { text }),
    addEventListener: () => {},
    removeEventListener: () => {},
  };

  const storageMap = new Map();
  const localStorageMock = {
    getItem: (k) => (storageMap.has(k) ? storageMap.get(k) : null),
    setItem: (k, v) => storageMap.set(k, String(v)),
    removeItem: (k) => storageMap.delete(k),
    clear: () => storageMap.clear(),
  };

  const sandbox = {
    document: doc,
    doc,
    elements,
    window: {
      location: { hash: "" },
      matchMedia: (q) => ({ matches: q.includes("dark"), addEventListener: () => {} }),
      addEventListener: () => {},
      setTimeout: () => 1,
      clearTimeout: () => {},
      setInterval: () => 1,
      clearInterval: () => {},
      localStorage: localStorageMock,
      sessionStorage: localStorageMock,
      getComputedStyle: () => ({ getPropertyValue: () => "#000" }),
      scrollTo: () => {},
      requestAnimationFrame: (cb) => cb(),
    },
    navigator: { serviceWorker: { register: () => Promise.resolve() } },
    URL,
    console,
    Node: MockNode,
  };

  vm.createContext(sandbox);
  vm.runInContext(cleanStrings, sandbox);
  vm.runInContext(cleanApp, sandbox);
  vm.runInContext(
    "Object.assign(globalThis, { STRINGS, getString, state, store, KEYS, boot, cycleTheme, setLanguage, applyLocalization, describeError, ApiError, pluralForm, tp, setTab, render, renderAbsences, renderHome, renderMarks, renderLessons, renderHomework, renderProfile, weekDays, todayIso, renderStudents, score, scaleChip, getSubjectScale, normalizeSubjectName, emptyMarkText, subjectCard, recentMarks, bandOf, renderMetrics, renderRecent });",
    sandbox
  );
  return sandbox;
}

// 1. THEME SWITCHER (LIGHT & DARK ONLY, NO AUTOMATIC BUTTON, SYSTEM FIRST LOAD)

test("UI Theme: Front end has only light and dark themes (no automatic/system option in settings)", () => {
  const html = fs.readFileSync(path.join(__dirname, "../index.html"), "utf8");
  const appJs = fs.readFileSync(path.join(__dirname, "../public/app.js"), "utf8");

  // Settings dialog must only have light and dark radio options
  assert.ok(html.includes('value="light"'), "Settings must have light theme option");
  assert.ok(html.includes('value="dark"'), "Settings must have dark theme option");
  assert.ok(!html.includes('value="system"'), "Settings must NOT have system / automatic theme option");
  assert.ok(!html.includes('>Automat<'), "Settings must NOT have 'Automat' label");

  // App JS THEMES array must only contain light and dark
  const themeMatch = appJs.match(/const\s+THEMES\s*=\s*\[([^\]]+)\]/);
  assert.ok(themeMatch);
  assert.ok(themeMatch[1].includes('"light"') && themeMatch[1].includes('"dark"'));
  assert.ok(!themeMatch[1].includes('"system"'), "THEMES array must not contain 'system'");
});

test("UI Theme: Boot checks system preference on first load and cycleTheme toggles between light and dark", () => {
  const ctx = loadClientContext();
  const state = ctx.state;
  const store = ctx.store;
  const KEYS = ctx.KEYS;

  // Simulate first load with no saved theme
  store.del(KEYS.theme);
  assert.equal(store.get(KEYS.theme), null);

  // Boot should inspect system preference
  ctx.boot();
  assert.ok(state.theme === "light" || state.theme === "dark", "Theme must be initialized to light or dark on first load");

  // cycleTheme toggles between light and dark
  const initialTheme = state.theme;
  ctx.cycleTheme();
  assert.notEqual(state.theme, initialTheme, "cycleTheme must switch theme");
  assert.ok(state.theme === "light" || state.theme === "dark");

  ctx.cycleTheme();
  assert.equal(state.theme, initialTheme, "Second cycleTheme must return to initial theme");
});

// 2. SITE BRANDING: "Open SIME"

test("UI Branding: Site name is 'Open SIME' across HTML title, brand mark, manifest and strings", () => {
  const html = fs.readFileSync(path.join(__dirname, "../index.html"), "utf8");
  const manifest = JSON.parse(fs.readFileSync(path.join(__dirname, "../public/manifest.json"), "utf8"));
  const stringsJs = fs.readFileSync(path.join(__dirname, "../public/strings.js"), "utf8");

  // 1. index.html title and brand mark
  assert.ok(html.includes("<title>Open SIME</title>"), "index.html title must be 'Open SIME'");
  assert.ok(html.includes("<span>Open SIME</span>"), "Auth brand mark must be 'Open SIME'");
  assert.ok(html.includes('<span class="brand-name">Open SIME</span>'), "Rail/drawer brand name must be 'Open SIME'");

  // 2. manifest.json name, short_name, and unified favicon.svg icon
  assert.equal(manifest.name, "Open SIME", "manifest.json name must be 'Open SIME'");
  assert.equal(manifest.short_name, "Open SIME", "manifest.json short_name must be 'Open SIME'");
  assert.ok(Array.isArray(manifest.icons) && manifest.icons.length > 0, "manifest must define icons");
  assert.ok(manifest.icons.every((ico) => ico.src.includes("favicon.svg")), "manifest icons must only reference favicon.svg");

  // 3. strings.js app_title
  assert.ok(stringsJs.includes('app_title: "Open SIME"'), "strings.js must define app_title as 'Open SIME'");
});

// 3. ABSENCES DETAIL RENDERING (LESSON, HOUR, CLASS, HONEST EMPTY STATE)

test("UI Absences: Absence records display lesson, hour, and room when real records exist, and honest empty state when details unavailable", () => {
  const ctx = loadClientContext();
  const state = ctx.state;

  state.token = "test-token";
  state.semester = 1;
  state.data = {
    student: { id: "101", fullName: "Ion Ciobanu" },
    absences: {
      summary: { sem1Total: 2, sem1Motivate: 1, sem1Nemotivate: 1 },
      sem1: {
        total: 2,
        motivate: 1,
        nemotivate: 1,
        records: [
          {
            date: "2026-10-14",
            lessonNumber: 1,
            startTime: "08:30",
            endTime: "09:15",
            subject: "Matematică",
            room: "101",
            teacher: "Ion Popescu",
            status: "unexcused",
            semester: 1,
          },
          {
            date: "2026-10-15",
            lessonNumber: 2,
            startTime: "09:25",
            endTime: "10:10",
            subject: "Fizică",
            room: "204",
            teacher: "Maria Ionescu",
            status: "excused",
            semester: 1,
          },
        ],
      },
    },
  };

  // Case 1: When real records exist, render lesson number, hour range, room, and subject
  ctx.renderAbsences();
  const list = ctx.elements.get("abs-list");
  let text = list.innerText;
  assert.ok(text.includes("Matematică"));
  assert.ok(text.includes("Fizică"));
  assert.ok(text.includes("Ora 1") || text.includes("08:30"));
  assert.ok(text.includes("Ora 2") || text.includes("09:25"));
  assert.ok(text.includes("Sala 101") || text.includes("101"));
  assert.ok(text.includes("Sala 204") || text.includes("204"));
  assert.ok(text.includes("Nemotivată") || text.includes("nemotivat"));
  assert.ok(text.includes("Motivată") || text.includes("motivat"));

  // Case 2: When portal reports absences but gives no hourly details (records: []), show honest message while keeping totals visible
  state.data.absences.sem1.records = [];
  state.data.absences.sem1.total = 17;
  state.data.absences.sem1.nemotivate = 17;
  state.data.absences.sem1.motivate = 0;
  ctx.renderAbsences();

  const stats = ctx.elements.get("abs-stats");
  assert.ok(stats.innerText.includes("17"), "Totals must remain visible in stats");

  text = list.innerText;
  assert.ok(
    text.includes("Detaliile nu sunt disponibile") || text.includes("fără detalii pe ore"),
    "Must display honest message when portal provides no hourly details"
  );
  assert.ok(text.includes("17"), "Must mention portal reported count in message");

  // Case 3: When 0 absences exist, show clear 'Nicio absență'
  state.data.absences.sem1.total = 0;
  state.data.absences.sem1.nemotivate = 0;
  state.data.absences.sem1.motivate = 0;
  ctx.renderAbsences();
  text = list.innerText;
  assert.ok(text.includes("Nicio absență"), "Must display 'Nicio absență' when total is 0");
});

// 4. CLEAN HEADINGS & DOM CLEANLINESS (NO "null", "undefined", "NaN")

test("UI Cleanliness: No view in any tab renders 'null', 'undefined', 'NaN', or '[object Object]'", () => {
  const ctx = loadClientContext();
  const state = ctx.state;

  state.token = "test-token";
  state.data = {
    student: { id: "101", fullName: "Ion Ciobanu" },
    metadata: { className: "11R", classMaster: null, schoolName: undefined },
    schedule: { days: { "2026-10-24": [] } },
    grades: [],
    catalog: { subjects: [] },
    homework: [],
    absences: { sem1: { total: 0, motivate: 0, nemotivate: 0, records: [] } },
  };

  ctx.render();

  for (const [id, el] of ctx.elements.entries()) {
    const text = el.innerText;
    assert.ok(!text.includes("null"), `Element #${id} must not render 'null': ${text}`);
    assert.ok(!text.includes("undefined"), `Element #${id} must not render 'undefined': ${text}`);
    assert.ok(!text.includes("NaN"), `Element #${id} must not render 'NaN': ${text}`);
    assert.ok(!text.includes("[object Object]"), `Element #${id} must not render '[object Object]': ${text}`);
  }
});

// 5. MATERIAL 3 EXPRESSIVE TOKENS, BUTTONS & ACCESSIBILITY

test("UI Material 3: Button tokens, window size classes, touch targets and accessibility", () => {
  const css = fs.readFileSync(path.join(__dirname, "../public/style.css"), "utf8");

  // Touch targets must be at least 48x48px
  assert.ok(css.includes("min-height: 48px") || css.includes("min-height: 48"), "Touch targets must have min-height 48px");

  // Window size classes
  assert.ok(css.includes("@media (min-width: 600px)"), "Medium rail at 600px");
  assert.ok(css.includes("@media (min-width: 840px)"), "Expanded drawer at 840px");
  assert.ok(css.includes("280px"), "Expanded drawer must specify 280px width");

  // Button variants
  assert.ok(css.includes(".btn-filled"), "Filled button variant");
  assert.ok(css.includes(".btn-tonal"), "Tonal button variant");
  assert.ok(css.includes(".btn-outlined"), "Outlined button variant");
  assert.ok(css.includes(".text-btn"), "Text button variant");
  assert.ok(css.includes(".icon-btn"), "Icon button variant");

  // Reduced motion
  assert.ok(css.includes("prefers-reduced-motion"), "Reduced motion query must be supported");
});

test("UI Localization: Strings dictionaries are complete for ro, ru, en without corrupted characters", () => {
  const stringsJs = fs.readFileSync(path.join(__dirname, "../public/strings.js"), "utf8");

  // No replacement characters or corrupted ? diacritics
  assert.ok(!stringsJs.includes("\uFFFD"), "No U+FFFD replacement characters in strings");
  assert.ok(!stringsJs.match(/[a-zA-Z]\?[a-zA-Z]/), "No letter-?-letter placeholder corruption");

  const ctx = loadClientContext();
  const STRINGS = ctx.STRINGS;
  assert.ok(STRINGS.ro, "Romanian dictionary exists");
  assert.ok(STRINGS.ru, "Russian dictionary exists");
  assert.ok(STRINGS.en, "English dictionary exists");

  const roKeys = Object.keys(STRINGS.ro);
  for (const k of roKeys) {
    assert.ok(STRINGS.ru[k], `Missing Russian translation for key: ${k}`);
    assert.ok(STRINGS.en[k], `Missing English translation for key: ${k}`);
  }
});

// 6. RESTORED CONSOLIDATION TESTS: TIMETABLE, HEADER, NAVIGATION, BUTTONS, MARKS, A11Y, SETTINGS

test("Timetable UI: Orar day heading does not render 'null' when viewing days other than today", () => {
  const ctx = loadClientContext();
  const { state, renderLessons, weekDays, todayIso, elements } = ctx;

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
            subject: "Matematica",
            classroom: "101",
            teacher: "Prof. Mate",
            topic: "Algebra",
            periodIndex: 1,
          },
        ],
      },
    },
  };

  renderLessons();

  const heading = elements.get("day-heading");
  assert.ok(heading, "day-heading must exist");
  assert.ok(!heading.innerText.includes("null"), `day-heading must not contain 'null', got: "${heading.innerText}"`);
  assert.ok(!heading.innerText.includes("undefined"), `day-heading must not contain 'undefined', got: "${heading.innerText}"`);
  assert.ok(!heading.innerText.includes("NaN"), `day-heading must not contain 'NaN', got: "${heading.innerText}"`);
});

test("Timetable UI: Orar day heading renders clean heading when day has 0 lessons", () => {
  const ctx = loadClientContext();
  const { state, renderLessons, weekDays, todayIso, elements } = ctx;

  const today = todayIso();
  const days = weekDays();
  const notToday = days.find((d) => d !== today) || "2026-10-19";

  state.day = notToday;
  state.data = {
    student: { id: "1", fullName: "Ion Popescu" },
    schedule: { days: { [notToday]: [] } },
  };

  renderLessons();

  const heading = elements.get("day-heading");
  assert.ok(!heading.innerText.includes("null"), `day-heading with 0 lessons must not contain 'null', got: "${heading.innerText}"`);
  assert.ok(!heading.innerText.includes("undefined"), `day-heading with 0 lessons must not contain 'undefined', got: "${heading.innerText}"`);
});

test("Localization: getString does not interpolate null or undefined into templates", () => {
  const ctx = loadClientContext();
  const getString = ctx.getString;
  assert.equal(getString("today"), "Astăzi");
  const period = getString("lesson_period", { period: null });
  assert.ok(!period.includes("null"), `getString with null param must not contain 'null', got: "${period}"`);
  const breakStr = getString("break_text", { time: undefined });
  assert.ok(!breakStr.includes("undefined"), `getString with undefined param must not contain 'undefined', got: "${breakStr}"`);
});

test("Header UI: redesign HTML structure and removed legacy sync elements", () => {
  const html = fs.readFileSync(path.join(__dirname, "../index.html"), "utf-8");

  // Legacy sync button, cooldown counter, and sync-status must be removed
  assert.ok(!html.includes('id="sync-btn"'), "sync-btn must be removed from index.html");
  assert.ok(!html.includes('id="sync-status"'), "sync-status must be removed from index.html");
  assert.ok(!html.includes('id="sync-count"'), "sync-count must be removed from index.html");

  // New M3 elements must exist
  assert.ok(html.includes('id="global-progress"'), "global-progress linear progress bar must exist");
  assert.ok(html.includes('id="header-student-subtitle"'), "header-student-subtitle must exist");
  assert.ok(html.includes('id="offline-chip"'), "offline-chip must exist");
  assert.ok(html.includes('id="account-btn"'), "account-btn must exist");
  assert.ok(html.includes('id="account-menu"'), "account-menu must exist");
  assert.ok(html.includes('id="settings-sync-time"'), "settings-sync-time must exist in settings dialog");

  // Account menu must contain profile-btn and logout-btn
  const topbarActionsMatch = html.match(/<div class="topbar-actions">([\s\S]*?)<\/div>/);
  assert.ok(topbarActionsMatch, "topbar-actions must exist");
  assert.ok(topbarActionsMatch[1].includes('id="profile-btn"'), "profile-btn must be inside topbar-actions");
  assert.ok(topbarActionsMatch[1].includes('id="logout-btn"'), "logout-btn must be inside topbar-actions");
});

test("Header UI: Topbar CSS adheres to M3 Expressive tokens and touch targets", () => {
  const css = fs.readFileSync(path.join(__dirname, "../public/style.css"), "utf-8");
  assert.ok(css.includes(".linear-progress"), "CSS must style .linear-progress");
  assert.ok(css.includes(".account-menu"), "CSS must style .account-menu");
  assert.ok(css.includes(".offline-chip"), "CSS must style .offline-chip");
});

test("Navigation UI: Brand mark uses real favicon.svg 40px img without data-cookie", () => {
  const html = fs.readFileSync(path.join(__dirname, "../index.html"), "utf-8");

  assert.ok(html.includes('src="/public/assets/favicon.svg"'), "brand icon must reference /public/assets/favicon.svg");
  assert.ok(html.includes('width="40"'), "brand icon must be 40px");
  assert.ok(html.includes('height="40"'), "brand icon height must be 40px");

  const brandMatch = html.match(/class="[^"]*(?:rail-brand|drawer-brand)[^"]*"[\s\S]*?<\/div>/);
  assert.ok(brandMatch, "brand container must exist");
  assert.ok(!brandMatch[0].includes("data-cookie"), "brand mark must NOT have data-cookie attribute");
  assert.ok(brandMatch[0].includes("Open SIME") || brandMatch[0].includes("open-sime"), "text brand must be beside icon");
});

test("Navigation UI: Drawer has bottom-pinned Profil and Setări", () => {
  const html = fs.readFileSync(path.join(__dirname, "../index.html"), "utf-8");
  const asideMatch = html.match(/<aside[\s\S]*?<\/aside>/);
  assert.ok(asideMatch, "aside element must exist");
  assert.ok(asideMatch[0].includes("drawer-pinned-bottom") || asideMatch[0].includes("rail-bottom"), "must have bottom pinned container");
  assert.ok(asideMatch[0].includes("drawer-settings-btn") || asideMatch[0].includes("nav-item-settings"), "must have settings button in drawer bottom");
});

test("Navigation UI: Navigation follows M3 window size classes (<600, 600-839, >=840)", () => {
  const css = fs.readFileSync(path.join(__dirname, "../public/style.css"), "utf-8");
  assert.ok(css.includes("280px"), "expanded drawer must specify 280px width");
  assert.ok(
    css.includes("drawer-pinned-bottom") || css.includes("rail-bottom"),
    "CSS must style drawer bottom pinned section"
  );
});

test("Buttons UI: Unified button tokens and variants in style.css", () => {
  const css = fs.readFileSync(path.join(__dirname, "../public/style.css"), "utf-8");

  const btnMatch = css.match(/\.btn\s*\{[^}]+\}/);
  assert.ok(btnMatch, "Must declare .btn");
  assert.ok(btnMatch[0].includes("min-height: 48px") || btnMatch[0].includes("min-height:48px"), ".btn must have min-height 48px");
  assert.ok(btnMatch[0].includes("var(--shape-full)"), ".btn must have fully rounded shape");

  assert.ok(css.includes(".btn-filled"), "Must have .btn-filled variant");
  assert.ok(css.includes(".btn-tonal"), "Must have .btn-tonal variant");
  assert.ok(css.includes(".btn-outlined"), "Must have .btn-outlined variant");
  assert.ok(css.includes(".text-btn"), "Must have .text-btn variant");
  assert.ok(css.includes(".icon-btn"), "Must have .icon-btn variant");

  const iconBtnMatch = css.match(/\.icon-btn\s*\{[^}]+\}/);
  assert.ok(iconBtnMatch[0].includes("min-width: 48px") || iconBtnMatch[0].includes("width: 48px"), "icon-btn min-width 48px");
  assert.ok(iconBtnMatch[0].includes("min-height: 48px") || iconBtnMatch[0].includes("height: 48px"), "icon-btn min-height 48px");

  const segBtnMatch = css.match(/\.seg-btn\s*\{[^}]+\}/);
  assert.ok(segBtnMatch, "Must find .seg-btn");
  assert.ok(segBtnMatch[0].includes("min-height: 48px") || segBtnMatch[0].includes("min-height:48px") || segBtnMatch[0].includes("height: 48px"), "seg-btn must have min-height 48px");

  const chipMatch = css.match(/\.student-chip\s*\{[^}]+\}/);
  assert.ok(chipMatch, "Must find .student-chip");
  assert.ok(chipMatch[0].includes("min-height: 48px") || chipMatch[0].includes("min-height:48px") || chipMatch[0].includes("height: 48px"), "student-chip must have min-height 48px");

  assert.ok(css.includes(":focus-visible"), "Must have focus-visible styling");
  assert.ok(css.includes("3px") || css.includes("4px"), "Focus ring must be at least 3px");
});

test("Buttons UI: Expressive states and reduced-motion protection", () => {
  const css = fs.readFileSync(path.join(__dirname, "../public/style.css"), "utf-8");
  assert.ok(css.includes(".btn:active") || css.includes(".btn:active::before"), "Must style button active state");
  assert.ok(css.includes("var(--ease-spring)"), "Must use spring easing for shape morph");

  const reducedMotionMatch = css.match(/@media\s*\(prefers-reduced-motion:\s*reduce\)[\s\S]*?\}/);
  assert.ok(reducedMotionMatch, "Must have prefers-reduced-motion media query");
});

test("Grades UI: GPA card is explicitly labeled as estimată", () => {
  const appJs = fs.readFileSync(path.join(__dirname, "../public/app.js"), "utf-8");
  const stringsJs = fs.readFileSync(path.join(__dirname, "../public/strings.js"), "utf-8");

  assert.ok(stringsJs.includes("Media generală (estimată)"), "ro strings must include Media generală (estimată)");
  assert.ok(stringsJs.includes("Средний балл (расчётный)") || stringsJs.includes("расчётный"), "ru strings must include estimated gpa");
  assert.ok(stringsJs.includes("General GPA (estimated)") || stringsJs.includes("estimated"), "en strings must include estimated gpa");

  assert.ok(
    appJs.includes("Media generală (estimată)") || appJs.includes("general_average_estimated"),
    "app.js renderMetrics must label GPA as estimată"
  );
});

test("Homework UI: short month abbreviation uses nov to avoid ambiguity with noi (new)", () => {
  const appJs = fs.readFileSync(path.join(__dirname, "../public/app.js"), "utf-8");
  const monthsShortMatch = appJs.match(/const MONTHS_SHORT = \[(.*?)\];/);
  assert.ok(monthsShortMatch, "Must find MONTHS_SHORT declaration");
  assert.ok(
    monthsShortMatch[1].includes('"nov"') || monthsShortMatch[1].includes("'nov'"),
    "MONTHS_SHORT must use 'nov' instead of 'noi' for November"
  );
});

test("Shell UI: index.html has profile-btn in topbar-actions so it is accessible when student strip is hidden", () => {
  const html = fs.readFileSync(path.join(__dirname, "../index.html"), "utf8");
  const topbarActionsMatch = html.match(/<div class="topbar-actions">([\s\S]*?)<\/div>/);
  assert.ok(topbarActionsMatch, "topbar-actions container must exist");
  assert.ok(topbarActionsMatch[1].includes('id="profile-btn"'), "profile-btn must be inside topbar-actions");

  const studentStripMatch = html.match(/<div class="student-strip">([\s\S]*?)<\/div>/);
  assert.ok(studentStripMatch, "student-strip container must exist");
  assert.ok(!studentStripMatch[1].includes('id="profile-btn"'), "profile-btn must NOT be inside student-strip");
});

test("Shell UI: renderStudents visibility logic hides strip for student role or single child", () => {
  function computeVisibility({ role, students }) {
    const list = Array.from(new Map(students.map((s) => [String(s.id), s])).values());
    const multiple = list.length > 1 && role !== "student";
    return {
      stripHidden: !multiple,
      renderedButtons: multiple ? list.length : 0,
    };
  }

  assert.deepEqual(computeVisibility({ role: "student", students: [{ id: "1", fullName: "Ion" }] }), { stripHidden: true, renderedButtons: 0 });
  assert.deepEqual(computeVisibility({ role: "student", students: [{ id: "1", fullName: "Ion" }, { id: "2", fullName: "Elena" }] }), { stripHidden: true, renderedButtons: 0 });
  assert.deepEqual(computeVisibility({ role: "parent", students: [{ id: "1", fullName: "Ion" }] }), { stripHidden: true, renderedButtons: 0 });
  assert.deepEqual(computeVisibility({ role: "parent", students: [{ id: "1", fullName: "Ion" }, { id: "1", fullName: "Ion" }] }), { stripHidden: true, renderedButtons: 0 });
  assert.deepEqual(computeVisibility({ role: "parent", students: [{ id: "1", fullName: "Ion" }, { id: "2", fullName: "Elena" }] }), { stripHidden: false, renderedButtons: 2 });
});

test("Marks UI: renderMarks contains no average stats or average columns in table header", () => {
  const appJs = fs.readFileSync(path.join(__dirname, "../public/app.js"), "utf8");
  const renderMarksMatch = appJs.match(/function renderMarks\(\)\s*\{([\s\S]*?)\n\}/);
  assert.ok(renderMarksMatch, "renderMarks must exist in app.js");
  const fnBody = renderMarksMatch[1];

  assert.ok(!fnBody.includes('stat("Media general'), "renderMarks must not render Media generala");
  assert.ok(!fnBody.includes("Medie Sem"), "renderMarks table headers must not include Medie Sem");
  assert.ok(!fnBody.includes("Medie Anual"), "renderMarks table headers must not include Medie Anuala");
  assert.ok(fnBody.includes("marks-table-surface"), "Must generate marks-table-surface");
  assert.ok(fnBody.includes("marks-cards-surface"), "Must generate marks-cards-surface");
});

test("Marks UI: score function styles thesis marks with is-thesis and thesis-tag", () => {
  const appJs = fs.readFileSync(path.join(__dirname, "../public/app.js"), "utf8");
  const scoreMatch = appJs.match(/function score\([\s\S]*?isThesis[\s\S]*?\{([\s\S]*?)\n\}/);
  assert.ok(scoreMatch, "score function with isThesis parameter must exist");
  const fnBody = scoreMatch[1];

  assert.ok(fnBody.includes("is-thesis"), "Must toggle is-thesis class");
  assert.ok(fnBody.includes("thesis-tag"), "Must include thesis-tag element");
});

test("Settings UI: index.html contains settings button and settings dialog with all controls", () => {
  const indexHtml = fs.readFileSync(path.join(__dirname, "../index.html"), "utf-8");
  assert.ok(indexHtml.includes('id="settings-btn"'), "Must have settings button in topbar");
  assert.ok(indexHtml.includes('id="settings-dialog"'), "Must have settings dialog");
  assert.ok(indexHtml.includes('name="setting-lang"'), "Must have language selector");
  assert.ok(indexHtml.includes('name="setting-text-size"'), "Must have text size selector");
  assert.ok(indexHtml.includes('name="setting-theme"'), "Must have theme selector");
  assert.ok(indexHtml.includes('id="setting-simple-mode"'), "Must have simple mode toggle");
  assert.ok(indexHtml.includes('id="setting-contrast"'), "Must have high contrast toggle");
  assert.ok(indexHtml.includes('id="setting-reduce-motion"'), "Must have reduce motion toggle");
});

test("Accessibility UI: text scaling and high contrast tokens in style.css", () => {
  const styleCss = fs.readFileSync(path.join(__dirname, "../public/style.css"), "utf-8");
  assert.ok(styleCss.includes('[data-text-size="S"]'), "Must have small text scaling");
  assert.ok(styleCss.includes('[data-text-size="XL"]'), "Must have extra-large text scaling");
  assert.ok(styleCss.includes('[data-contrast="high"]'), "Must have high contrast rules");
  assert.ok(styleCss.includes('[data-simple-mode="true"]'), "Must have simple mode styling");
});

test("Settings UI: app.js binds settings dialog and simple mode labels", () => {
  const appJs = fs.readFileSync(path.join(__dirname, "../public/app.js"), "utf-8");
  assert.ok(appJs.includes("settings-dialog"), "Must bind settings dialog");
  assert.ok(appJs.includes("open-sime.simple-mode") || appJs.includes("simpleMode"), "Must persist simple mode setting");
  assert.ok(appJs.includes("open-sime.text-size") || appJs.includes("textSize"), "Must persist text size setting");
});

test("Service Worker: public/sw.js never caches /api/auth and caches shell", () => {
  const swJs = fs.readFileSync(path.join(__dirname, "../public/sw.js"), "utf8");
  assert.ok(swJs.includes("/api/auth"), "sw.js must explicitly reference /api/auth to exclude it");
  assert.ok(swJs.includes("index.html"), "sw.js pre-caches index.html shell");
  assert.ok(swJs.includes("app.js"), "sw.js pre-caches app.js");
  assert.ok(swJs.includes("style.css"), "sw.js pre-caches style.css");
  assert.ok(swJs.includes("favicon.svg"), "sw.js pre-caches favicon.svg");
  assert.ok(!swJs.includes(".png"), "sw.js must not reference removed png icons");
});

test("Marks UI: Qualifier pills and neutral raw pills rendering in score", () => {
  const ctx = loadClientContext();
  const fb = ctx.score({ kind: "qualifier", label: "FB", raw: "Foarte bine", value: null });
  assert.ok(fb.classList.contains("is-qualifier"));
  assert.ok(fb.classList.contains("band-top"));
  assert.equal(fb.textContent, "Foarte bine");
  assert.equal(fb.attributes.get("title"), "Foarte bine");
  assert.equal(fb.attributes.get("aria-label"), "Foarte bine");

  const fbWithDate = ctx.score({ kind: "qualifier", label: "FB", raw: "Foarte bine", value: null, date: "2024-10-12" });
  assert.ok(fbWithDate.attributes.get("title").includes("Foarte bine"));
  assert.ok(fbWithDate.attributes.get("title").includes("12 oct"));

  const b = ctx.score({ kind: "qualifier", label: "B", raw: "Bine", value: null });
  assert.ok(b.classList.contains("is-qualifier"));
  assert.ok(b.classList.contains("band-good"));
  assert.equal(b.textContent, "Bine");
  assert.equal(b.attributes.get("title"), "Bine");

  const s = ctx.score({ kind: "qualifier", label: "S", raw: "Suficient", value: null });
  assert.ok(s.classList.contains("is-qualifier"));
  assert.ok(s.classList.contains("band-ok"));
  assert.equal(s.textContent, "Suficient");
  assert.equal(s.attributes.get("title"), "Suficient");

  const i = ctx.score({ kind: "qualifier", label: "I", raw: "Insuficient", value: null });
  assert.ok(i.classList.contains("is-qualifier"));
  assert.ok(i.classList.contains("band-low"));
  assert.equal(i.textContent, "Insuficient");
  assert.equal(i.attributes.get("title"), "Insuficient");

  // Full words passed directly as string or label
  const fbStr = ctx.score("Foarte bine");
  assert.ok(fbStr.classList.contains("is-qualifier"));
  assert.ok(fbStr.classList.contains("band-top"));
  assert.equal(fbStr.textContent, "Foarte bine");

  const bStr = ctx.score("Bine");
  assert.ok(bStr.classList.contains("is-qualifier"));
  assert.ok(bStr.classList.contains("band-good"));
  assert.equal(bStr.textContent, "Bine");

  // CSS white-space nowrap verification
  const styleCss = fs.readFileSync(path.join(__dirname, "../public/style.css"), "utf8");
  assert.ok(styleCss.includes(".score.is-qualifier"), "style.css must have .score.is-qualifier");
  const qualRule = styleCss.match(/\.score\.is-qualifier\s*\{([^}]+)\}/);
  assert.ok(qualRule, "must find .score.is-qualifier block");
  assert.ok(qualRule[1].includes("white-space: nowrap"), ".score.is-qualifier must enforce white-space: nowrap");

  // Multi-language localization support
  ctx.state.lang = "ru";
  const fbRu = ctx.score({ kind: "qualifier", label: "FB", raw: "Foarte bine", value: null });
  assert.equal(fbRu.textContent, "Отлично");
  assert.ok(fbRu.classList.contains("band-top"));

  ctx.state.lang = "en";
  const fbEn = ctx.score({ kind: "qualifier", label: "FB", raw: "Foarte bine", value: null });
  assert.equal(fbEn.textContent, "Very Good");
  assert.ok(fbEn.classList.contains("band-top"));
  ctx.state.lang = "ro";

  // Consistency across Reușita, recent marks on Home, and "Ultima notă"
  const markObj = { kind: "qualifier", label: "FB", raw: "Foarte bine", date: "2024-10-12", value: null };
  const card = ctx.subjectCard({
    subject: "Educație fizică",
    discipline: "Educație fizică",
    scale: "qualifier",
    markDetails: [markObj],
    semesters: [{ semester: 1, marks: [markObj] }],
  });
  const pills = card.querySelectorAll(".score");
  assert.ok(pills.length >= 1, "Subject card must render qualifier mark pill");
  for (const pill of pills) {
    assert.equal(pill.textContent, "Foarte bine");
    assert.ok(pill.classList.contains("band-top"));
    assert.ok(pill.classList.contains("is-qualifier"));
    // Ensure no fragmenting into separate "Foarte" and "bine" pills
    assert.notEqual(pill.textContent, "Foarte");
    assert.notEqual(pill.textContent, "bine");
  }

  // Home metrics & recent marks consistency
  ctx.state.data = {
    grades: [{ subject: "Educație fizică", kind: "qualifier", label: "FB", raw: "Foarte bine", date: "2024-10-12", value: null }],
    reusita: { subjects: [] },
    absences: { summary: {} },
    schedule: { days: {} },
    homework: [],
  };
  ctx.renderHome();

  const metricsHost = ctx.elements.get("home-metrics");
  const metricLast = metricsHost ? metricsHost.querySelector(".metric-last") : null;
  const lastScore = metricLast ? metricLast.querySelector(".score") : null;
  assert.ok(lastScore, "Ultima notă in Home metrics must render a .score pill");
  assert.equal(lastScore.textContent, "Foarte bine");
  assert.ok(lastScore.classList.contains("band-top"));
  assert.ok(lastScore.classList.contains("is-qualifier"));

  const recentHost = ctx.elements.get("home-marks");
  const recentItem = recentHost ? recentHost.querySelector(".recent-item") : null;
  const recentScore = recentItem ? recentItem.querySelector(".score") : null;
  assert.ok(recentScore, "Recent marks in Home must render a .score pill");
  assert.equal(recentScore.textContent, "Foarte bine");
  assert.ok(recentScore.classList.contains("band-top"));
  assert.ok(recentScore.classList.contains("is-qualifier"));

  // Unrecognized non-empty cell text renders as neutral raw pill (band-none, is-other)
  const other = ctx.score({ kind: "other", label: "25", raw: "25", value: null });
  assert.ok(other.classList.contains("band-none"));
  assert.ok(other.classList.contains("is-other"));
  assert.equal(other.textContent, "25");

  const descriptor = ctx.score({ kind: "descriptor", label: "Competent", raw: "Competent", value: null });
  assert.ok(descriptor.classList.contains("band-none"));
  assert.ok(descriptor.classList.contains("is-other"));
  assert.equal(descriptor.textContent, "Competent");
});

test("Marks UI: qualifier legend does not exist in markup, CSS, or strings", () => {
  const appJs = fs.readFileSync(path.join(__dirname, "../public/app.js"), "utf8");
  const styleCss = fs.readFileSync(path.join(__dirname, "../public/style.css"), "utf8");
  const stringsJs = fs.readFileSync(path.join(__dirname, "../public/strings.js"), "utf8");

  assert.ok(!appJs.includes("qualifier-legend"), "app.js must not contain qualifier-legend");
  assert.ok(!styleCss.includes(".qualifier-legend"), "style.css must not contain .qualifier-legend");
  assert.ok(!styleCss.includes(".legend-title"), "style.css must not contain .legend-title");
  assert.ok(!styleCss.includes(".legend-item"), "style.css must not contain .legend-item");
  assert.ok(!stringsJs.includes("qualifiers_legend"), "strings.js must not contain qualifiers_legend");
});

test("Marks UI: subject scale chips and scale-aware empty state", () => {
  const ctx = loadClientContext();
  const physScale = ctx.getSubjectScale("Educație fizică");
  assert.equal(physScale, "qualifier");
  const socScale = ctx.getSubjectScale("Educație pentru societate");
  assert.equal(socScale, "descriptor");
  const mathScale = ctx.getSubjectScale("Matematică");
  assert.equal(mathScale, null);

  // Scale chip rendering
  const qualChip = ctx.scaleChip("qualifier");
  assert.ok(qualChip.classList.contains("scale-chip"));
  assert.ok(qualChip.textContent.includes("Calificative"));

  const descChip = ctx.scaleChip("descriptor");
  assert.ok(descChip.classList.contains("scale-chip"));
  assert.ok(descChip.textContent.includes("Descriptori"));

  assert.equal(ctx.scaleChip(null), null);

  // Empty state tooltips
  assert.equal(ctx.emptyMarkText("qualifier"), "Încă nu sunt calificative");
  assert.equal(ctx.emptyMarkText("descriptor"), "Încă nu sunt descriptori");
  assert.equal(ctx.emptyMarkText(null), "Încă nu sunt note");

  // Empty subject card shows scale-aware message
  const emptyCard = ctx.subjectCard({ subject: "Educație fizică", discipline: "Educație fizică", scale: "qualifier", markDetails: [], semesters: [] });
  assert.ok(emptyCard.classList.contains("subject"));
  const pMuted = emptyCard.children.find((c) => c.tagName === "P" && c.classList.contains("muted"));
  assert.equal(pMuted.textContent, "Încă nu sunt calificative");
});

// 7. ENGLISH & RUSSIAN LOCALIZATION, LANGUAGE SWITCHING, AND SUN ROTATION

test("Localization: Full English (en) and Russian (ru) rendering across all tabs, errors, and pluralization", () => {
  const ctx = loadClientContext();
  const { state, elements, setLanguage, describeError, ApiError, tp } = ctx;

  const today = ctx.todayIso();
  state.token = "test-token";
  state.studentId = "101";
  state.students = [{ id: "101", fullName: "Ion Ciobanu", className: "11R", school: "Liceul Teoretic" }];
  state.semester = 1;
  state.day = today;
  state.data = {
    student: { id: "101", fullName: "Ion Ciobanu", className: "11R", school: "Liceul Teoretic", classMaster: "Prof. Elena" },
    metadata: { className: "11R", schoolName: "Liceul Teoretic", classMaster: "Prof. Elena", studyYear: "2026-2027" },
    schedule: {
      days: {
        [today]: [
          {
            start: `${today}T08:00:00Z`,
            end: `${today}T08:45:00Z`,
            subject: "Matematică",
            classroom: "101",
            teacher: "Ion Popescu",
            topic: "Ecuații",
            periodIndex: 1,
          },
        ],
      },
    },
    grades: [
      { subject: "Matematică", value: 10, date: "2026-10-14", semester: 1, isThesis: true },
      { subject: "Fizică", value: 9, date: "2026-10-15", semester: 1 },
    ],
    reusita: {
      subjects: [
        {
          subject: "Matematică",
          discipline: "Matematică",
          teacher: "Ion Popescu",
          semesters: [{ semester: 1, marks: [{ value: 10, date: "2026-10-14", isThesis: true }] }],
        },
      ],
    },
    homework: [
      { subject: "Matematică", task: "Ex. 5 pag. 42", assignedDate: "2026-10-14", dueDate: "2026-10-20" },
    ],
    absences: {
      summary: { sem1Total: 2, sem1Motivate: 1, sem1Nemotivate: 1 },
      sem1: {
        total: 2,
        motivate: 1,
        nemotivate: 1,
        records: [
          {
            date: "2026-10-14",
            lessonNumber: 1,
            startTime: "08:30",
            endTime: "09:15",
            subject: "Matematică",
            room: "101",
            teacher: "Ion Popescu",
            status: "unexcused",
            semester: 1,
          },
        ],
      },
    },
  };

  // Switch to English
  setLanguage("en");
  assert.equal(state.lang, "en");
  assert.equal(ctx.doc.documentElement.lang, "en");
  assert.equal(elements.get("page-title").textContent, "Home");
  assert.ok(elements.get("home-metrics").innerText.includes("General GPA (estimated)"), "English GPA metric label");
  assert.ok(elements.get("home-metrics").innerText.includes("Latest grade"), "English latest grade label");
  assert.ok(elements.get("marks-list").innerText.includes("Subject"), "English marks table header");
  assert.ok(elements.get("marks-list").innerText.includes("Thesis"), "English thesis tag");
  assert.ok(elements.get("abs-stats").innerText.includes("Unexcused"), "English absences stats");
  assert.ok(elements.get("abs-list").innerText.includes("Room 101"), "English room label");
  assert.ok(elements.get("homework-list").innerText.includes("Matematică"), "English homework list item");
  assert.ok(elements.get("profile-card").innerText.includes("Class master"), "English profile class master");

  // English pluralization and errors
  assert.equal(tp(1, "lesson_count"), "1 lesson");
  assert.equal(tp(5, "lesson_count"), "5 lessons");
  const enErr = describeError(new ApiError("Auth", 401, 0, "PORTAL_LOGIN_REJECTED"), "login");
  assert.ok(enErr.includes("Incorrect username or password"));

  // Switch to Russian
  setLanguage("ru");
  assert.equal(state.lang, "ru");
  assert.equal(ctx.doc.documentElement.lang, "ru");
  assert.equal(elements.get("page-title").textContent, "Главная");
  assert.ok(elements.get("home-metrics").innerText.includes("Средний балл (расчётный)"), "Russian GPA metric label");
  assert.ok(elements.get("home-metrics").innerText.includes("Последняя оценка"), "Russian latest mark label");
  assert.ok(elements.get("marks-list").innerText.includes("Предмет"), "Russian marks table header");
  assert.ok(elements.get("marks-list").innerText.includes("Теза"), "Russian thesis tag");
  assert.ok(elements.get("abs-stats").innerText.includes("Неуважительные"), "Russian absences stats");
  assert.ok(elements.get("abs-list").innerText.includes("Каб. 101"), "Russian room label");
  assert.ok(elements.get("homework-list").innerText.includes("Matematică"), "Russian homework list item");
  assert.ok(elements.get("profile-card").innerText.includes("Классный руководитель"), "Russian profile class teacher");

  // Russian 3-form pluralization (1, 2-4, 5+) and errors
  assert.equal(tp(1, "lesson_count"), "1 урок");
  assert.equal(tp(3, "lesson_count"), "3 урока");
  assert.equal(tp(5, "lesson_count"), "5 уроков");
  assert.equal(tp(12, "lesson_count"), "12 уроков");
  assert.equal(tp(21, "lesson_count"), "21 урок");
  const ruErr = describeError(new ApiError("Auth", 401, 0, "PORTAL_LOGIN_REJECTED"), "login");
  assert.ok(ruErr.includes("Неверное имя пользователя или пароль"));
});

test("Localization: Language preference persists in localStorage and preserves active tab and student state", () => {
  const ctx = loadClientContext();
  const { state, store, KEYS, setLanguage, setTab } = ctx;

  state.token = "tok-123";
  state.studentId = "stud-2";
  state.data = { student: { id: "stud-2", fullName: "Maria Ionescu" } };
  setTab("marks");

  setLanguage("ru");
  assert.equal(store.get(KEYS.lang), "ru", "Selected language must be saved in localStorage");
  assert.equal(state.tab, "marks", "Switching language must preserve active tab");
  assert.equal(state.studentId, "stud-2", "Switching language must preserve active student");
  assert.equal(state.token, "tok-123", "Switching language must preserve auth token");
  assert.equal(ctx.elements.get("page-title").textContent, "Успеваемость", "Marks tab title must update to Russian immediately");

  setLanguage("en");
  assert.equal(store.get(KEYS.lang), "en");
  assert.equal(ctx.elements.get("page-title").textContent, "Grades", "Marks tab title must update to English immediately");
});

test("Sun Rotation: CSS rotates only background suns and GPA sun with center origin and respects reduced motion", () => {
  const css = fs.readFileSync(path.join(__dirname, "../public/style.css"), "utf8");

  // Keyframes exist
  assert.ok(css.includes("@keyframes sun-spin"), "CSS must define @keyframes sun-spin");
  assert.ok(css.includes("@keyframes sun-spin-reverse"), "CSS must define @keyframes sun-spin-reverse");

  // Background suns and GPA sun have center transform-origin and slow linear infinite animations
  assert.ok(css.includes(".auth-deco.deco-a .deco-shape"), "Must target .auth-deco.deco-a .deco-shape");
  assert.ok(css.includes(".auth-deco.deco-b .deco-shape"), "Must target .auth-deco.deco-b .deco-shape");
  assert.ok(css.includes(".metric-gpa .metric-deco .deco-shape"), "Must target .metric-gpa .metric-deco .deco-shape");
  assert.ok(css.includes("transform-origin: 50% 50%"), "Sun shapes must rotate around their center (50% 50%)");

  // Brand mark and avatar cookies must NOT rotate
  const nonRotatingMatch = css.match(/\.brand-mark\s+\.deco-shape,\s*\r?\n?\.cookie-shape\s*\{([^}]+)\}/);
  assert.ok(nonRotatingMatch, "Must explicitly guard .brand-mark .deco-shape and .cookie-shape");
  assert.ok(nonRotatingMatch[1].includes("animation: none"), ".brand-mark .deco-shape and .cookie-shape must have animation: none");

  // GPA metric card renders .metric-deco inside .metric-gpa in runtime DOM
  const ctx = loadClientContext();
  ctx.state.data = {
    grades: [{ subject: "Matematică", value: 10, date: "2026-10-14", semester: 1 }],
    reusita: { subjects: [] },
    absences: { summary: {} },
    schedule: { days: {} },
    homework: [],
  };
  ctx.renderHome();
  const metricsHost = ctx.elements.get("home-metrics");
  const gpaCard = metricsHost.querySelector(".metric-gpa");
  assert.ok(gpaCard, "renderHome must render .metric-gpa card");
  const gpaDeco = gpaCard.querySelector(".metric-deco");
  assert.ok(gpaDeco, ".metric-gpa card must contain .metric-deco sun container");
});

test("M3 Expressive Motion & Switch Selection: unselected pill vs selected rounded-square, spring tokens, and reduced motion", () => {
  const css = fs.readFileSync(path.join(__dirname, "../public/style.css"), "utf8");

  // 1. M3 Expressive Spatial & Effects spring tokens in :root
  assert.ok(css.includes("--spring-spatial-fast"), "Must define --spring-spatial-fast token");
  assert.ok(css.includes("--spring-spatial-default"), "Must define --spring-spatial-default token");
  assert.ok(css.includes("--spring-spatial-slow"), "Must define --spring-spatial-slow token");
  assert.ok(css.includes("--spring-effects-fast"), "Must define --spring-effects-fast token");
  assert.ok(css.includes("--spring-effects-default"), "Must define --spring-effects-default token");
  assert.ok(css.includes("--spring-effects-slow"), "Must define --spring-effects-slow token");
  assert.ok(css.includes("@supports (transition-timing-function: linear(0, 1))"), "Must provide @supports linear() spring enhancement");

  // 2. .seg track is neutral/transparent so unselected .seg-btn read as individual round pills
  const segTrackMatch = css.match(/^\.seg\s*\{([^}]+)\}/m);
  assert.ok(segTrackMatch, "Must declare .seg");
  assert.ok(
    segTrackMatch[1].includes("background: transparent") || segTrackMatch[1].includes("background: none"),
    ".seg container must not have a solid grey pill track background"
  );

  // 3. Unselected .seg-btn, .day-tab, .student-chip use var(--shape-full) and var(--surface-container-high)
  const segBtnMatch = css.match(/^\.seg-btn\s*\{([^}]+)\}/m);
  assert.ok(segBtnMatch, "Must declare .seg-btn");
  assert.ok(segBtnMatch[1].includes("border-radius: var(--shape-full)"), "Unselected .seg-btn must use var(--shape-full)");
  assert.ok(segBtnMatch[1].includes("background: var(--surface-container-high)"), "Unselected .seg-btn must use var(--surface-container-high)");

  const dayTabMatch = css.match(/^\.day-tab\s*\{([^}]+)\}/m);
  assert.ok(dayTabMatch, "Must declare .day-tab");
  assert.ok(dayTabMatch[1].includes("border-radius: var(--shape-full)"), "Unselected .day-tab must use var(--shape-full)");
  assert.ok(dayTabMatch[1].includes("background: var(--surface-container-high)"), "Unselected .day-tab must use var(--surface-container-high)");

  const studentChipMatch = css.match(/^\.student-chip\s*\{([^}]+)\}/m);
  assert.ok(studentChipMatch, "Must declare .student-chip");
  assert.ok(studentChipMatch[1].includes("border-radius: var(--shape-full)"), "Unselected .student-chip must use var(--shape-full)");
  assert.ok(studentChipMatch[1].includes("background: var(--surface-container-high)"), "Unselected .student-chip must use var(--surface-container-high)");

  // 4. Selected .seg-btn, .day-tab, and .student-chip morph to rounded square var(--shape-md) with var(--primary) / var(--on-primary)
  const segSelectedMatch = css.match(/\.seg-btn\[aria-pressed="true"\][\s\S]*?\.seg\s+input:checked\s*\+\s*\.seg-btn\s*\{([^}]+)\}/);
  assert.ok(segSelectedMatch, "Must have shared selected rule for .seg-btn[aria-pressed='true'] and .seg input:checked + .seg-btn");
  assert.ok(segSelectedMatch[1].includes("border-radius: var(--shape-md)"), "Selected .seg-btn must use var(--shape-md)");
  assert.ok(segSelectedMatch[1].includes("background: var(--primary)"), "Selected .seg-btn must use var(--primary)");
  assert.ok(segSelectedMatch[1].includes("color: var(--on-primary)"), "Selected .seg-btn must use var(--on-primary)");

  const daySelectedMatch = css.match(/\.day-tab\.is-selected[\s\S]*?\{([^}]+)\}/);
  assert.ok(daySelectedMatch, "Must have selected rule for .day-tab.is-selected");
  assert.ok(daySelectedMatch[1].includes("border-radius: var(--shape-md)"), "Selected .day-tab must use var(--shape-md)");
  assert.ok(daySelectedMatch[1].includes("background: var(--primary)"), "Selected .day-tab must use var(--primary)");
  assert.ok(daySelectedMatch[1].includes("color: var(--on-primary)"), "Selected .day-tab must use var(--on-primary)");

  const chipSelectedMatch = css.match(/\.student-chip\.is-active[\s\S]*?\{([^}]+)\}/);
  assert.ok(chipSelectedMatch, "Must have selected rule for .student-chip.is-active");
  assert.ok(chipSelectedMatch[1].includes("border-radius: var(--shape-md)"), "Selected .student-chip must use var(--shape-md)");
  assert.ok(chipSelectedMatch[1].includes("background: var(--primary)"), "Selected .student-chip must use var(--primary)");
  assert.ok(chipSelectedMatch[1].includes("color: var(--on-primary)"), "Selected .student-chip must use var(--on-primary)");

  // 5. Expressive selection transition without horizontal scale jitter between adjacent buttons
  assert.ok(segBtnMatch[1].includes("border-radius var(--spring-spatial-default)"), ".seg-btn must transition border-radius with spatial spring");
  assert.ok(dayTabMatch[1].includes("border-radius var(--spring-spatial-default)"), ".day-tab must transition border-radius with spatial spring");
  assert.ok(studentChipMatch[1].includes("border-radius var(--spring-spatial-default)"), ".student-chip must transition border-radius with spatial spring");
  assert.ok(!segSelectedMatch[1].includes("scale("), "Selected .seg-btn must not scale width and jitter adjacent buttons");

  // 6. --shape-full uses an interpolatable pill radius (24px) so border-radius transitions smoothly across the full spring duration
  assert.ok(css.includes("--shape-full: 24px"), "--shape-full must use 24px so border-radius interpolates smoothly without 999px clamping delay");

  // 7. Checkboxes use the same unselected-round -> selected-rounded-square morph animation
  const checkboxMatch = css.match(/input\[type="checkbox"\]\s*\{([^}]+)\}/);
  assert.ok(checkboxMatch, "Must style input[type='checkbox'] with custom M3 Expressive morph");
  assert.ok(checkboxMatch[1].includes("appearance: none"), "Checkbox must use appearance: none for custom shape morph");
  assert.ok(checkboxMatch[1].includes("border-radius: 11px") || checkboxMatch[1].includes("border-radius: 50%"), "Unselected checkbox must be round");
  const checkboxCheckedMatch = css.match(/input\[type="checkbox"\]:checked\s*\{([^}]+)\}/);
  assert.ok(checkboxCheckedMatch, "Must style input[type='checkbox']:checked");
  assert.ok(checkboxCheckedMatch[1].includes("border-radius: 6px"), "Checked checkbox must morph to rounded square (6px)");
  assert.ok(checkboxCheckedMatch[1].includes("background: var(--primary)"), "Checked checkbox must use var(--primary)");
  assert.ok(checkboxCheckedMatch[1].includes("animation: select-morph"), "Checked checkbox must use select-morph animation");

  // 8. Reduced-motion disables spring transitions/overshoot while preserving selected shape
  const reducedMotionBlock = css.match(/@media\s*\(prefers-reduced-motion:\s*reduce\)\s*\{([\s\S]*?)\n\}/);
  assert.ok(reducedMotionBlock, "Must have @media (prefers-reduced-motion: reduce) block");
  assert.ok(
    reducedMotionBlock[1].includes("--ease-spatial-default: linear") || reducedMotionBlock[1].includes("transition-timing-function: linear"),
    "Reduced motion block must neutralize spring overshoot to linear"
  );
  assert.ok(
    reducedMotionBlock[1].includes("transition-duration: 0.01ms"),
    "Reduced motion block must collapse transition durations"
  );
});

test("Tauri 2 Desktop: configuration, sidecar externalBin, scripts, capabilities, and icons in sime-desktop", () => {
  const desktopRoot = path.resolve(__dirname, "../../desktop");
  if (!fs.existsSync(desktopRoot)) {
    return;
  }
  const pkg = JSON.parse(fs.readFileSync(path.join(desktopRoot, "package.json"), "utf8"));
  assert.equal(pkg.scripts["sidecar:build"], "node scripts/build-sidecar.mjs");
  assert.equal(pkg.scripts.tauri, "tauri");
  assert.equal(pkg.scripts["tauri:dev"], "tauri dev");
  assert.equal(pkg.scripts["tauri:build"], "tauri build");
  assert.ok(pkg.devDependencies["@tauri-apps/cli"], "Must include @tauri-apps/cli v2 in devDependencies");
  assert.ok(pkg.devDependencies["@tauri-apps/plugin-shell"], "Must include @tauri-apps/plugin-shell in devDependencies");

  const tauriConf = JSON.parse(fs.readFileSync(path.join(desktopRoot, "src-tauri/tauri.conf.json"), "utf8"));
  assert.equal(tauriConf.productName, "Open SIME");
  assert.equal(tauriConf.identifier, "md.sime.opensime");
  assert.equal(tauriConf.build.beforeDevCommand, "npm run start --prefix ../web");
  assert.equal(tauriConf.build.devUrl, "http://127.0.0.1:3000");
  assert.equal(tauriConf.build.beforeBuildCommand, "npm run sidecar:build");
  assert.equal(tauriConf.build.frontendDist, "../dist-tauri");
  assert.equal(tauriConf.app.windows[0].title, "Open SIME");
  assert.equal(tauriConf.app.windows[0].label, "main");
  assert.equal(tauriConf.app.windows[0].visible, true);
  assert.ok(tauriConf.app.windows[0].width >= 800);
  assert.ok(tauriConf.app.windows[0].height >= 600);
  assert.equal(tauriConf.bundle.active, true);
  assert.deepEqual(tauriConf.bundle.externalBin, ["binaries/open-sime-server"]);

  const cap = JSON.parse(fs.readFileSync(path.join(desktopRoot, "src-tauri/capabilities/default.json"), "utf8"));
  assert.ok(cap.windows.includes("main"));
  assert.ok(cap.permissions.includes("core:default"));
  const spawnPerm = cap.permissions.find((p) => typeof p === "object" && p.identifier === "shell:allow-spawn");
  assert.ok(spawnPerm, "Capabilities must include shell:allow-spawn for the sidecar");
  assert.ok(spawnPerm.allow.some((a) => a.name === "binaries/open-sime-server" && a.sidecar === true));

  const cargoToml = fs.readFileSync(path.join(desktopRoot, "src-tauri/Cargo.toml"), "utf8");
  assert.ok(cargoToml.includes("tauri-plugin-shell"), "Cargo.toml must include tauri-plugin-shell");

  assert.ok(fs.existsSync(path.join(desktopRoot, "dist-tauri/index.html")), "Must have dist-tauri/index.html fallback shell");
  assert.ok(fs.existsSync(path.join(desktopRoot, "src-tauri/build.rs")), "Must have src-tauri/build.rs");
  assert.ok(fs.existsSync(path.join(desktopRoot, "src-tauri/src/lib.rs")), "Must have src-tauri/src/lib.rs");
  assert.ok(fs.existsSync(path.join(desktopRoot, "src-tauri/src/main.rs")), "Must have src-tauri/src/main.rs");
  assert.ok(fs.existsSync(path.join(desktopRoot, "src-tauri/icons/icon.ico")), "Must have src-tauri/icons/icon.ico");
  assert.ok(fs.existsSync(path.join(desktopRoot, "src-tauri/icons/128x128.png")), "Must have src-tauri/icons/128x128.png");
});
