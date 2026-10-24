// Local web frontend backend for agenda.sime.md.
// Sections: errors, endpoints, time helpers, parsers, resilience (fingerprint, gate, breaker),
// HTTP transport, APEX login & handshake, client, web layer.

import crypto from "node:crypto";
import path from "node:path";
import { fileURLToPath } from "node:url";
import express from "express";
import * as cheerio from "cheerio";

const ROOT = path.dirname(fileURLToPath(import.meta.url));

// errors

class SimeError extends Error {}
class SimeNetworkError extends SimeError {}
class SimeAuthError extends SimeError {}
class SimeSessionExpiredError extends SimeAuthError {}
class SimeRateLimitError extends SimeAuthError {
  constructor(message, waitSeconds = 900) {
    super(message);
    this.waitSeconds = waitSeconds;
  }
}
class SimeParseError extends SimeError {}
class SimeStudentNotFoundError extends SimeError {}

// endpoints

const HOST = "agenda.sime.md";
const BASE_URL = `https://${HOST}`;
const PUBLIC_PORTAL_URL = `${BASE_URL}/ords/r/agenda/main/public`;
const ajaxUrl = (sessionId) => `${BASE_URL}/ords/wwv_flow.ajax?p_context=main/main-page/${sessionId}`;
const studentSwitchUrl = (sessionId, studentId) =>
  resolveSimeUrl(`f?p=AGENDA:1:${encodeURIComponent(sessionId)}::NO::P1_ELEVE_ID:${encodeURIComponent(studentId)}`, `${BASE_URL}/ords/`);

const FLOW_ID = "104";
const FLOW_STEP_ID = "1";
const ITEM_STUDENT_ID = "P1_ELEVE_ID";
const ITEM_LAST_EVALS = "P1_LAST_3_EVAL_LIST";
const ITEM_HOMEWORK = "P1_HOMEWORK";
const REGIONS = [
  { semester: 1, id: "sem1", selectors: ["#sem1", "#SEMESTER_REG_1", "#R_SEM1", "[id='sem1' i]", "[id*='semester_reg_1' i]", "[id*='sem_1' i]"] },
  { semester: 2, id: "sem2", selectors: ["#sem2", "#SEMESTER_REG_2", "#R_SEM2", "[id='sem2' i]", "[id*='semester_reg_2' i]", "[id*='sem_2' i]"] },
];

const REQUEST_TIMEOUT_MS = 30_000;
const MAX_REDIRECTS = 5;
const MAX_BODY_BYTES = 8 * 1024 * 1024;
const MAX_WAIT_SECONDS = 3600;
const DEFAULT_WAIT_SECONDS = 900;
const PAGE_TTL_MS = 60_000;
const CALENDAR_CHUNK_DAYS = 180;
const RENEWAL_MIN_GAP_MS = 60_000;
const MAINTENANCE_RE = /maintenance|mentenan[țt]|lucr[ăa]ri tehnice|temporar indisponibil/i;

function requireSimeUrl(url) {
  let parts;
  try {
    parts = new URL(url);
  } catch {
    throw new SimeNetworkError("Refused to contact a host other than agenda.sime.md");
  }
  // URL normalizes the default port 443 to an empty string
  if (parts.protocol !== "https:" || parts.hostname !== HOST || parts.port !== "" || parts.username || parts.password) {
    throw new SimeNetworkError("Refused to contact a host other than agenda.sime.md");
  }
  return parts.href;
}

function resolveSimeUrl(target, base = `${BASE_URL}/`) {
  let resolved;
  try {
    resolved = new URL(target, base).href;
  } catch {
    throw new SimeNetworkError("Refused to contact a host other than agenda.sime.md");
  }
  return requireSimeUrl(resolved);
}

// time helpers (Europe/Chisinau)

const TZ = "Europe/Chisinau";
const tzFormat = new Intl.DateTimeFormat("en-GB", {
  timeZone: TZ,
  hourCycle: "h23",
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
  second: "2-digit",
});

function tzParts(ms) {
  const out = {};
  for (const p of tzFormat.formatToParts(new Date(ms))) out[p.type] = p.value;
  return out;
}

function tzOffsetMs(ms) {
  const p = tzParts(ms);
  const asUtc = Date.UTC(+p.year, +p.month - 1, +p.day, +p.hour, +p.minute, +p.second);
  return asUtc - Math.floor(ms / 1000) * 1000;
}

function localToEpoch(y, mo, d, h, mi, s) {
  const guess = Date.UTC(y, mo - 1, d, h, mi, s);
  const first = guess - tzOffsetMs(guess);
  return guess - tzOffsetMs(first);
}

const chisinauDate = (ms) => {
  const p = tzParts(ms);
  return `${p.year}-${p.month}-${p.day}`;
};
const chisinauHm = (ms) => {
  const p = tzParts(ms);
  return `${p.hour}:${p.minute}`;
};
const todayChisinau = () => chisinauDate(Date.now());

const pad = (n, w = 2) => String(n).padStart(w, "0");
const DAY_MS = 86_400_000;

// returns an ISO date string or null when the components are not a real date
function makeDate(y, m, d) {
  if (!(y >= 1 && m >= 1 && m <= 12 && d >= 1 && d <= 31)) return null;
  const dt = new Date(0);
  dt.setUTCFullYear(y, m - 1, d);
  if (dt.getUTCFullYear() !== y || dt.getUTCMonth() !== m - 1 || dt.getUTCDate() !== d) return null;
  return `${pad(y, 4)}-${pad(m)}-${pad(d)}`;
}

const dateMs = (iso) => Date.parse(`${iso}T00:00:00Z`);
const addDays = (iso, n) => new Date(dateMs(iso) + n * DAY_MS).toISOString().slice(0, 10);

function weekStart(iso) {
  const dow = (new Date(dateMs(iso)).getUTCDay() + 6) % 7;
  return addDays(iso, -dow);
}

// parsers

const DATE_RE = /(\d{1,2})[./-](\d{1,2})(?:[./-](\d{2,4}))?/;
const ISO_DATE_RE = /(\d{4})-(\d{2})-(\d{2})/;
const FULL_DATE_RE = /\d{1,2}[./-]\d{1,2}[./-]\d{2,4}|\d{4}-\d{2}-\d{2}/;
const EXCUSED_MARKERS = ["motivat"];
const UNEXCUSED_MARKERS = ["nemotivat", "unmotivat", "unexcused"];

function unescapeJs(value) {
  return value.replace(/\\u([0-9a-fA-F]{4})|\\x([0-9a-fA-F]{2})|\\([\s\S])/g, (_m, u, x, c) => {
    if (u) return String.fromCharCode(parseInt(u, 16));
    if (x) return String.fromCharCode(parseInt(x, 16));
    return c;
  });
}

// strip every text node, drop empties, join with sep
function textOf(el, sep = "") {
  if (!el) return "";
  const parts = [];
  const walk = (node) => {
    for (const child of node.children || []) {
      if (child.type === "text") parts.push(child.data);
      else if (child.type === "tag") walk(child);
    }
  };
  walk(el);
  return parts.map((s) => s.trim()).filter(Boolean).join(sep);
}

function classTokens(el) {
  return (el.attribs?.class || "").split(/\s+/).filter(Boolean);
}

// first descendant (document order) whose class token contains one of the needles
function classContains($, scope, ...needles) {
  const candidates = (scope ? $(scope).find("*") : $("*")).toArray();
  for (const needle of needles) {
    const found = candidates.find((el) => classTokens(el).some((t) => t.includes(needle)));
    if (found) return found;
  }
  return null;
}

function getInputValue($, id, def = "") {
  const inputs = $("input").toArray();
  const el = inputs.find((e) => e.attribs.id === id) || inputs.find((e) => e.attribs.name === id);
  if (el) {
    const v = el.attribs.value;
    return typeof v === "string" ? v : def;
  }
  const sel = $(`select[id="${id}"], select[name="${id}"]`).first();
  if (sel.length) {
    const opt = sel.find("option[selected]").first();
    const chosen = opt.length ? opt : sel.find("option").first();
    const v = chosen.attr("value");
    if (typeof v === "string" && v) return v;
  }
  return def;
}

function getItemChecksum($, item) {
  const el = $("input").toArray().find((e) => e.attribs["data-for"] === item);
  const v = el?.attribs.value;
  return typeof v === "string" && v ? v : null;
}

function isDashboard($) {
  return Boolean(getInputValue($, ITEM_STUDENT_ID) || getItemText($, "P1_ELEVE")) || $("#t_TreeNav").length > 0;
}

function parseDate(text, today = todayChisinau()) {
  if (!text) return null;
  const iso = ISO_DATE_RE.exec(text);
  if (iso) return makeDate(+iso[1], +iso[2], +iso[3]);
  const m = DATE_RE.exec(text);
  if (!m) return null;
  const day = +m[1];
  const month = +m[2];
  if (m[3]) {
    let year = +m[3];
    if (year < 100) year += 2000;
    return makeDate(year, month, day);
  }
  // No year: pick the closest plausible one, never more than a week ahead
  const ty = +today.slice(0, 4);
  let candidate = makeDate(ty, month, day);
  if (candidate === null) return null;
  if (dateMs(candidate) > dateMs(today) + 7 * DAY_MS) candidate = makeDate(ty - 1, month, day);
  return candidate;
}

// accepts only strings that carry a full date, so "9.5" is never read as 9 May
function parseFullDate(text) {
  return text && FULL_DATE_RE.test(text) ? parseDate(text) : null;
}

function inferSemester(isoDay, fallback) {
  if (!isoDay) return fallback;
  return Number(isoDay.slice(5, 7)) >= 9 ? 1 : 2;
}

function toFloat(text) {
  const s = text.trim().replace(",", ".");
  return /^[+-]?(\d+\.?\d*|\.\d+)$/.test(s) ? Number(s) : null;
}

const round2 = (n) => Math.round(n * 100) / 100;
const mean = (values) => (values.length ? round2(values.reduce((a, b) => a + b, 0) / values.length) : null);

function getItemText($, id, def = "") {
  const v = getInputValue($, id, null);
  if (v !== null && v !== "") return v;
  const node = $(`[id="${id}"]`).first();
  if (node.length && node[0].name !== "input" && node[0].name !== "select") {
    const t = textOf(node[0], " ").trim();
    if (t) return t;
  }
  return v !== null ? v : def;
}

function parseStudentProfile($) {
  return {
    id: getItemText($, ITEM_STUDENT_ID),
    fullName: getItemText($, "P1_ELEVE"),
    className: getItemText($, "P1_CLASS_NAME") || null,
    schoolName: getItemText($, "P1_INSTITUTION_NAME") || null,
  };
}

function parseStudentMetadata($) {
  const profile = parseStudentProfile($);
  const statusRaw = getItemText($, "P1_STATUS") || getItemText($, "P1_ELEVE_STATUS");
  return {
    ...profile,
    birthDate: getItemText($, "P1_BIRTH_DATE") || null,
    permanentCode: getItemText($, "P1_ELEVE_UK") || getItemText($, "P1_UK") || null,
    classMaster: getItemText($, "P1_DIRIGINTE") || null,
    address: getItemText($, "P1_ADDRESS") || null,
    studyYear: getItemText($, "P1_STUDY_YEAR") || null,
    currentSemester: parseCurrentSemester($),
    enrollmentStatus: statusRaw || (profile.id || profile.fullName ? "Activ" : null),
  };
}

function parseCurrentSemester($) {
  const v = (getItemText($, "P1_SEMESTER_ID") || getItemText($, "P1_SEMESTER_NUMBER")).trim();
  return /^[+-]?\d+$/.test(v) ? parseInt(v, 10) : null;
}

function safeUnquote(s) {
  try {
    return decodeURIComponent(s);
  } catch {
    return s;
  }
}

function parseStudentLinks($) {
  const links = [];
  const tree = $("#t_TreeNav").first();
  if (tree.length) {
    tree.find("li").each((_i, li) => {
      const a = $(li).find("a[href]").first();
      if (!a.length) return;
      const href = String(a.attr("href"));
      const m = /(?:user_eleve_id=|p1_eleve_id=|P1_ELEVE_ID:)([^&#:'"]+)/i.exec(href);
      if (!m) return;
      links.push({
        studentId: safeUnquote(m[1]),
        name: textOf(a[0]),
        href,
        isCurrent: li.attribs["data-current"] === "true",
      });
    });
  }
  if (!links.length) {
    const sel = $(`select[id="${ITEM_STUDENT_ID}"], select[name="${ITEM_STUDENT_ID}"]`).first();
    if (sel.length) {
      sel.find("option").each((_i, opt) => {
        const val = String($(opt).attr("value") || "").trim();
        if (!val) return;
        links.push({
          studentId: val,
          name: textOf(opt) || val,
          href: `/ords/f?p=AGENDA:1:0::NO::P1_ELEVE_ID:${encodeURIComponent(val)}`,
          isCurrent: opt.attribs.selected !== undefined,
        });
      });
    }
  }
  return links;
}

function parseRecentGradesFromRoot($, root, defaultSemester = null, today = todayChisinau()) {
  const grades = [];
  root.find("li").each((_i, li) => {
    const dateEl = classContains($, li, "eval-date");
    const subjectEl = classContains($, li, "eval-discipline");
    const gradeEl = classContains($, li, "grade");
    let dateText;
    let subject;
    let value;
    if (dateEl && subjectEl && gradeEl) {
      dateText = textOf(dateEl);
      subject = textOf(subjectEl).replace(/:+$/, "").trim();
      value = toFloat(textOf(gradeEl));
    } else {
      // Fallback when class names change: date, subject and trailing mark in plain text
      const m = /^\s*(\S+)\s+(.+?)[:\s]+(\d+(?:[.,]\d+)?)\s*$/.exec(textOf(li, " "));
      if (!m) return;
      dateText = m[1];
      subject = m[2].trim();
      value = toFloat(m[3]);
    }
    if (value === null || !subject) return;
    const day = parseDate(dateText, today);
    grades.push({ subject, value, date: day, semester: inferSemester(day, defaultSemester) });
  });
  return grades;
}

function parseRecentGrades(htmlSnippet, defaultSemester = null, today = todayChisinau()) {
  if (!htmlSnippet) return [];
  const $ = cheerio.load(htmlSnippet, null, false);
  return parseRecentGradesFromRoot($, $.root(), defaultSemester, today);
}

function headerHas($, td, needles) {
  const raw = ($(td).attr("headers") || "").toUpperCase();
  return Boolean(raw) && raw.split(/\s+/).some((t) => needles.some((n) => t.includes(n)));
}

// Marks are read from leaf elements when the cell is structured (date taken from a title-like
// attribute when present); otherwise from numeric tokens in the cell text.
function parseMarkCell($, cell) {
  const marks = [];
  const leaves = $(cell).find("*").toArray().filter((e) => $(e).children().length === 0);
  for (const leaf of leaves) {
    const text = textOf(leaf);
    if (!/^\d+(?:[.,]\d+)?$/.test(text)) continue;
    const value = toFloat(text);
    if (!(value > 0 && value <= 10)) continue;
    const raw = ["title", "data-date", "data-title", "aria-label"].map((k) => leaf.attribs[k]).find(Boolean) || leaf.parent?.attribs?.title;
    marks.push({ value, date: parseFullDate(raw) });
  }
  if (marks.length) return marks;
  for (const token of textOf(cell, " ").match(/\d+(?:\.\d+)?/g) || []) {
    const value = Number(token);
    if (value > 0 && value <= 10) marks.push({ value, date: null });
  }
  return marks;
}

function parseSemesterReportFromRoot($, root) {
  const result = new Map();
  root.find("tr").each((_i, tr) => {
    const tds = $(tr).find("td").toArray();
    if (!tds.length) return;
    const disc = tds.find((td) => headerHas($, td, ["DISCIPLINA", "DISCIPLINE", "MATERIA", "OBIECT"]));
    if (!disc) return;
    const subject = textOf(disc);
    if (!subject) return;
    const evals = tds.find((td) => headerHas($, td, ["LISTA_EVALUATION", "EVALUATION", "NOTE", "MARKS"]));
    const avg = tds.find((td) => headerHas($, td, ["MED_SEM", "MEDIA_SEM", "MEDIA"]));
    const teacherTd = tds.find((td) => headerHas($, td, ["PROFESOR", "CADRU", "TEACHER"]));
    result.set(subject, {
      subject,
      marks: evals ? parseMarkCell($, evals) : [],
      semesterAverage: avg ? toFloat(textOf(avg)) : null,
      teacher: teacherTd ? textOf(teacherTd) || null : null,
    });
  });
  return result;
}

function parseSemesterReport(reportHtml) {
  if (!reportHtml) return new Map();
  const $ = cheerio.load(reportHtml);
  return parseSemesterReportFromRoot($, $.root());
}

// Extracts multi-semester or annual report columns when a single table contains Sem 1, Sem 2, or Annual columns
function parseCombinedReportFromRoot($, root, annualMap = new Map()) {
  const sem1 = new Map();
  const sem2 = new Map();
  root.find("tr").each((_i, tr) => {
    const tds = $(tr).find("td").toArray();
    if (!tds.length) return;
    const disc = tds.find((td) => headerHas($, td, ["DISCIPLINA", "DISCIPLINE", "MATERIA", "OBIECT"]));
    if (!disc) return;
    const subject = textOf(disc);
    if (!subject) return;
    const teacherTd = tds.find((td) => headerHas($, td, ["PROFESOR", "CADRU", "TEACHER"]));
    const teacher = teacherTd ? textOf(teacherTd) || null : null;

    const annualTd = tds.find((td) => headerHas($, td, ["MED_AN", "MEDIA_AN", "MEDIA_GEN", "ANUAL", "ANNUAL"]));
    if (annualTd) {
      const val = toFloat(textOf(annualTd));
      if (val !== null) annualMap.set(subject, val);
    }

    const evals1 = tds.find((td) => headerHas($, td, ["LISTA_EVALUATION_1", "EVAL_SEM1", "NOTE_SEM1", "SEM1_NOTE"]));
    const evals2 = tds.find((td) => headerHas($, td, ["LISTA_EVALUATION_2", "EVAL_SEM2", "NOTE_SEM2", "SEM2_NOTE"]));
    const avg1 = tds.find((td) => headerHas($, td, ["MED_SEM1", "MED_SEM_1", "MEDIA_SEM1", "SEM1_MED"]));
    const avg2 = tds.find((td) => headerHas($, td, ["MED_SEM2", "MED_SEM_2", "MEDIA_SEM2", "SEM2_MED"]));

    if (evals1 || avg1) {
      sem1.set(subject, {
        subject,
        marks: evals1 ? parseMarkCell($, evals1) : [],
        semesterAverage: avg1 ? toFloat(textOf(avg1)) : null,
        teacher,
      });
    }
    if (evals2 || avg2) {
      sem2.set(subject, {
        subject,
        marks: evals2 ? parseMarkCell($, evals2) : [],
        semesterAverage: avg2 ? toFloat(textOf(avg2)) : null,
        teacher,
      });
    }
  });
  return { sem1, sem2, annualMap };
}

// Merges per-semester reports into a full gradebook ("Reusita") with discipline marks, Sem 1/Sem 2 averages, and annual average
function buildCatalog(reports, annualMap = new Map()) {
  const subjects = new Map();
  for (const { semester, report } of reports) {
    for (const [name, entry] of report) {
      if (!subjects.has(name)) subjects.set(name, { subject: name, teacher: null, semesters: [] });
      const s = subjects.get(name);
      s.teacher = s.teacher || entry.teacher;
      const values = entry.marks.map((m) => m.value);
      const existing = s.semesters.find((x) => x.semester === semester);
      if (existing) {
        if (!existing.marks.length && entry.marks.length) existing.marks = entry.marks;
        existing.average = mean(existing.marks.map((m) => m.value));
        if (existing.officialAverage === null && entry.semesterAverage !== null) {
          existing.officialAverage = entry.semesterAverage;
        }
      } else {
        s.semesters.push({
          semester,
          marks: entry.marks,
          average: mean(values),
          officialAverage: entry.semesterAverage,
        });
      }
    }
  }
  const list = [...subjects.values()].map((s) => {
    s.semesters.sort((a, b) => a.semester - b.semester);
    const sem1 = s.semesters.find((x) => x.semester === 1) || null;
    const sem2 = s.semesters.find((x) => x.semester === 2) || null;
    const sem1Average = sem1 ? (sem1.officialAverage ?? sem1.average ?? null) : null;
    const sem2Average = sem2 ? (sem2.officialAverage ?? sem2.average ?? null) : null;
    const markDetails = s.semesters.flatMap((x) => x.marks.map((m) => ({ value: m.value, date: m.date, semester: x.semester })));
    const marks = markDetails.map((m) => m.value);
    const official = s.semesters.map((x) => x.officialAverage).filter((v) => v !== null);
    const computedAverage = marks.length ? mean(marks) : mean(official);
    const explicitAnnual = annualMap instanceof Map ? (annualMap.get(s.subject) ?? null) : null;
    const annualAverage =
      explicitAnnual ?? (sem1Average !== null && sem2Average !== null ? mean([sem1Average, sem2Average]) : computedAverage);
    const average = computedAverage ?? annualAverage;
    return {
      ...s,
      discipline: s.subject,
      marks,
      markDetails,
      markCount: marks.length,
      sem1Average,
      sem2Average,
      annualAverage,
      average,
    };
  });
  list.sort((a, b) => a.subject.localeCompare(b.subject));
  const averages = list.map((s) => s.average).filter((v) => v !== null);
  const sem1Avgs = list.map((s) => s.sem1Average).filter((v) => v !== null);
  const sem2Avgs = list.map((s) => s.sem2Average).filter((v) => v !== null);
  const annualAvgs = list.map((s) => s.annualAverage).filter((v) => v !== null);
  const generalAverage = mean(averages);
  return {
    subjects: list,
    disciplines: list,
    sem1GeneralAverage: mean(sem1Avgs),
    sem2GeneralAverage: mean(sem2Avgs),
    annualGeneralAverage: mean(annualAvgs) ?? generalAverage,
    generalAverage,
  };
}

// Homework fragment: <b>date</b> followed by <li><span>subject</span><span>task</span></li>
function parseHomeworkFromRoot($, root, today = todayChisinau()) {
  const tasks = [];
  let current = null;
  root.find("b, li").each((_i, el) => {
    if (el.name === "b") {
      current = parseDate(textOf(el), today);
      return;
    }
    const spans = $(el).find("span").toArray();
    if (spans.length < 2) return;
    const subject = textOf(spans[0]).replace(/:+$/, "").trim();
    const taskDescription = textOf(spans[1], " ");
    if (subject || taskDescription) tasks.push({ subject, taskDescription, dueDate: current, isCompleted: false });
  });
  return tasks;
}

function parseHomework(htmlSnippet, today = todayChisinau()) {
  if (!htmlSnippet) return [];
  const $ = cheerio.load(htmlSnippet, null, false);
  return parseHomeworkFromRoot($, $.root(), today);
}

function parseAbsenceSummary($) {
  const asInt = (item, def) => {
    const v = getItemText($, item, String(def)).trim();
    return /^[+-]?\d+$/.test(v) ? parseInt(v, 10) : def;
  };
  const presenceRaw = getItemText($, "P1_PRESENCE_PERCENT", "") || getItemText($, "P1_PROC_PREZ", "");
  const presencePercent = /^[+-]?\d+$/.test(presenceRaw.trim()) ? parseInt(presenceRaw.trim(), 10) : 100;
  const summary = {
    presencePercent,
    sem1Excused: asInt("P1_MOTIVATED", 0),
    sem1Unexcused: asInt("P1_UNMOTIVATED", 0),
    sem2Excused: asInt("P1_MOTIVATED2", 0),
    sem2Unexcused: asInt("P1_UNMOTIVATED2", 0),
  };
  summary.totalExcused = summary.sem1Excused + summary.sem2Excused;
  summary.totalUnexcused = summary.sem1Unexcused + summary.sem2Unexcused;
  summary.total = summary.totalExcused + summary.totalUnexcused;
  return summary;
}

const escapeRe = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

function findReportAjaxId(pageHtml, regionStaticId) {
  const m = new RegExp(`apex\\.widget\\.report\\.init\\(\\s*"${escapeRe(regionStaticId)}"\\s*,\\s*"([^"]+)"`).exec(pageHtml);
  return m ? unescapeJs(m[1]) : null;
}

function findCalendarAjaxId(pageHtml) {
  const m = /apex\.widget\.fullCalendar\(\{[\s\S]{0,6000}?"ajaxIdentifier"\s*:\s*"([^"]+)"/.exec(pageHtml);
  return m ? unescapeJs(m[1]) : null;
}

const EVENT_TIME_RE = /^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?)?(Z|[+-]\d{2}(?::?\d{2})?)?$/;

// returns epoch milliseconds; naive times are interpreted as Europe/Chisinau
function parseEventTime(raw) {
  if (typeof raw !== "string" || !raw) return null;
  const m = EVENT_TIME_RE.exec(raw.trim());
  if (!m) return null;
  const [y, mo, d, h, mi, s] = [m[1], m[2], m[3], m[4] || 0, m[5] || 0, m[6] || 0].map(Number);
  if (makeDate(y, mo, d) === null || h > 23 || mi > 59 || s > 59) return null;
  const tz = m[7];
  if (!tz) return localToEpoch(y, mo, d, h, mi, s);
  if (tz === "Z") return Date.UTC(y, mo - 1, d, h, mi, s);
  const sign = tz[0] === "-" ? -1 : 1;
  const digits = tz.slice(1).replace(":", "");
  const offset = Number(digits.slice(0, 2)) * 60 + Number(digits.slice(2) || 0);
  return Date.UTC(y, mo - 1, d, h, mi, s) - sign * offset * 60_000;
}

function asList(value) {
  if (Array.isArray(value)) return value;
  if (typeof value === "string") return value.split(/\s+/).filter(Boolean);
  return [];
}

function parseCalendarEventsWithDom($, events) {
  const entries = [];
  if (!Array.isArray(events)) return entries;
  for (const ev of events) {
    if (ev === null || typeof ev !== "object" || Array.isArray(ev)) continue;
    const start = parseEventTime(ev.start);
    const end = parseEventTime(ev.end);
    if (start === null || end === null) continue;
    const root = $(`<div>${typeof ev.title === "string" ? ev.title : ""}</div>`);
    const textFor = (...names) => {
      const el = classContains($, root[0], ...names);
      return el ? textOf(el, " ") || null : null;
    };
    const subject = textFor("lesson-text-wrap") || textOf(root[0], " ") || "Unknown";
    let marker = ["className", "classNames", "class"]
      .flatMap((key) => asList(ev[key]).map(String))
      .join(" ")
      .toLowerCase();
    const absenceEl = classContains($, root[0], "absen");
    if (absenceEl) marker += ` ${classTokens(absenceEl).join(" ").toLowerCase()}`;
    const isAbsence = marker.includes("absen");
    const isUnexcused = UNEXCUSED_MARKERS.some((k) => marker.includes(k));
    const isExcused = !isUnexcused && EXCUSED_MARKERS.some((k) => marker.includes(k));
    const explicitSem = ev.semester === 1 || ev.semester === 2 ? ev.semester : null;
    entries.push({
      start,
      end,
      subject,
      classroom: textFor("lesson-room"),
      teacher: textFor("lesson-teacher"),
      topic: textFor("lesson-homework", "lesson-line3"),
      isAbsence,
      isExcused,
      isUnexcused,
      semester: explicitSem,
      note: textFor("absence-reason", "reason", "note") || absenceEl?.attribs?.title || null,
    });
  }
  entries.sort((a, b) => a.start - b.start);
  return entries;
}

function parseCalendarEvents(events) {
  if (!Array.isArray(events) || !events.length) return [];
  const $ = cheerio.load("", null, false);
  return parseCalendarEventsWithDom($, events);
}

const lessonView = (e, periodIndex) => ({
  periodIndex,
  start: new Date(e.start).toISOString(),
  end: new Date(e.end).toISOString(),
  startLocal: chisinauHm(e.start),
  endLocal: chisinauHm(e.end),
  subject: e.subject,
  classroom: e.classroom,
  teacher: e.teacher,
  topic: e.topic,
});

// Groups calendar entries by local date; period index follows start-time order within a day
function groupByDay(entries) {
  const days = {};
  for (const e of entries) {
    const day = chisinauDate(e.start);
    (days[day] ||= []).push(e);
  }
  const out = {};
  for (const [day, list] of Object.entries(days)) out[day] = list.map((e, i) => lessonView(e, i + 1));
  return out;
}

// Infers semester from school calendar dates: Sep-Dec = Sem 1, Jan-May/Aug = Sem 2
function inferAbsenceSemester(isoDay, explicitSemester = null) {
  if (explicitSemester === 1 || explicitSemester === 2) return explicitSemester;
  if (!isoDay || typeof isoDay !== "string") return 1;
  const month = Number(isoDay.slice(5, 7));
  if (month >= 9 && month <= 12) return 1;
  if (month >= 1 && month <= 8) return 2;
  return 1;
}

function absenceRecords(entries) {
  const seen = new Set();
  const records = [];
  for (const e of entries.filter((x) => x.isAbsence)) {
    const key = `${e.start}|${e.subject}`;
    if (seen.has(key)) continue;
    seen.add(key);
    const date = chisinauDate(e.start);
    records.push({
      date,
      startLocal: chisinauHm(e.start),
      endLocal: chisinauHm(e.end),
      subject: e.subject,
      discipline: e.subject,
      teacher: e.teacher,
      status: e.isExcused ? "motivat" : e.isUnexcused ? "nemotivat" : "necunoscut",
      note: e.note,
      semester: inferAbsenceSemester(date, e.semester),
    });
  }
  return records.sort((a, b) => b.date.localeCompare(a.date) || b.startLocal.localeCompare(a.startLocal));
}

function extractHtmlAbsenceRecords($, today = todayChisinau()) {
  const records = [];
  $("tr").each((_i, tr) => {
    const tds = $(tr).find("td").toArray();
    if (!tds.length) return;
    const statusTd = tds.find((td) => headerHas($, td, ["STATUT", "MOTIVAT", "TIP_ABS", "ABSENT"]));
    const dateTd = tds.find((td) => headerHas($, td, ["DATA", "DATE", "DATA_ABS"]));
    const discTd = tds.find((td) => headerHas($, td, ["DISCIPLINA", "DISCIPLINE", "MATERIA"]));
    if (!statusTd || !dateTd || !discTd) return;
    const date = parseDate(textOf(dateTd), today);
    const subject = textOf(discTd);
    if (!date || !subject) return;
    const rawStatus = textOf(statusTd).toLowerCase();
    const status = UNEXCUSED_MARKERS.some((k) => rawStatus.includes(k))
      ? "nemotivat"
      : EXCUSED_MARKERS.some((k) => rawStatus.includes(k))
        ? "motivat"
        : "necunoscut";
    const timeTd = tds.find((td) => headerHas($, td, ["ORA", "TIME", "LECTIA"]));
    const teacherTd = tds.find((td) => headerHas($, td, ["PROFESOR", "CADRU", "TEACHER"]));
    const noteTd = tds.find((td) => headerHas($, td, ["MOTIV", "NOTE", "OBSERVATII"]));
    const semTd = tds.find((td) => headerHas($, td, ["SEMESTRU", "SEM"]));
    const explicitSem = semTd ? parseInt(textOf(semTd), 10) : null;
    records.push({
      date,
      startLocal: timeTd ? textOf(timeTd) : "",
      endLocal: "",
      subject,
      discipline: subject,
      teacher: teacherTd ? textOf(teacherTd) || null : null,
      status,
      note: noteTd ? textOf(noteTd) || null : null,
      semester: inferAbsenceSemester(date, explicitSem === 1 || explicitSem === 2 ? explicitSem : null),
    });
  });
  return records;
}

// Categorizes absences strictly into Semestrul 1 and Semestrul 2 with totals, motivate, nemotivate, and lesson records
function buildSemesterAbsences(summary, rawRecords = []) {
  const seen = new Set();
  const deduped = [];
  for (const r of rawRecords) {
    if (!r) continue;
    const key = `${r.date || ""}|${r.startLocal || ""}|${r.subject || r.discipline || ""}`;
    if (seen.has(key)) continue;
    seen.add(key);
    const sem = inferAbsenceSemester(r.date, r.semester);
    const subj = r.subject || r.discipline || "Disciplina";
    deduped.push({
      date: r.date || null,
      startLocal: r.startLocal || "",
      endLocal: r.endLocal || "",
      subject: subj,
      discipline: r.discipline || subj,
      teacher: r.teacher || null,
      status: r.status || "necunoscut",
      note: r.note || null,
      semester: sem,
    });
  }
  deduped.sort((a, b) => (b.date || "").localeCompare(a.date || "") || (b.startLocal || "").localeCompare(a.startLocal || ""));

  const sem1Records = deduped.filter((r) => r.semester === 1);
  const sem2Records = deduped.filter((r) => r.semester === 2);

  const countBy = (list, status) => list.filter((r) => r.status === status).length;
  const sem1Motivate = Math.max(summary?.sem1Excused ?? 0, countBy(sem1Records, "motivat"));
  const sem1Nemotivate = Math.max(summary?.sem1Unexcused ?? 0, countBy(sem1Records, "nemotivat"));
  const sem1Total = Math.max(sem1Motivate + sem1Nemotivate, sem1Records.length);

  const sem2Motivate = Math.max(summary?.sem2Excused ?? 0, countBy(sem2Records, "motivat"));
  const sem2Nemotivate = Math.max(summary?.sem2Unexcused ?? 0, countBy(sem2Records, "nemotivat"));
  const sem2Total = Math.max(sem2Motivate + sem2Nemotivate, sem2Records.length);

  const normalizedSummary = {
    presencePercent: summary?.presencePercent ?? 100,
    sem1Excused: sem1Motivate,
    sem1Unexcused: sem1Nemotivate,
    sem2Excused: sem2Motivate,
    sem2Unexcused: sem2Nemotivate,
    totalExcused: sem1Motivate + sem2Motivate,
    totalUnexcused: sem1Nemotivate + sem2Nemotivate,
    total: sem1Total + sem2Total,
  };

  return {
    summary: normalizedSummary,
    records: deduped,
    sem1: {
      semester: 1,
      total: sem1Total,
      motivate: sem1Motivate,
      nemotivate: sem1Nemotivate,
      records: sem1Records,
    },
    sem2: {
      semester: 2,
      total: sem2Total,
      motivate: sem2Motivate,
      nemotivate: sem2Nemotivate,
      records: sem2Records,
    },
  };
}

function buildTeachers(entries, metadata = null, catalogSubjects = []) {
  const map = new Map();
  const ensure = (name) => {
    if (!map.has(name)) map.set(name, { name, subjects: new Set(), lessons: 0, note: null });
    return map.get(name);
  };
  if (metadata?.classMaster) {
    const cm = ensure(metadata.classMaster);
    cm.note = "Diriginte";
  }
  for (const s of catalogSubjects) {
    if (!s?.teacher) continue;
    const t = ensure(s.teacher);
    if (s.subject) t.subjects.add(s.subject);
  }
  for (const e of entries) {
    if (!e.teacher) continue;
    const t = ensure(e.teacher);
    if (e.subject) t.subjects.add(e.subject);
    t.lessons += 1;
  }
  return [...map.values()]
    .map((t) => ({ name: t.name, subjects: [...t.subjects].sort(), lessons: t.lessons, note: t.note }))
    .sort((a, b) => a.name.localeCompare(b.name));
}

function extractEmbeddedCalendarEntries($) {
  const rawJson =
    getInputValue($, "P1_TIMETABLE") ||
    getInputValue($, "P1_CALENDAR_EVENTS") ||
    $("script#P1_CALENDAR_EVENTS, script[data-calendar-events]").first().text().trim();
  if (rawJson) {
    try {
      const parsed = JSON.parse(rawJson);
      if (Array.isArray(parsed)) return parseCalendarEventsWithDom($, parsed);
    } catch {
      // ignore non-JSON content
    }
  }
  const domEvents = [];
  $("[data-start][data-end]").each((_i, el) => {
    domEvents.push({
      start: el.attribs["data-start"],
      end: el.attribs["data-end"],
      className: el.attribs.class || "",
      title: $(el).html() || "",
    });
  });
  return domEvents.length ? parseCalendarEventsWithDom($, domEvents) : [];
}

function findSemesterScope($, region) {
  for (const sel of region.selectors || [`#${region.id}`]) {
    const scope = $(sel);
    if (scope.length) return scope;
  }
  return $();
}

// Single-pass unified parser over the APEX dashboard HTML document
function parseFullPortal(html, today = todayChisinau(), { extraReports = [], extraCalendar = [] } = {}) {
  const $ = cheerio.load(html || "");
  const metadata = parseStudentMetadata($);
  const links = parseStudentLinks($);
  const baseStudent = {
    id: metadata.id,
    fullName: metadata.fullName,
    className: metadata.className,
    schoolName: metadata.schoolName,
  };
  const students = [];
  if (links.length === 0) {
    if (baseStudent.id || baseStudent.fullName) students.push(baseStudent);
  } else {
    for (const link of links) {
      const matchesBase = Boolean(baseStudent.id && link.studentId === baseStudent.id);
      if (matchesBase || (!baseStudent.id && (link.isCurrent || links.length === 1))) {
        students.push({
          id: baseStudent.id || link.studentId,
          fullName: baseStudent.fullName || link.name,
          className: baseStudent.className,
          schoolName: baseStudent.schoolName,
        });
      } else {
        students.push({ id: link.studentId, fullName: link.name, className: null, schoolName: null });
      }
    }
    if (baseStudent.id && !students.some((s) => s.id === baseStudent.id)) {
      students.unshift(baseStudent);
    }
  }
  const student =
    (baseStudent.id && students.find((s) => s.id === baseStudent.id)) ||
    students.find((_s, idx) => links[idx]?.isCurrent) ||
    (baseStudent.id || baseStudent.fullName ? baseStudent : students[0]) ||
    baseStudent;

  const evalVal = getInputValue($, ITEM_LAST_EVALS);
  const evalRoot = evalVal ? $(`<div>${evalVal}</div>`) : $("#P1_LAST_3_EVAL_LIST");
  const grades = evalRoot.length ? parseRecentGradesFromRoot($, evalRoot, metadata.currentSemester, today) : [];

  const hwVal = getInputValue($, ITEM_HOMEWORK);
  const hwRoot = hwVal ? $(`<div>${hwVal}</div>`) : $("#P1_HOMEWORK");
  const homework = hwRoot.length ? parseHomeworkFromRoot($, hwRoot, today) : [];

  const rawAbsenceSummary = parseAbsenceSummary($);

  const annualMap = new Map();
  const reports = [];
  for (const region of REGIONS) {
    const scope = findSemesterScope($, region);
    if (scope.length) {
      parseCombinedReportFromRoot($, scope, annualMap);
      const rep = parseSemesterReportFromRoot($, scope);
      if (rep.size) reports.push({ semester: region.semester, report: rep });
    }
  }
  for (const extra of extraReports) {
    if (extra?.report?.size) reports.push(extra);
  }
  const combined = parseCombinedReportFromRoot($, $.root(), annualMap);
  if (combined.sem1.size && !reports.some((r) => r.semester === 1)) {
    reports.push({ semester: 1, report: combined.sem1 });
  }
  if (combined.sem2.size && !reports.some((r) => r.semester === 2)) {
    reports.push({ semester: 2, report: combined.sem2 });
  }
  if (!reports.length) {
    const rep = parseSemesterReportFromRoot($, $.root());
    if (rep.size) reports.push({ semester: metadata.currentSemester || 1, report: rep });
  }
  if (!reports.length && grades.length) {
    const bySem = new Map();
    for (const g of grades) {
      const sem = g.semester || metadata.currentSemester || 1;
      if (!bySem.has(sem)) bySem.set(sem, new Map());
      const map = bySem.get(sem);
      if (!map.has(g.subject)) {
        map.set(g.subject, { subject: g.subject, marks: [], semesterAverage: null, teacher: null });
      }
      map.get(g.subject).marks.push({ value: g.value, date: g.date });
    }
    for (const [semester, report] of bySem) reports.push({ semester, report });
  }
  const catalog = buildCatalog(reports, annualMap);
  const averages = catalog.subjects
    .filter((s) => s.average !== null)
    .map((s) => ({ subject: s.subject, average: s.average }));

  const embeddedEntries = extractEmbeddedCalendarEntries($);
  const calendarEntries = embeddedEntries.length ? embeddedEntries : extraCalendar;
  const days = groupByDay(calendarEntries);
  const timetable = days[today] || [];
  const schedule = { weekStart: weekStart(today), days };
  const rawRecords = [...absenceRecords(calendarEntries), ...extractHtmlAbsenceRecords($, today)];
  const absences = buildSemesterAbsences(rawAbsenceSummary, rawRecords);
  const absenceSummary = absences.summary;
  const teachers = buildTeachers(calendarEntries, metadata, catalog.subjects);

  return {
    student,
    students,
    metadata,
    grades,
    reusita: catalog,
    catalog,
    averages,
    generalAverage: catalog.generalAverage,
    absenceSummary,
    absente: absences,
    absences,
    timetable,
    orar: schedule,
    schedule,
    homework,
    teachers,
  };
}

// resilience: fingerprint, rate gate, circuit breaker

const ACCEPT_NAVIGATE = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*\u002f*;q=0.8";
const ACCEPT_LANGUAGE = "ro-MD,ro;q=0.9,en-US;q=0.8,en;q=0.7";

const chromeProfile = (major, platform, brand) => ({
  userAgent:
    platform === "Windows"
      ? `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${major}.0.0.0 Safari/537.36`
      : `Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${major}.0.0.0 Safari/537.36`,
  hints: {
    "Sec-CH-UA": `"Chromium";v="${major}", "Google Chrome";v="${major}", "${brand}"`,
    "Sec-CH-UA-Mobile": "?0",
    "Sec-CH-UA-Platform": `"${platform}"`,
  },
});

const firefoxProfile = (major, platform) => ({
  userAgent:
    platform === "Windows"
      ? `Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:${major}.0) Gecko/20100101 Firefox/${major}.0`
      : `Mozilla/5.0 (X11; Linux x86_64; rv:${major}.0) Gecko/20100101 Firefox/${major}.0`,
  hints: {},
});

const FINGERPRINTS = [
  chromeProfile(131, "Linux", 'Not_A Brand";v="24'),
  chromeProfile(131, "Windows", 'Not_A Brand";v="24'),
  chromeProfile(130, "Linux", 'Not?A_Brand";v="99'),
  chromeProfile(130, "Windows", 'Not?A_Brand";v="99'),
  chromeProfile(129, "Windows", 'Not=A?Brand";v="8'),
  firefoxProfile(133, "Linux"),
  firefoxProfile(132, "Windows"),
];

// A browser identity stays constant for a session; it is rotated between sessions
function pickFingerprint(random = Math.random) {
  return FINGERPRINTS[Math.floor(random() * FINGERPRINTS.length)];
}

// kind "navigate" for page loads, "xhr" for APEX ajax and login submissions
function fingerprintHeaders(fp, { kind = "navigate", hasReferer = false } = {}) {
  const base = {
    "User-Agent": fp.userAgent,
    "Accept-Language": ACCEPT_LANGUAGE,
    ...fp.hints,
  };
  if (kind === "xhr") {
    return { ...base, Accept: "*\u002f*", "Sec-Fetch-Dest": "empty", "Sec-Fetch-Mode": "cors", "Sec-Fetch-Site": "same-origin", Priority: "u=1, i" };
  }
  return {
    ...base,
    Accept: ACCEPT_NAVIGATE,
    "Upgrade-Insecure-Requests": "1",
    "Sec-Fetch-Dest": "document",
    "Sec-Fetch-Mode": "navigate",
    "Sec-Fetch-Site": hasReferer ? "same-origin" : "none",
    "Sec-Fetch-User": "?1",
    Priority: "u=0, i",
  };
}

const realSleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// Capacity-one token bucket used only when throttling manual force-refresh/sync actions
class RateGate {
  constructor({ minIntervalMs = 3000, jitterMinMs = 200, jitterMaxMs = 500, now = Date.now, sleep = realSleep, random = Math.random } = {}) {
    Object.assign(this, { minIntervalMs, jitterMinMs, jitterMaxMs, now, sleep, random });
    this.last = 0;
    this.tail = Promise.resolve();
  }

  wait() {
    const run = this.tail.then(async () => {
      const jitter = this.jitterMinMs + this.random() * (this.jitterMaxMs - this.jitterMinMs);
      const due = (this.last === 0 ? this.now() : this.last + this.minIntervalMs) + jitter;
      const delay = due - this.now();
      if (delay > 0) await this.sleep(delay);
      this.last = this.now();
    });
    this.tail = run.catch(() => undefined);
    return run;
  }
}

// Process-wide: when the portal signals throttling or maintenance, all outbound traffic stops
class CircuitBreaker {
  constructor(now = Date.now) {
    this.now = now;
    this.until = 0;
  }

  trip(seconds = DEFAULT_WAIT_SECONDS) {
    this.until = Math.max(this.until, this.now() + Math.max(1, seconds) * 1000);
  }

  remainingSeconds() {
    return Math.max(0, Math.ceil((this.until - this.now()) / 1000));
  }

  ensureClosed() {
    const remaining = this.remainingSeconds();
    if (remaining > 0) throw new SimeRateLimitError("Portal requests are suspended after throttling", remaining);
  }

  reset() {
    this.until = 0;
  }
}

const breaker = new CircuitBreaker();
const gates = new Map();

function gateFor(key) {
  if (!gates.has(key)) gates.set(key, new RateGate());
  return gates.get(key);
}

// HTTP transport

function retryAfter(headers) {
  const v = Number.parseInt(String(headers.get("retry-after") ?? "").trim(), 10);
  if (!Number.isFinite(v)) return DEFAULT_WAIT_SECONDS;
  return Math.max(1, Math.min(v, MAX_WAIT_SECONDS));
}

function decodeBody(bytes, contentType) {
  const cs = /charset=["']?([\w-]+)/i.exec(contentType || "");
  try {
    return new TextDecoder(cs ? cs[1] : "utf-8").decode(bytes);
  } catch {
    return new TextDecoder("utf-8").decode(bytes);
  }
}

class SimeHttp {
  constructor({ gate = new RateGate(), fingerprint = pickFingerprint(), breaker: br = breaker, cookies = new Map() } = {}) {
    this.gate = gate;
    this.fingerprint = fingerprint;
    this.breaker = br;
    this.cookies = cookies;
    this.lastUrl = null;
  }

  clearCookies() {
    this.cookies.clear();
    this.lastUrl = null;
  }

  storeCookies(res) {
    for (const line of res.headers.getSetCookie?.() ?? []) {
      const [pair, ...attrs] = line.split(";");
      const eq = pair.indexOf("=");
      if (eq < 1) continue;
      const name = pair.slice(0, eq).trim();
      const value = pair.slice(eq + 1).trim();
      const maxAge = attrs.map((a) => /^\s*max-age=(-?\d+)/i.exec(a)).find(Boolean);
      if (value === "" || (maxAge && Number(maxAge[1]) <= 0)) this.cookies.delete(name);
      else this.cookies.set(name, value);
    }
  }

  cookieHeader() {
    return [...this.cookies].map(([k, v]) => `${k}=${v}`).join("; ");
  }

  // Redirects are followed manually so every hop is validated against agenda.sime.md.
  // RateGate is only triggered when gated: true is passed (manual sync / rapid force-refresh).
  async request(method, url, { data = null, headers = {}, kind = "navigate", gated = false } = {}) {
    let current = requireSimeUrl(url);
    let body = data ? new URLSearchParams(data) : undefined;
    if (gated) {
      this.breaker.ensureClosed();
      await this.gate.wait();
    }
    for (let hop = 0; hop <= MAX_REDIRECTS; hop += 1) {
      this.breaker.ensureClosed();
      let res;
      try {
        const h = { ...fingerprintHeaders(this.fingerprint, { kind, hasReferer: this.lastUrl !== null }), ...headers };
        if (kind === "navigate" && this.lastUrl && !h.Referer) h.Referer = this.lastUrl;
        const cookie = this.cookieHeader();
        if (cookie) h.Cookie = cookie;
        res = await fetch(current, {
          method,
          headers: h,
          body,
          redirect: "manual",
          signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
        });
        this.storeCookies(res);
        if ([301, 302, 303, 307, 308].includes(res.status)) {
          const location = res.headers.get("location");
          await res.body?.cancel();
          if (!location) throw new SimeNetworkError("Redirect without Location header");
          current = resolveSimeUrl(location, current);
          if ([301, 302, 303].includes(res.status)) {
            method = "GET";
            body = undefined;
          }
          continue;
        }
        this.raiseForStatus(res);
        const text = await this.readBody(res);
        if (kind === "navigate") this.lastUrl = current;
        return { status: res.status, url: current, text };
      } catch (err) {
        if (err instanceof SimeRateLimitError) this.breaker.trip(err.waitSeconds);
        if (err instanceof SimeError) throw err;
        if (err?.name === "TimeoutError" || err?.name === "AbortError") throw new SimeNetworkError("Request timed out");
        throw new SimeNetworkError("Network error");
      }
    }
    throw new SimeNetworkError("Too many redirects");
  }

  raiseForStatus(res) {
    const s = res.status;
    if (s === 429 || s === 503) throw new SimeRateLimitError(`HTTP ${s} from agenda.sime.md`, retryAfter(res.headers));
    if ([401, 403, 404, 440].includes(s)) throw new SimeSessionExpiredError(`HTTP ${s} from agenda.sime.md`);
    if (s >= 400) throw new SimeNetworkError(`HTTP ${s} from agenda.sime.md`);
  }

  async readBody(res) {
    const declared = Number(res.headers.get("content-length"));
    if (Number.isFinite(declared) && declared > MAX_BODY_BYTES) throw new SimeNetworkError("Response body too large");
    const chunks = [];
    let size = 0;
    if (res.body) {
      for await (const chunk of res.body) {
        size += chunk.length;
        // leaving the loop cancels the stream
        if (size > MAX_BODY_BYTES) throw new SimeNetworkError("Response body too large");
        chunks.push(chunk);
      }
    }
    return decodeBody(Buffer.concat(chunks), res.headers.get("content-type"));
  }
}

// APEX login & atomic handshake

const stripReplacement = (s) => s.replace(/\uFFFD/g, "");

class SimeAuth {
  constructor(username, password, role = "parent", br = breaker) {
    this.username = username;
    this.password = password;
    this.role = role === "student" ? "student" : "parent";
    this.breaker = br;
    this.sessionId = null;
    this.portalUrl = null;
    this.credentialsRejected = false;
  }

  get isAuthenticated() {
    return this.sessionId !== null && this.portalUrl !== null;
  }

  invalidate() {
    this.sessionId = null;
    this.portalUrl = null;
  }

  async login(http, { force = false } = {}) {
    this.breaker.ensureClosed();
    if (this.credentialsRejected && !force) {
      throw new SimeAuthError("Credentials were rejected earlier; log in again to retry");
    }
    this.invalidate();
    http.clearCookies();
    const userType = this.role === "parent" ? "1" : "2";
    const userTypeName = this.role === "parent" ? "Autentificare părinte" : "Autentificare elev";

    const pub = await http.request("GET", PUBLIC_PORTAL_URL, { gated: false });
    const dialogUrl = findDialogUrl(pub.text, userType);
    const dialog = await http.request("GET", dialogUrl, { gated: false });

    const $ = cheerio.load(dialog.text);
    const action = $("form").first().attr("action");
    const inputValue = (id) => {
      const el = $("input").toArray().find((e) => e.attribs.id === id);
      return typeof el?.attribs.value === "string" ? el.attribs.value : null;
    };
    const tokens = {};
    for (const name of ["pFlowId", "pFlowStepId", "pInstance", "pPageSubmissionId", "pSalt", "pPageItemsProtected"]) {
      tokens[name] = inputValue(name);
    }
    if (!action || Object.values(tokens).some((v) => v === null)) {
      throw new SimeParseError("Login form is missing required APEX fields");
    }
    const reloadOnSubmit = inputValue("pReloadOnSubmit") || "S";
    const checksums = {};
    $("input[data-for]").each((_i, el) => {
      checksums[el.attribs["data-for"]] = el.attribs.value ?? "";
    });
    const payload = {
      salt: tokens.pSalt,
      pageItems: {
        itemsToSubmit: [
          { n: "P101_USER_TYPE", v: userType, ck: checksums.P101_USER_TYPE ?? null },
          { n: "P101_USER_TYPE_NAME", v: userTypeName, ck: checksums.P101_USER_TYPE_NAME ?? null },
          { n: "P101_USERNAME", v: this.username },
          { n: "P101_PASSWORD", v: this.password },
        ],
        protected: tokens.pPageItemsProtected,
        rowVersion: "",
        formRegionChecksums: [],
      },
    };
    const postUrl = resolveSimeUrl(action, `${BASE_URL}/ords/`);
    const response = await http.request("POST", postUrl, {
      kind: "xhr",
      gated: false,
      data: {
        p_flow_id: tokens.pFlowId,
        p_flow_step_id: tokens.pFlowStepId,
        p_instance: tokens.pInstance,
        p_page_submission_id: tokens.pPageSubmissionId,
        p_request: "LOGIN",
        p_reload_on_submit: reloadOnSubmit,
        p_json: JSON.stringify(payload),
      },
      headers: {
        Referer: dialogUrl,
        Origin: BASE_URL,
        "X-Requested-With": "XMLHttpRequest",
        Accept: "application/json, text/javascript, *\u002f*; q=0.01",
      },
    });
    this.handleLoginResponse(response.text);
  }

  handleLoginResponse(body) {
    let data;
    try {
      data = JSON.parse(body);
    } catch {
      throw new SimeParseError("Login response is not valid JSON");
    }
    const redirect = data !== null && typeof data === "object" && !Array.isArray(data) ? data.redirectURL : null;
    if (typeof redirect !== "string") throw new SimeParseError("Login response has no redirectURL");

    if (redirect.includes("notification_msg=")) this.raiseLoginFailure(redirect);

    const match = /atob\("([^"]+)"\)/.exec(redirect);
    if (!match) throw new SimeParseError("Login response has an unrecognized redirect format");
    let target;
    try {
      target = new TextDecoder("utf-8", { fatal: true }).decode(Buffer.from(match[1], "base64"));
    } catch {
      throw new SimeParseError("Login redirect target could not be decoded");
    }
    const portalUrl = resolveSimeUrl(target);
    const session = /[?&;]session=(\d+)/.exec(portalUrl);
    if (!session) throw new SimeParseError("Login redirect has no session id");
    this.portalUrl = portalUrl;
    this.sessionId = session[1];
    this.credentialsRejected = false;
  }

  raiseLoginFailure(redirect) {
    let message = "Authentication failed";
    let wait = DEFAULT_WAIT_SECONDS;
    const m = /notification_msg=([^&"]+)/.exec(redirect);
    if (m) {
      const raw = safeUnquote(m[1]).split("/")[0];
      const b64 = raw + "=".repeat((4 - (raw.length % 4)) % 4);
      if (/^[A-Za-z0-9+/=_-]*$/.test(b64)) {
        const decoded = stripReplacement(new TextDecoder("utf-8").decode(Buffer.from(b64, "base64")));
        const text = decoded.replace(/<[^>]+>/g, " ").split(/\s+/).filter(Boolean).join(" ").slice(0, 200);
        if (text) message = text;
        const seconds = /(\d+)\s+seconds/.exec(text);
        if (seconds) wait = Math.max(1, Math.min(Number(seconds[1]), MAX_WAIT_SECONDS));
      }
    }
    const lowered = message.toLowerCase();
    if (lowered.includes("blocked") || lowered.includes("wait")) {
      this.breaker.trip(wait);
      throw new SimeRateLimitError(message, wait);
    }
    this.credentialsRejected = true;
    throw new SimeAuthError(message);
  }
}

function findDialogUrl(publicHtml, userType) {
  const $ = cheerio.load(publicHtml);
  const needle = `p101_user_type=${userType}`;
  for (const a of $("a[href]").toArray()) {
    const href = String(a.attribs.href);
    if (!href.includes("login_desktop") || !href.includes(needle)) continue;
    const start = href.indexOf("'");
    const end = href.indexOf("'", start + 1);
    if (start === -1 || end === -1) continue;
    return resolveSimeUrl(unescapeJs(href.slice(start + 1, end)));
  }
  throw new SimeParseError("Login dialog link not found on the public page");
}

// Runs the complete login sequence + landing page fetch without inter-step RateGate delays
async function executeHandshake(cookieJar = new Map(), { username = "", password = "", role = "parent", http, auth } = {}) {
  const transport = http || new SimeHttp({ cookies: cookieJar instanceof Map ? cookieJar : new Map() });
  if (cookieJar instanceof Map) transport.cookies = cookieJar;
  const authenticator = auth || new SimeAuth(username, password, role, transport.breaker);
  await authenticator.login(transport, { force: true });
  const landing = await transport.request("GET", authenticator.portalUrl, { gated: false });
  const pathname = new URL(landing.url).pathname.toLowerCase();
  if (pathname.endsWith("/public") || pathname.includes("login")) {
    throw new SimeSessionExpiredError("Portal returned a non-dashboard page");
  }
  if (MAINTENANCE_RE.test(landing.text.slice(0, 4000)) && !landing.text.includes(ITEM_STUDENT_ID)) {
    transport.breaker.trip(DEFAULT_WAIT_SECONDS);
    throw new SimeRateLimitError("Portal reports maintenance", DEFAULT_WAIT_SECONDS);
  }
  return {
    sessionId: authenticator.sessionId,
    portalUrl: authenticator.portalUrl,
    url: landing.url,
    html: landing.text,
    cookies: transport.cookies,
  };
}

// client

class SimeClient {
  constructor(username, password, role, { http, auth } = {}) {
    this.http = http || new SimeHttp({ gate: gateFor(sha(username.toLowerCase())) });
    this.auth = auth || new SimeAuth(username, password, role, this.http.breaker);
    this.studentUrls = new Map();
    this.knownStudents = new Map();
    this.portalIds = new Set();
    this.pages = new Map();
    this.lastRenewal = 0;
  }

  resetSession() {
    this.auth.invalidate();
    this.studentUrls.clear();
    this.portalIds.clear();
    this.pages.clear();
    this.http.clearCookies();
  }

  async executeHandshake(cookieJar = this.http.cookies) {
    this.resetSession();
    const res = await executeHandshake(cookieJar, { http: this.http, auth: this.auth });
    const $ = cheerio.load(res.html);
    if (!isDashboard($)) throw new SimeSessionExpiredError("Portal returned a non-dashboard page");
    const page = { url: this.auth.portalUrl, html: res.html, $, fetchedAt: Date.now() };
    this.pages.set(this.auth.portalUrl, page);
    this.registerStudents(page);
    return res;
  }

  async enrichPortalPage(page, requestedStudentId = "") {
    const extraReports = [];
    for (const region of REGIONS) {
      const inlineScope = findSemesterScope(page.$, region);
      if (inlineScope.length && parseSemesterReportFromRoot(page.$, inlineScope).size > 0) continue;
      const ajaxId = findReportAjaxId(page.html, region.id);
      if (!ajaxId) continue;
      try {
        const repHtml = await this.ajax(page, ajaxId);
        const rep = parseSemesterReport(repHtml);
        if (rep.size) extraReports.push({ semester: region.semester, report: rep });
      } catch {
        // fallback to inline report data
      }
    }

    const data = parseFullPortal(page.html, todayChisinau(), { extraReports });
    const effectiveId = data.metadata.id || requestedStudentId || data.student?.id || "";
    if (effectiveId) {
      data.metadata.id = effectiveId;
      const prev = this.knownStudents.get(effectiveId);
      data.student = {
        id: effectiveId,
        fullName: data.metadata.fullName || prev?.fullName || data.student?.fullName || effectiveId,
        className: data.metadata.className || prev?.className || null,
        schoolName: data.metadata.schoolName || prev?.schoolName || null,
      };
    }
    for (const s of data.students) {
      if (!s?.id) continue;
      const prev = this.knownStudents.get(s.id);
      this.knownStudents.set(s.id, {
        id: s.id,
        fullName: s.fullName || prev?.fullName || s.id,
        className: s.className || prev?.className || null,
        schoolName: s.schoolName || prev?.schoolName || null,
      });
    }
    if (data.student?.id) {
      this.knownStudents.set(data.student.id, data.student);
    }
    if (this.knownStudents.size) {
      data.students = [...this.knownStudents.values()];
    }
    return data;
  }

  // Authenticates and returns { html, data: parseFullPortal(html) }, or null when credentials are rejected
  async loginAndParsePortal() {
    this.resetSession();
    try {
      const res = await executeHandshake(this.http.cookies, { http: this.http, auth: this.auth });
      const $ = cheerio.load(res.html);
      const page = { url: this.auth.portalUrl, html: res.html, $, fetchedAt: Date.now() };
      this.pages.set(this.auth.portalUrl, page);
      this.registerStudents(page);
      const data = await this.enrichPortalPage(page);
      if (!data.student?.id && !data.students.length) {
        throw new SimeSessionExpiredError("Portal returned a non-dashboard page");
      }
      return { html: res.html, data };
    } catch (err) {
      if (err instanceof SimeRateLimitError) throw err;
      if (err instanceof SimeAuthError) return null;
      throw err;
    }
  }

  // Returns false when credentials are rejected
  async authenticate() {
    const res = await this.loginAndParsePortal();
    return res !== null;
  }

  async ensureLogin() {
    if (!this.auth.isAuthenticated) {
      this.pages.clear();
      this.studentUrls.clear();
      await this.executeHandshake(this.http.cookies);
    }
    return this.auth.portalUrl;
  }

  // Exactly one re-login per expiry; a second expiry within a minute of a renewal is final
  async withRetry(operation) {
    try {
      return await operation();
    } catch (err) {
      if (!(err instanceof SimeSessionExpiredError)) throw err;
      if (Date.now() - this.lastRenewal < RENEWAL_MIN_GAP_MS) throw err;
      this.lastRenewal = Date.now();
      this.resetSession();
      return operation();
    }
  }

  async fetchDashboard(url, { gated = false, bypassCache = false } = {}) {
    const cached = this.pages.get(url);
    if (!bypassCache && cached && Date.now() - cached.fetchedAt < PAGE_TTL_MS) return cached;
    const resp = await this.http.request("GET", url, { gated });
    const $ = cheerio.load(resp.text);
    const pathname = new URL(resp.url).pathname.toLowerCase();
    if (!isDashboard($) || pathname.endsWith("/public") || pathname.includes("login")) {
      if (MAINTENANCE_RE.test(textOf($("body")[0] || $.root()[0], " ").slice(0, 2000))) {
        this.http.breaker.trip(DEFAULT_WAIT_SECONDS);
        throw new SimeRateLimitError("Portal reports maintenance", DEFAULT_WAIT_SECONDS);
      }
      throw new SimeSessionExpiredError("Portal returned a non-dashboard page");
    }
    const page = { url, html: resp.text, $, fetchedAt: Date.now() };
    this.pages.set(url, page);
    return page;
  }

  async loadPortal({ gated = false, bypassCache = false } = {}) {
    const portalUrl = await this.ensureLogin();
    const page = await this.fetchDashboard(portalUrl, { gated, bypassCache });
    this.registerStudents(page);
    return page;
  }

  registerStudents(page) {
    const profile = parseStudentProfile(page.$);
    const links = parseStudentLinks(page.$);
    const students = [];
    this.portalIds = new Set();
    if (links.length === 0) {
      if (profile.id) {
        this.studentUrls.set(profile.id, page.url);
        this.portalIds.add(profile.id);
        this.knownStudents.set(profile.id, profile);
      }
      return profile.id || profile.fullName ? [profile] : [];
    }
    for (const link of links) {
      let url;
      try {
        url = resolveSimeUrl(link.href);
      } catch (err) {
        if (err instanceof SimeNetworkError) continue;
        throw err;
      }
      this.studentUrls.set(link.studentId, url);
      const matchesProfile = Boolean(profile.id && link.studentId === profile.id);
      if (matchesProfile || (!profile.id && (link.isCurrent || links.length === 1))) {
        this.portalIds.add(link.studentId);
        if (profile.id) {
          this.studentUrls.set(profile.id, url);
          this.portalIds.add(profile.id);
        }
        const entry = {
          id: profile.id || link.studentId,
          fullName: profile.fullName || link.name,
          className: profile.className,
          schoolName: profile.schoolName,
        };
        this.knownStudents.set(entry.id, entry);
        students.push(entry);
      } else {
        const prev = this.knownStudents.get(link.studentId);
        const entry = {
          id: link.studentId,
          fullName: prev?.fullName || link.name,
          className: prev?.className || null,
          schoolName: prev?.schoolName || null,
        };
        this.knownStudents.set(entry.id, entry);
        students.push(entry);
      }
    }
    return students;
  }

  // Switches active student via f?p=AGENDA:1:${apexSession}::NO::P1_ELEVE_ID:${studentId} and parses full student state
  async switchStudent(studentId, { gated = false } = {}) {
    const cleanId = String(studentId || "").trim();
    if (!cleanId) throw new SimeStudentNotFoundError("Student ID is required");
    return this.withRetry(async () => {
      await this.ensureLogin();
      const targetUrl = studentSwitchUrl(this.auth.sessionId, cleanId);
      let page = await this.fetchDashboard(targetUrl, { gated, bypassCache: true });
      const parsedId = parseStudentProfile(page.$).id;
      if (parsedId && parsedId !== cleanId && this.studentUrls.has(cleanId)) {
        const altUrl = this.studentUrls.get(cleanId);
        if (altUrl && altUrl !== targetUrl) {
          page = await this.fetchDashboard(altUrl, { gated: false, bypassCache: true });
        }
      }
      this.studentUrls.set(cleanId, page.url);
      this.registerStudents(page);
      return this.enrichPortalPage(page, cleanId);
    });
  }

  async loadStudent(studentId, { gated = false, bypassCache = false } = {}) {
    const portal = await this.loadPortal({ gated: gated && (!studentId || this.portalIds.has(studentId)), bypassCache });
    if (!studentId || this.portalIds.has(studentId)) return portal;
    const url = this.studentUrls.get(studentId) || studentSwitchUrl(this.auth.sessionId, studentId);
    return this.fetchDashboard(url, { gated, bypassCache });
  }

  fetchStudentPortalHtml(studentId, { gated = false, bypassCache = false } = {}) {
    return this.withRetry(async () => (await this.loadStudent(studentId, { gated, bypassCache })).html);
  }

  fetchStudentPortalData(studentId, { gated = false, bypassCache = false } = {}) {
    return this.withRetry(async () => {
      const page = await this.loadStudent(studentId, { gated, bypassCache });
      return this.enrichPortalPage(page, studentId);
    });
  }

  async ajax(page, ajaxId, extra = {}) {
    const { $ } = page;
    const student = getInputValue($, ITEM_STUDENT_ID);
    const checksum = getItemChecksum($, ITEM_STUDENT_ID);
    const salt = getInputValue($, "pSalt");
    const protectedItems = getInputValue($, "pPageItemsProtected");
    const sessionId = this.auth.sessionId;
    if (!(student && checksum && salt && sessionId)) {
      throw new SimeParseError("Dashboard page lacks the items required for APEX requests");
    }
    const payload = {
      salt,
      pageItems: {
        itemsToSubmit: [{ n: ITEM_STUDENT_ID, v: student, ck: checksum }],
        protected: protectedItems,
        rowVersion: "",
        formRegionChecksums: [],
      },
    };
    const resp = await this.http.request("POST", ajaxUrl(sessionId), {
      kind: "xhr",
      gated: false,
      data: {
        p_flow_id: FLOW_ID,
        p_flow_step_id: FLOW_STEP_ID,
        p_instance: sessionId,
        p_request: `PLUGIN=${ajaxId}`,
        p_json: JSON.stringify(payload),
        ...extra,
      },
      headers: {
        Referer: page.url,
        Origin: BASE_URL,
        "X-Requested-With": "XMLHttpRequest",
        Accept: "text/html, *\u002f*; q=0.01",
      },
    });
    return resp.text;
  }

  async calendarRange(studentId, startIso, endIso) {
    const page = await this.loadStudent(studentId);
    const ajaxId = findCalendarAjaxId(page.html);
    if (ajaxId === null) throw new SimeParseError("Calendar widget not found on the dashboard");
    const entries = [];
    for (let from = startIso; from <= endIso; from = addDays(from, CALENDAR_CHUNK_DAYS)) {
      const to = addDays(from, CALENDAR_CHUNK_DAYS - 1) < endIso ? addDays(from, CALENDAR_CHUNK_DAYS - 1) : endIso;
      const body = await this.ajax(page, ajaxId, {
        x01: "GET",
        x02: `${from.replaceAll("-", "")}000000`,
        x03: `${to.replaceAll("-", "")}235959`,
      });
      let events;
      try {
        events = JSON.parse(body);
      } catch {
        throw new SimeParseError("Calendar response is not valid JSON");
      }
      if (!Array.isArray(events)) throw new SimeParseError("Calendar response has an unexpected shape");
      entries.push(...parseCalendarEvents(events));
    }
    return entries;
  }

  getStudents() {
    return this.withRetry(async () => this.registerStudents(await this.loadPortal()));
  }

  getMetadata(studentId) {
    return this.withRetry(async () => parseStudentMetadata((await this.loadStudent(studentId)).$));
  }

  getLatestGrades(studentId) {
    return this.withRetry(async () => {
      const page = await this.loadStudent(studentId);
      return parseRecentGrades(getInputValue(page.$, ITEM_LAST_EVALS), parseCurrentSemester(page.$));
    });
  }

  getAbsenceSummary(studentId) {
    return this.withRetry(async () => parseAbsenceSummary((await this.loadStudent(studentId)).$));
  }

  getHomework(studentId) {
    return this.withRetry(async () => parseHomework(getInputValue((await this.loadStudent(studentId)).$, ITEM_HOMEWORK)));
  }

  getCatalog(studentId) {
    return this.withRetry(async () => {
      const page = await this.loadStudent(studentId);
      const data = await this.enrichPortalPage(page, studentId);
      if (data.reusita.disciplines.length) return data.reusita;
      const reports = [];
      for (const region of REGIONS) {
        const ajaxId = findReportAjaxId(page.html, region.id);
        if (ajaxId === null) continue;
        reports.push({ semester: region.semester, report: parseSemesterReport(await this.ajax(page, ajaxId)) });
      }
      if (!reports.length) throw new SimeParseError("Semester report regions not found on the dashboard");
      return buildCatalog(reports);
    });
  }

  // Lessons for the Monday-Sunday week containing the given date, grouped by day
  getWeek(studentId, isoDate) {
    return this.withRetry(async () => {
      const start = weekStart(isoDate);
      const entries = await this.calendarRange(studentId, start, addDays(start, 6));
      return { weekStart: start, days: groupByDay(entries) };
    });
  }

  // Per-lesson absence markers depend on calendar markup and may be missing
  getAbsenceRecords(studentId) {
    return this.withRetry(async () => {
      const today = todayChisinau();
      const year = Number(today.slice(0, 4));
      const startYear = Number(today.slice(5, 7)) >= 9 ? year : year - 1;
      return absenceRecords(await this.calendarRange(studentId, `${startYear}-09-01`, today));
    });
  }

  // Teachers are derived from the lesson entries of a two to three week window plus class master metadata
  getTeachers(studentId) {
    return this.withRetry(async () => {
      const page = await this.loadStudent(studentId);
      const meta = parseStudentMetadata(page.$);
      const today = todayChisinau();
      const entries = await this.calendarRange(studentId, addDays(today, -7), addDays(today, 14));
      return buildTeachers(entries, meta);
    });
  }
}

// web layer

const TTL = {
  overview: 60_000,
  schedule: 60_000,
  homework: 60_000,
  grades: 120_000,
  catalog: 120_000,
  reusita: 120_000,
  absences: 120_000,
  students: 15 * 60_000,
  profile: 15 * 60_000,
  teachers: 15 * 60_000,
};
const SESSION_IDLE_MS = 30 * 60_000;
const LOGIN_MAX_REJECTIONS = 4;
const LOGIN_LOCK_MS = 60_000;

// token -> { client, role, lastUsed, lastSyncAt, defaultStudentId, students, studentList, cache, chain }; memory only
const sessions = new Map();
// sha256(username) -> { rejections, until }
const loginGuard = new Map();

function sha(s) {
  return crypto.createHash("sha256").update(s).digest("hex");
}

function serialize(session, fn) {
  const run = session.chain.then(fn, fn);
  session.chain = run.catch(() => undefined);
  return run;
}

function storeStudentSessionState(session, studentId, data) {
  const id = String(studentId || data.student?.id || data.metadata?.id || "");
  const now = Date.now();
  const payload = { ...data, cachedAt: now };
  const record = {
    metadata: payload.metadata,
    reusita: payload.reusita,
    absente: payload.absente,
    orar: payload.orar,
    homework: payload.homework,
    cachedAt: now,
    data: payload,
  };
  if (id) {
    session.students[id] = record;
    session.cache.set(`overview:${id}`, { at: now, data: payload });
  }
  session.cache.set("overview:", { at: now, data: payload });
  if (Array.isArray(payload.students) && payload.students.length) {
    session.studentList = payload.students;
    session.cache.set("students", { at: now, data: payload.students });
  }
  return record;
}

const isUpstreamFailure = (err) => err instanceof SimeRateLimitError || err instanceof SimeNetworkError;

// Serves from memory inside the TTL without touching the portal. While the breaker is open
// or the upstream fails, an expired entry is served as stale instead of an error.
function cached(session, key, ttl, producer) {
  const meta = (hit, stale) => ({ data: hit.data, ageSeconds: Math.floor((Date.now() - hit.at) / 1000), stale });
  const fastHit = session.cache.get(key);
  if (fastHit && Date.now() - fastHit.at < ttl) return Promise.resolve(meta(fastHit, false));
  return serialize(session, async () => {
    const hit = session.cache.get(key);
    if (hit && Date.now() - hit.at < ttl) return meta(hit, false);
    if (hit && breaker.remainingSeconds() > 0) return meta(hit, true);
    try {
      const entry = { at: Date.now(), data: await producer() };
      session.cache.set(key, entry);
      return meta(entry, false);
    } catch (err) {
      if (hit && isUpstreamFailure(err)) return meta(hit, true);
      throw err;
    }
  });
}

async function resolveStudent(session, requested) {
  const { data } = await cached(session, "students", TTL.students, () => session.client.getStudents());
  const target = requested || session.defaultStudentId || "";
  const student = target ? data.find((s) => s.id === target) || session.students[target]?.metadata : data[0];
  if (!student) throw new SimeStudentNotFoundError("No matching student");
  return { student, students: data };
}

function sendError(res, err, token) {
  if (err instanceof SimeRateLimitError) {
    res.set("Retry-After", String(err.waitSeconds));
    return res.status(429).json({ error: err.message, waitSeconds: err.waitSeconds, failSafe: true });
  }
  if (err instanceof SimeAuthError) {
    if (token) sessions.delete(token);
    return res.status(401).json({ error: err.message });
  }
  if (err instanceof SimeStudentNotFoundError) return res.status(404).json({ error: err.message });
  if (err instanceof SimeError) return res.status(502).json({ error: err.message });
  return res.status(500).json({ error: "Internal error" });
}

function requireSession(req, res, next) {
  const m = /^Bearer ([A-Za-z0-9_-]{20,128})$/.exec(req.get("authorization") || "");
  const session = m ? sessions.get(m[1]) : undefined;
  if (!session) return res.status(401).json({ error: "Not authenticated" });
  session.lastUsed = Date.now();
  req.token = m[1];
  req.session = session;
  return next();
}

// Registers GET /api/<name>; build(client, student, req) returns the payload, keyFor adds query parts
function dataRoute(app, name, ttl, build, { keyFor = () => "", validate = () => null } = {}) {
  app.get(`/api/${name}`, requireSession, async (req, res) => {
    try {
      const problem = validate(req);
      if (problem) return res.status(400).json({ error: problem });
      const requested = typeof req.query.student === "string" ? req.query.student.slice(0, 128) : "";
      const { student, students } = await resolveStudent(req.session, requested);
      const key = `${name}:${student.id}:${keyFor(req)}`;
      const result = await cached(req.session, key, ttl, () => build(req.session.client, student, req, students));
      return res.json({ data: result.data, student, students, cache: { ageSeconds: result.ageSeconds, stale: result.stale } });
    } catch (err) {
      return sendError(res, err, req.token);
    }
  });
}

function createApp() {
  const app = express();
  app.disable("x-powered-by");

  app.use((_req, res, next) => {
    res.set({
      "Content-Security-Policy":
        "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
      "X-Content-Type-Options": "nosniff",
      "Referrer-Policy": "no-referrer",
    });
    next();
  });
  app.use("/api", (_req, res, next) => {
    res.set("Cache-Control", "no-store");
    next();
  });
  app.use(express.json({ limit: "4kb" }));

  app.post("/api/auth/login", async (req, res) => {
    const { username, password, role } = req.body ?? {};
    if (typeof username !== "string" || typeof password !== "string" || !username || !password || username.length > 128 || password.length > 256) {
      return res.status(400).json({ error: "Username and password are required" });
    }
    const guardKey = sha(username.toLowerCase());
    const guard = loginGuard.get(guardKey);
    if (guard && guard.until > Date.now()) {
      const waitSeconds = Math.ceil((guard.until - Date.now()) / 1000);
      res.set("Retry-After", String(waitSeconds));
      return res.status(429).json({ error: "Login locked after repeated rejections", waitSeconds, failSafe: true });
    }
    const normalizedRole = role === "student" ? "student" : "parent";
    const client = new SimeClient(username, password, normalizedRole);
    try {
      const loginResult = await client.loginAndParsePortal();
      if (!loginResult) {
        const entry = guard || { rejections: 0, until: 0 };
        entry.rejections += 1;
        if (entry.rejections >= LOGIN_MAX_REJECTIONS) {
          entry.until = Date.now() + LOGIN_LOCK_MS;
          entry.rejections = 0;
        }
        loginGuard.set(guardKey, entry);
        return res.status(401).json({ error: "Credentials were rejected" });
      }
      loginGuard.delete(guardKey);
      const { data } = loginResult;
      const now = Date.now();
      const studentId = data.student?.id || "";
      const session = {
        client,
        role: normalizedRole,
        lastUsed: now,
        lastSyncAt: now,
        defaultStudentId: studentId,
        students: {},
        studentList: data.students || [],
        cache: new Map(),
        chain: Promise.resolve(),
      };
      const record = storeStudentSessionState(session, studentId, data);
      const token = crypto.randomBytes(32).toString("base64url");
      sessions.set(token, session);
      return res.json({ token, role: normalizedRole, student: record.data.student, students: record.data.students, data: record.data });
    } catch (err) {
      return sendError(res, err, null);
    }
  });

  app.post("/api/auth/logout", requireSession, (req, res) => {
    sessions.delete(req.token);
    res.status(204).end();
  });

  app.post("/api/student/switch", requireSession, async (req, res) => {
    if (!req.session) return res.status(401).json({ error: "Not authenticated" });
    if (req.session.role === "student" || req.session.role === "E") return res.status(400).json({ error: "Students cannot switch students" });
    const rawId = req.body?.studentId;
    const studentId = typeof rawId === "string" || typeof rawId === "number" ? String(rawId).trim().slice(0, 128) : "";
    if (!studentId) {
      return res.status(400).json({ error: "studentId is required" });
    }
    try {
      const result = await serialize(req.session, async () => {
        const existing = req.session.students[studentId];
        if (existing && breaker.remainingSeconds() > 0) {
          req.session.defaultStudentId = studentId;
          return {
            data: existing.data,
            ageSeconds: Math.floor((Date.now() - existing.cachedAt) / 1000),
            stale: true,
          };
        }
        try {
          const data = await req.session.client.switchStudent(studentId, { gated: false });
          req.session.defaultStudentId = data.student?.id || studentId;
          const record = storeStudentSessionState(req.session, req.session.defaultStudentId, data);
          if (studentId !== req.session.defaultStudentId) {
            req.session.students[studentId] = record;
          }
          return { data: record.data, ageSeconds: 0, stale: false };
        } catch (err) {
          if (existing && isUpstreamFailure(err)) {
            req.session.defaultStudentId = studentId;
            return {
              data: existing.data,
              ageSeconds: Math.floor((Date.now() - existing.cachedAt) / 1000),
              stale: true,
            };
          }
          throw err;
        }
      });
      return res.json({
        studentId: result.data.student?.id || studentId,
        student: result.data.student,
        students: result.data.students,
        data: result.data,
        cache: { ageSeconds: result.ageSeconds, stale: result.stale },
      });
    } catch (err) {
      return sendError(res, err, req.token);
    }
  });

  app.get("/api/overview", requireSession, async (req, res) => {
    try {
      const requested = typeof req.query.student === "string" ? req.query.student.trim().slice(0, 128) : "";
      const force = req.query.force === "1" || req.query.force === "true";
      const targetId = requested || req.session.defaultStudentId || "";
      const studentEntry = targetId ? req.session.students[targetId] : undefined;
      const key = `overview:${targetId}`;
      const hit = studentEntry
        ? { at: studentEntry.cachedAt, data: studentEntry.data }
        : req.session.cache.get(key) || (!requested ? req.session.cache.get("overview:") : undefined);
      const now = Date.now();

      if (!force && hit && now - hit.at < TTL.overview) {
        return res.json({
          data: hit.data,
          student: hit.data.student,
          students: hit.data.students,
          cache: { ageSeconds: Math.floor((now - hit.at) / 1000), stale: false },
        });
      }

      const result = await serialize(req.session, async () => {
        const curStudentEntry = targetId ? req.session.students[targetId] : undefined;
        const currentHit = curStudentEntry
          ? { at: curStudentEntry.cachedAt, data: curStudentEntry.data }
          : req.session.cache.get(key) || (!requested ? req.session.cache.get("overview:") : undefined);
        if (!force && currentHit && Date.now() - currentHit.at < TTL.overview) {
          return { data: currentHit.data, ageSeconds: Math.floor((Date.now() - currentHit.at) / 1000), stale: false };
        }
        if (currentHit && breaker.remainingSeconds() > 0) {
          return { data: currentHit.data, ageSeconds: Math.floor((Date.now() - currentHit.at) / 1000), stale: true };
        }
        const shortWindow = Date.now() - (req.session.lastSyncAt || 0) < TTL.overview;
        const shouldGate = force || shortWindow;
        try {
          const isSwitching = Boolean(targetId && targetId !== req.session.defaultStudentId);
          const data = isSwitching
            ? await req.session.client.switchStudent(targetId, { gated: shouldGate })
            : await req.session.client.fetchStudentPortalData(targetId, { gated: shouldGate, bypassCache: true });
          const at = Date.now();
          req.session.lastSyncAt = at;
          const resolvedId = data.student?.id || targetId;
          if (resolvedId) req.session.defaultStudentId = resolvedId;
          const record = storeStudentSessionState(req.session, resolvedId, data);
          return { data: record.data, ageSeconds: 0, stale: false };
        } catch (err) {
          if (currentHit && isUpstreamFailure(err)) {
            return { data: currentHit.data, ageSeconds: Math.floor((Date.now() - currentHit.at) / 1000), stale: true };
          }
          throw err;
        }
      });

      return res.json({
        data: result.data,
        student: result.data.student,
        students: result.data.students,
        cache: { ageSeconds: result.ageSeconds, stale: result.stale },
      });
    } catch (err) {
      return sendError(res, err, req.token);
    }
  });

  dataRoute(app, "profile", TTL.profile, async (client, student, req) => {
    const hit = req.session.students[student.id];
    if (hit?.metadata && Date.now() - hit.cachedAt < TTL.profile) return { metadata: hit.metadata };
    return { metadata: await client.getMetadata(student.id) };
  });
  dataRoute(app, "teachers", TTL.teachers, async (client, student, req) => {
    const hit = req.session.students[student.id];
    if (hit?.data?.teachers?.length && Date.now() - hit.cachedAt < TTL.teachers) return { teachers: hit.data.teachers };
    return { teachers: await client.getTeachers(student.id) };
  });
  dataRoute(app, "grades", TTL.grades, async (client, student) => ({ grades: await client.getLatestGrades(student.id) }));
  dataRoute(app, "catalog", TTL.catalog, (client, student) => client.getCatalog(student.id));
  dataRoute(app, "reusita", TTL.reusita, (client, student) => client.getCatalog(student.id));
  dataRoute(app, "homework", TTL.homework, async (client, student) => ({ homework: await client.getHomework(student.id) }));
  dataRoute(app, "absences", TTL.absences, async (client, student, req) => {
    const hit = req.session.students[student.id];
    if (hit?.absente && Date.now() - hit.cachedAt < TTL.absences) return hit.absente;
    const summary = await client.getAbsenceSummary(student.id);
    const records = await client.getAbsenceRecords(student.id);
    return buildSemesterAbsences(summary, records);
  });
  dataRoute(app, "schedule", TTL.schedule, (client, student, req) => client.getWeek(student.id, req.query.date || todayChisinau()), {
    keyFor: (req) => weekStart(req.query.date || todayChisinau()),
    validate: (req) => {
      const d = req.query.date;
      if (d === undefined) return null;
      if (typeof d !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(d) || makeDate(+d.slice(0, 4), +d.slice(5, 7), +d.slice(8, 10)) === null) {
        return "date must be YYYY-MM-DD";
      }
      return Math.abs(dateMs(d) - dateMs(todayChisinau())) > 400 * DAY_MS ? "date is out of range" : null;
    },
  });

  app.get("/", (_req, res) => res.sendFile(path.join(ROOT, "index.html")));
  app.use("/public", express.static(path.join(ROOT, "public"), { index: false, dotfiles: "ignore" }));

  app.use((err, _req, res, _next) => {
    if (err?.type === "entity.parse.failed" || err?.type === "entity.too.large") {
      return res.status(400).json({ error: "Invalid request body" });
    }
    return res.status(500).json({ error: "Internal error" });
  });
  return app;
}

const app = createApp();

const sweep = setInterval(() => {
  const now = Date.now();
  for (const [token, s] of sessions) if (now - s.lastUsed > SESSION_IDLE_MS) sessions.delete(token);
  for (const [key, g] of loginGuard) if (g.until <= now && g.rejections === 0) loginGuard.delete(key);
}, 60_000);
sweep.unref();

export {
  app,
  breaker,
  buildCatalog,
  buildSemesterAbsences,
  buildTeachers,
  absenceRecords,
  CircuitBreaker,
  executeHandshake,
  fingerprintHeaders,
  FINGERPRINTS,
  inferAbsenceSemester,
  loginGuard,
  parseAbsenceSummary,
  parseCalendarEvents,
  parseDate,
  parseFullPortal,
  parseHomework,
  parseRecentGrades,
  parseSemesterReport,
  parseStudentMetadata,
  pickFingerprint,
  RateGate,
  requireSimeUrl,
  resolveSimeUrl,
  sessions,
  SimeAuth,
  SimeClient,
  SimeHttp,
  studentSwitchUrl,
  unescapeJs,
  weekStart,
};

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const port = Number(process.env.SIME_WEB_PORT) || 3000;
  const host = process.env.SIME_WEB_HOST || "127.0.0.1";
  app.listen(port, host, () => {
    console.log(`Listening on http://${host}:${port}`);
  });
}
