// Local web frontend backend for agenda.sime.md.
// Sections: errors, endpoints, time helpers, parsers, resilience (fingerprint, gate, breaker),
// HTTP transport, APEX login & handshake, client, web layer.

import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import express from "express";
import * as cheerio from "cheerio";

const ROOT = path.dirname(fileURLToPath(import.meta.url));

// errors

class SimeError extends Error {}
class SimeNetworkError extends SimeError {}
class SimeAuthError extends SimeError {}
class SimeAppSessionError extends SimeAuthError {}
class SimeLoginRejectedError extends SimeAuthError {}
class SimeSessionExpiredError extends SimeAuthError {}
class SimeRateLimitError extends SimeError {
  constructor(message, waitSeconds = 900) {
    super(message);
    this.waitSeconds = waitSeconds;
  }
}
class SimeMaintenanceError extends SimeError {
  constructor(message = "Portalul este în mentenanță", waitSeconds = 900) {
    super(message);
    this.waitSeconds = waitSeconds;
  }
}
class SimeForbiddenError extends SimeError {}
class SimeNotFoundError extends SimeError {}
class SimeUnexpectedPageError extends SimeError {
  constructor(message, step = null) {
    super(message);
    this.step = step;
  }
}
class SimeParseError extends SimeError {}
class SimeStudentNotFoundError extends SimeError {}
// The portal answered with a different student than the one requested; never serve that page
class SimeStudentMismatchError extends SimeStudentNotFoundError {}

// endpoints

const HOST = "agenda.sime.md";
const BASE_URL = `https://${HOST}`;
const PUBLIC_PORTAL_URL = `${BASE_URL}/ords/r/agenda/main/public`;
const ajaxUrl = (sessionId) => `${BASE_URL}/ords/wwv_flow.ajax?p_context=main/main-page/${sessionId}`;
const studentSwitchUrl = (sessionId, studentId, checksum = null) => {
  if (!checksum) {
    throw new SimeStudentNotFoundError("Cannot build student switch URL without APEX checksum");
  }
  return resolveSimeUrl(
    `f?p=AGENDA:1:${encodeURIComponent(sessionId)}::NO::P1_ELEVE_ID:${encodeURIComponent(studentId)}&cs=${encodeURIComponent(checksum)}`,
    `${BASE_URL}/ords/`,
  );
};

function formatSwitchUrl(rawHref, sessionId) {
  if (!rawHref) return null;
  let url = rawHref.replace(/(f\?p=[^:]+:[^:]+:)([^:]*)/, `$1${sessionId}`);
  url = url.replace(/([?&;:]session=)\d+/gi, `$1${sessionId}`);
  return resolveSimeUrl(url);
}

const FLOW_ID = "104";
const FLOW_STEP_ID = "1";
const ITEM_STUDENT_ID = "P1_ELEVE_ID";
const ITEM_LAST_EVALS = "P1_LAST_3_EVAL_LIST";
const ITEM_HOMEWORK = "P1_HOMEWORK";
const REGIONS = [
  { semester: 1, id: "sem1", selectors: ["#sem1", "#SEMESTER_REG_1", "#R_SEM1", "[id='sem1' i]", "[id*='semester_reg_1' i]", "[id*='sem_1' i]"] /* UNVERIFIED */ },
  { semester: 2, id: "sem2", selectors: ["#sem2", "#SEMESTER_REG_2", "#R_SEM2", "[id='sem2' i]", "[id*='semester_reg_2' i]", "[id*='sem_2' i]"] /* UNVERIFIED */ },
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
  if (typeof el.text === "function") return el.text().trim();
  const node = el.type ? el : (el[0] || el);
  if (!node) return "";
  if (typeof node.text === "function") return node.text().trim();
  const parts = [];
  const walk = (n) => {
    if (!n) return;
    const kids = Array.isArray(n.children) ? n.children : (Array.isArray(n.childNodes) ? n.childNodes : []);
    for (const child of kids) {
      if (child.type === "text") parts.push(child.data);
      else if (child.type === "tag") walk(child);
    }
  };
  walk(node);
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

const studentKey = (id) => (typeof id === "string" || typeof id === "number" ? String(id).trim().toLowerCase() : "");
const normalizeName = (name) => (typeof name === "string" ? name.trim().toLowerCase().replace(/\s+/g, " ") : "");

function mergeStudents(baseStudent, links = []) {
  const baseKey = studentKey(baseStudent?.id);
  const baseName = normalizeName(baseStudent?.fullName);
  const students = [];
  const linkMap = new Map();

  const findExistingIndex = (id, name) => {
    const k = studentKey(id);
    const n = normalizeName(name);
    return students.findIndex((s) => (k && studentKey(s.id) === k) || (n && normalizeName(s.fullName) === n));
  };

  for (const link of links) {
    const matchesBase =
      (baseKey && studentKey(link.studentId) === baseKey) ||
      (baseName && normalizeName(link.name) === baseName) ||
      (Boolean(link.isCurrent) && (baseKey || baseName));
    const canonicalId = matchesBase && baseStudent?.id ? baseStudent.id : link.studentId;
    const displayName = (matchesBase && baseStudent?.fullName) || link.name || canonicalId;
    const className = (matchesBase && baseStudent?.className) || null;
    const schoolName = (matchesBase && baseStudent?.schoolName) || null;

    if (link.href) {
      linkMap.set(studentKey(canonicalId), { href: link.href, displayName });
      if (link.studentId) {
        linkMap.set(studentKey(link.studentId), { href: link.href, displayName });
      }
    }

    const existingIdx = findExistingIndex(canonicalId, displayName);
    if (existingIdx !== -1) {
      const ex = students[existingIdx];
      students[existingIdx] = {
        id: ex.id || canonicalId,
        fullName: ex.fullName || displayName,
        className: ex.className || className,
        schoolName: ex.schoolName || schoolName,
      };
    } else {
      students.push({
        id: canonicalId,
        fullName: displayName,
        className,
        schoolName,
      });
    }
  }

  if (baseStudent && (baseStudent.id || baseStudent.fullName)) {
    const existingIdx = findExistingIndex(baseStudent.id, baseStudent.fullName);
    if (existingIdx !== -1) {
      const ex = students[existingIdx];
      students[existingIdx] = {
        id: baseStudent.id || ex.id,
        fullName: baseStudent.fullName || ex.fullName,
        className: baseStudent.className || ex.className,
        schoolName: baseStudent.schoolName || ex.schoolName,
      };
    } else {
      students.unshift({
        id: baseStudent.id,
        fullName: baseStudent.fullName,
        className: baseStudent.className || null,
        schoolName: baseStudent.schoolName || null,
      });
    }
  }

  return { students, linkMap };
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
    let rawGrade;
    if (dateEl && subjectEl && gradeEl) {
      dateText = textOf(dateEl);
      subject = textOf(subjectEl).replace(/:+$/, "").trim();
      rawGrade = textOf(gradeEl);
    } else {
      // Fallback: date, subject and trailing mark in plain text
      const m = /^\s*(\S+)\s+(.+?)[:\s]+(f[oó]arte\s+bine|\d+(?:[.,]\d+)?|[A-Za-z.]+)\s*$/i.exec(textOf(li, " "));
      if (!m) return;
      dateText = m[1];
      subject = m[2].trim();
      rawGrade = m[3];
    }
    if (!rawGrade || !subject) return;
    const day = parseDate(dateText, today);
    const sem = inferSemester(day, defaultSemester);
    const markObj = normalizeMark(rawGrade, null, false, sem);
    if (!markObj) return;
    markObj.date = day;
    grades.push({
      subject,
      ...markObj,
    });
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

// UNVERIFIED: Portal spelling variations for qualifiers.
// "Foarte bine" reported by maintainer; exact casing/diacritics beyond that are unverified.
const QUALIFIERS = {
  fb: "FB",
  "f.b.": "FB",
  "f.b": "FB",
  "foarte bine": "FB",   /* UNVERIFIED */
  b: "B",
  bine: "B",             /* UNVERIFIED */
  s: "S",
  suficient: "S",        /* UNVERIFIED */
  i: "I",
  insuficient: "I",      /* UNVERIFIED */
};

function normalizeQualifierKey(str) {
  if (!str) return "";
  return String(str)
    .trim()
    .toLowerCase()
    .replace(/[\u015F\u0219]/g, "s")
    .replace(/[\u0163\u021B]/g, "t")
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/\s+/g, " ");
}

function splitMarkTokens(rawText) {
  if (!rawText) return [];
  const text = String(rawText).trim();
  if (!text) return [];
  // Match "foarte bine" (with arbitrary whitespace/casing/accents) before splitting individual tokens
  const matches = text.match(/f[oó]arte\s+bine|[^\s,]+/gi);
  return matches ? matches.map((t) => t.trim()).filter(Boolean) : [];
}

// UNVERIFIED: Subject scale lookup based on maintainer description.
// Portal captures needed to confirm exact naming variations.
const SUBJECT_SCALE_REGISTRY = [
  { stem: "educatie fizica", scale: "qualifier" },
  { stem: "educatie muzicala", scale: "qualifier" },
  { stem: "educatie plastica", scale: "qualifier" },
  { stem: "educatie tehnologica", scale: "qualifier" },
  { stem: "educatie pentru societate", scale: "descriptor" },
];

function normalizeSubjectName(raw) {
  if (!raw) return "";
  let s = String(raw).toLowerCase().trim();
  s = s.replace(/[\u015F\u0219]/g, "s").replace(/[\u0163\u021B]/g, "t");
  s = s.normalize("NFD").replace(/[\u0300-\u036f]/g, "");
  s = s.replace(/\beducatia\b/g, "educatie");
  s = s.replace(/\s+/g, " ").trim();
  return s;
}

function getSubjectScale(subjectName) {
  const norm = normalizeSubjectName(subjectName);
  if (!norm) return null;
  for (const entry of SUBJECT_SCALE_REGISTRY) {
    if (norm.includes(entry.stem)) {
      return entry.scale;
    }
  }
  return null;
}

function normalizeMark(rawText, rawAttr = null, isThesisExplicit = false, semester = null) {
  if (!rawText && rawText !== 0) return null;
  const raw = String(rawText).trim();
  if (!raw || raw === "-" || raw === "–" || raw === "—") return null;

  const date = parseFullDate(rawAttr);
  const isThesis = Boolean(
    isThesisExplicit ||
    (rawAttr && /tez[aă]/i.test(rawAttr))
  );

  const qualKey = normalizeQualifierKey(raw);
  if (QUALIFIERS[qualKey]) {
    return {
      kind: "qualifier",
      value: null,
      label: QUALIFIERS[qualKey],
      raw,
      isThesis,
      date,
      semester,
    };
  }

  const numVal = toFloat(raw);
  if (numVal !== null && numVal >= 0 && numVal <= 10) {
    return {
      kind: "numeric",
      value: numVal,
      label: String(numVal),
      raw,
      isThesis,
      date,
      semester,
    };
  }

  return {
    kind: "other",
    value: null,
    label: raw,
    raw,
    isThesis,
    date,
    semester,
  };
}

// Marks are read from leaf elements when the cell is structured (date taken from a title-like
// attribute when present); otherwise from numeric or qualifier tokens in the cell text.
function parseMarkCell($, cell, semester = null) {
  const marks = [];
  const cellNode = cell?.type ? cell : (cell?.[0] || cell);
  const leaves = $(cellNode).find("*").toArray().filter((e) => $(e).children().length === 0);
  for (const leaf of leaves) {
    const text = textOf(leaf);
    const rawAttr = ["title", "data-date", "data-title", "aria-label"].map((k) => leaf.attribs[k]).find(Boolean) || leaf.parent?.attribs?.title;
    const isThesis = Boolean(
      (leaf.attribs?.class && /tez[aă]/i.test(leaf.attribs.class)) ||
      (rawAttr && /tez[aă]/i.test(rawAttr)) ||
      (leaf.parent?.attribs?.class && /tez[aă]/i.test(leaf.parent.attribs.class))
    );
    const tokens = splitMarkTokens(text);
    if (tokens.length > 1) {
      for (const tok of tokens) {
        const m = normalizeMark(tok, rawAttr, isThesis, semester);
        if (m) marks.push(m);
      }
    } else {
      const m = normalizeMark(text, rawAttr, isThesis, semester);
      if (m) {
        marks.push(m);
      }
    }
  }
  if (marks.length) return marks;
  for (const token of splitMarkTokens(textOf(cellNode, " "))) {
    const m = normalizeMark(token, null, false, semester);
    if (m) {
      marks.push(m);
    }
  }
  return marks;
}

function parseSemesterReportFromRoot($, root) {
  const result = new Map();
  root.find("tr").each((_i, tr) => {
    const tds = $(tr).find("td").toArray();
    if (!tds.length) return;
    const disc = tds.find((td) => headerHas($, td, ["DISCIPLINA", "DISCIPLINE" /* UNVERIFIED */, "MATERIA" /* UNVERIFIED */, "OBIECT" /* UNVERIFIED */]));
    if (!disc) return;
    const subject = textOf(disc);
    if (!subject) return;
    const evals = tds.find((td) => headerHas($, td, ["LISTA_EVALUATION", "EVALUATION" /* UNVERIFIED */, "NOTE" /* UNVERIFIED */, "MARKS" /* UNVERIFIED */]));
    const tezaTd = tds.find((td) => headerHas($, td, ["TEZA" /* UNVERIFIED */, "NOTA_TEZA" /* UNVERIFIED */, "THESIS" /* UNVERIFIED */]));
    const avg = tds.find((td) => headerHas($, td, ["MED_SEM" /* UNVERIFIED */, "MEDIA_SEM" /* UNVERIFIED */, "MEDIA" /* UNVERIFIED */]));
    const teacherTd = tds.find((td) => headerHas($, td, ["PROFESOR", "CADRU" /* UNVERIFIED */, "TEACHER" /* UNVERIFIED */]));
    const parsedMarks = evals ? parseMarkCell($, evals) : [];
    if (tezaTd) {
      const tezaMarks = parseMarkCell($, tezaTd);
      for (const tm of tezaMarks) tm.isThesis = true;
      parsedMarks.push(...tezaMarks);
    }
    result.set(subject, {
      subject,
      marks: parsedMarks,
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

    const annualTd = tds.find((td) => headerHas($, td, ["MED_AN" /* UNVERIFIED */, "MEDIA_AN" /* UNVERIFIED */, "MEDIA_GEN" /* UNVERIFIED */, "ANUAL" /* UNVERIFIED */, "ANNUAL" /* UNVERIFIED */]));
    if (annualTd) {
      const val = toFloat(textOf(annualTd));
      if (val !== null) annualMap.set(subject, val);
    }

    const evals1 = tds.find((td) => headerHas($, td, ["LISTA_EVALUATION_1" /* UNVERIFIED */, "EVAL_SEM1" /* UNVERIFIED */, "NOTE_SEM1" /* UNVERIFIED */, "SEM1_NOTE" /* UNVERIFIED */]));
    const teza1 = tds.find((td) => headerHas($, td, ["TEZA_SEM1" /* UNVERIFIED */, "TEZA1" /* UNVERIFIED */, "NOTA_TEZA1" /* UNVERIFIED */]));
    const evals2 = tds.find((td) => headerHas($, td, ["LISTA_EVALUATION_2" /* UNVERIFIED */, "EVAL_SEM2" /* UNVERIFIED */, "NOTE_SEM2" /* UNVERIFIED */, "SEM2_NOTE" /* UNVERIFIED */]));
    const teza2 = tds.find((td) => headerHas($, td, ["TEZA_SEM2" /* UNVERIFIED */, "TEZA2" /* UNVERIFIED */, "NOTA_TEZA2" /* UNVERIFIED */]));
    const avg1 = tds.find((td) => headerHas($, td, ["MED_SEM1" /* UNVERIFIED */, "MED_SEM_1" /* UNVERIFIED */, "MEDIA_SEM1" /* UNVERIFIED */, "SEM1_MED" /* UNVERIFIED */]));
    const avg2 = tds.find((td) => headerHas($, td, ["MED_SEM2" /* UNVERIFIED */, "MED_SEM_2" /* UNVERIFIED */, "MEDIA_SEM2" /* UNVERIFIED */, "SEM2_MED" /* UNVERIFIED */]));

    if (evals1 || avg1 || teza1) {
      const m1 = evals1 ? parseMarkCell($, evals1) : [];
      if (teza1) {
        const tm1 = parseMarkCell($, teza1);
        for (const m of tm1) m.isThesis = true;
        m1.push(...tm1);
      }
      sem1.set(subject, {
        subject,
        marks: m1,
        semesterAverage: avg1 ? toFloat(textOf(avg1)) : null,
        teacher,
      });
    }
    if (evals2 || avg2 || teza2) {
      const m2 = evals2 ? parseMarkCell($, evals2) : [];
      if (teza2) {
        const tm2 = parseMarkCell($, teza2);
        for (const m of tm2) m.isThesis = true;
        m2.push(...tm2);
      }
      sem2.set(subject, {
        subject,
        marks: m2,
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
      const values = entry.marks
        .filter((m) => m.kind === "numeric" && typeof m.value === "number")
        .map((m) => m.value);
      const existing = s.semesters.find((x) => x.semester === semester);
      if (existing) {
        if (!existing.marks.length && entry.marks.length) existing.marks = entry.marks;
        const existVals = existing.marks
          .filter((m) => m.kind === "numeric" && typeof m.value === "number")
          .map((m) => m.value);
        existing.average = existVals.length ? mean(existVals) : null;
        if (existing.officialAverage === null && entry.semesterAverage !== null) {
          existing.officialAverage = entry.semesterAverage;
        }
      } else {
        s.semesters.push({
          semester,
          marks: entry.marks,
          average: values.length ? mean(values) : null,
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
    const markDetails = s.semesters.flatMap((x) =>
      x.marks.map((m) => ({
        kind: m.kind || (typeof m.value === "number" ? "numeric" : "qualifier"),
        value: m.value ?? null,
        label: m.label || (m.value != null ? String(m.value) : m.raw || ""),
        raw: m.raw || String(m.value ?? m.label ?? ""),
        isThesis: Boolean(m.isThesis),
        date: m.date ?? null,
        semester: x.semester,
      }))
    );
    const numericMarks = markDetails
      .filter((m) => m.kind === "numeric" && typeof m.value === "number")
      .map((m) => m.value);
    const marks = numericMarks.length ? numericMarks : markDetails.map((m) => m.label || m.value);
    const official = s.semesters.map((x) => x.officialAverage).filter((v) => v !== null);
    const computedAverage = numericMarks.length ? mean(numericMarks) : (official.length ? mean(official) : null);
    const explicitAnnual = annualMap instanceof Map ? (annualMap.get(s.subject) ?? null) : null;
    const annualAverage =
      explicitAnnual ?? (sem1Average !== null && sem2Average !== null ? mean([sem1Average, sem2Average]) : computedAverage);
    const average = computedAverage ?? annualAverage;
    const scale = getSubjectScale(s.subject);
    return {
      ...s,
      discipline: s.subject,
      scale,
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
  const sem1Excused = asInt("P1_MOTIVATED", 0);
  const sem1Unexcused = asInt("P1_UNMOTIVATED", 0);
  const sem2Excused = asInt("P1_MOTIVATED2", 0);
  const sem2Unexcused = asInt("P1_UNMOTIVATED2", 0);
  const sem1Total = asInt("P1_TOTAL_ABSENCES1", sem1Excused + sem1Unexcused);
  const sem2Total = asInt("P1_TOTAL_ABSENCES2", sem2Excused + sem2Unexcused);
  const summary = {
    presencePercent,
    sem1Excused,
    sem1Unexcused,
    sem1Total,
    sem2Excused,
    sem2Unexcused,
    sem2Total,
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
    let marker = ["className", "classNames", "class", "status", "absenceStatus"]
      .flatMap((key) => asList(ev[key]).map(String))
      .join(" ")
      .toLowerCase();
    const absenceEl = classContains($, root[0], "absen" /* UNVERIFIED */, "nemotivat" /* UNVERIFIED */, "motivat" /* UNVERIFIED */, "unmotivat" /* UNVERIFIED */);
    if (absenceEl) {
      marker += ` ${classTokens(absenceEl).join(" ").toLowerCase()}`;
      if (absenceEl.attribs?.status) marker += ` ${absenceEl.attribs.status.toLowerCase()}`;
      if (absenceEl.attribs?.["data-status"]) marker += ` ${absenceEl.attribs["data-status"].toLowerCase()}`;
    }
    const isUnexcused = UNEXCUSED_MARKERS.some((k) => marker.includes(k));
    const isExcused = !isUnexcused && EXCUSED_MARKERS.some((k) => marker.includes(k));
    const isAbsence = marker.includes("absen" /* UNVERIFIED */) || isUnexcused || isExcused;
    const explicitSem = ev.semester === 1 || ev.semester === 2 ? ev.semester : null;
    entries.push({
      start,
      end,
      subject,
      classroom: textFor("lesson-room" /* UNVERIFIED */),
      teacher: textFor("lesson-teacher" /* UNVERIFIED */),
      topic: textFor("lesson-homework" /* UNVERIFIED */, "lesson-line3" /* UNVERIFIED */),
      isAbsence,
      isExcused,
      isUnexcused,
      semester: explicitSem,
      note: textFor("absence-reason" /* UNVERIFIED */, "reason" /* UNVERIFIED */, "note" /* UNVERIFIED */) || absenceEl?.attribs?.title || null,
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

function absenceRecords(entries, studentKey = null) {
  const seen = new Set();
  const records = [];
  for (const e of entries.filter((x) => x.isAbsence)) {
    const key = `${e.start}|${e.subject}`;
    if (seen.has(key)) continue;
    seen.add(key);
    const date = chisinauDate(e.start);
    const startHm = chisinauHm(e.start);
    const endHm = chisinauHm(e.end);
    const status = e.isExcused ? "excused" : e.isUnexcused ? "unexcused" : "unknown";
    records.push({
      studentKey: studentKey ? String(studentKey) : null,
      date,
      lessonNumber: e.periodIndex || e.lessonNumber || null,
      startTime: startHm,
      endTime: endHm,
      subject: e.subject || "Disciplină",
      room: e.classroom || e.room || null,
      teacher: e.teacher || null,
      status,
      reason: e.note || e.reason || null,
      semester: inferAbsenceSemester(date, e.semester),
      // Backward compatibility aliases
      startLocal: startHm,
      endLocal: endHm,
      discipline: e.subject || "Disciplină",
      note: e.note || e.reason || null,
      rawStatus: e.isExcused ? "motivat" : e.isUnexcused ? "nemotivat" : "necunoscut",
    });
  }
  return records.sort((a, b) => b.date.localeCompare(a.date) || (b.startTime || b.startLocal || "").localeCompare(a.startTime || a.startLocal || ""));
}

function extractHtmlAbsenceRecords($, today = todayChisinau(), studentKey = null) {
  const records = [];
  $("tr").each((_i, tr) => {
    const tds = $(tr).find("td").toArray();
    if (!tds.length) return;
    const statusTd = tds.find((td) => headerHas($, td, ["STATUT" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */, "MOTIVAT" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */, "TIP_ABS" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */, "ABSENT" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */]));
    const dateTd = tds.find((td) => headerHas($, td, ["DATA" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */, "DATE" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */, "DATA_ABS" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */]));
    const discTd = tds.find((td) => headerHas($, td, ["DISCIPLINA" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */, "DISCIPLINE" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */, "MATERIA" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */]));
    if (!statusTd || !dateTd || !discTd) return;
    const date = parseDate(textOf(dateTd), today);
    const subject = textOf(discTd);
    if (!date || !subject) return;
    const rawStatus = textOf(statusTd).toLowerCase();
    const isUnexcused = UNEXCUSED_MARKERS.some((k) => rawStatus.includes(k));
    const isExcused = !isUnexcused && EXCUSED_MARKERS.some((k) => rawStatus.includes(k));
    const status = isExcused ? "excused" : isUnexcused ? "unexcused" : "unknown";
    const timeTd = tds.find((td) => headerHas($, td, ["ORA" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */, "TIME" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */, "LECTIA" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */]));
    const roomTd = tds.find((td) => headerHas($, td, ["SALA" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */, "CABINET" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */, "ROOM" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */]));
    const teacherTd = tds.find((td) => headerHas($, td, ["PROFESOR" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */, "CADRU" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */, "TEACHER" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */]));
    const noteTd = tds.find((td) => headerHas($, td, ["MOTIV" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */, "NOTE" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */, "OBSERVATII" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */]));
    const semTd = tds.find((td) => headerHas($, td, ["SEMESTRU" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */, "SEM" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */]));
    const explicitSem = semTd ? parseInt(textOf(semTd), 10) : null;
    const rawTime = timeTd ? textOf(timeTd) : "";
    const lessonNum = rawTime && /^\d+$/.test(rawTime) ? parseInt(rawTime, 10) : null;
    records.push({
      studentKey: studentKey ? String(studentKey) : null,
      date,
      lessonNumber: lessonNum,
      startTime: lessonNum ? "" : rawTime,
      endTime: "",
      subject,
      room: roomTd ? textOf(roomTd) || null : null,
      teacher: teacherTd ? textOf(teacherTd) || null : null,
      status,
      reason: noteTd ? textOf(noteTd) || null : null,
      semester: inferAbsenceSemester(date, explicitSem === 1 || explicitSem === 2 ? explicitSem : null),
      startLocal: rawTime,
      endLocal: "",
      discipline: subject,
      note: noteTd ? textOf(noteTd) || null : null,
      rawStatus: isExcused ? "motivat" : isUnexcused ? "nemotivat" : "necunoscut",
    });
  });
  return records;
}

function extractEmbeddedAbsenceRecords($, today = todayChisinau(), studentKey = null) {
  const raw = getInputValue($, "P1_JSON_ABSENCES");
  if (!raw) return [];
  try {
    const list = JSON.parse(raw);
    if (!Array.isArray(list)) return [];
    const out = [];
    for (const item of list) {
      if (!item || typeof item !== "object") continue;
      const date = parseDate(item.lessonDate, today);
      const subject = String(item.discipliny || item.discipline || item.subject || "Disciplină").trim();
      if (!date || !subject) continue;
      const rawStatus = String(item.absenceName || "").toLowerCase();
      const isUnexcused = UNEXCUSED_MARKERS.some((k) => rawStatus.includes(k));
      const isExcused = !isUnexcused && EXCUSED_MARKERS.some((k) => rawStatus.includes(k));
      const status = isExcused ? "excused" : isUnexcused ? "unexcused" : "unknown";
      const rawStatusStr = isExcused ? "motivat" : isUnexcused ? "nemotivat" : "necunoscut";
      const lessonNumber =
        typeof item.lessonOrderId === "number"
          ? item.lessonOrderId
          : /^\d+$/.test(String(item.lessonOrderId || "").trim())
          ? parseInt(String(item.lessonOrderId).trim(), 10)
          : null;
      out.push({
        studentKey: studentKey ? String(studentKey) : null,
        date,
        lessonNumber,
        startTime: "",
        endTime: "",
        subject,
        room: null,
        teacher: null,
        status,
        reason: null,
        semester: inferAbsenceSemester(date),
        startLocal: "",
        endLocal: "",
        discipline: subject,
        note: null,
        rawStatus: rawStatusStr,
      });
    }
    return out;
  } catch {
    return [];
  }
}

// Categorizes absences strictly into Semestrul 1 and Semestrul 2 with totals, motivate, nemotivate, and lesson records
function buildSemesterAbsences(summary, rawRecords = [], studentKey = null) {
  const effectiveRecords = Array.isArray(rawRecords) ? [...rawRecords] : [];
  const seen = new Set();
  const deduped = [];
  for (const r of effectiveRecords) {
    if (!r) continue;
    const key = `${r.date || ""}|${r.lessonNumber || ""}|${r.startTime || r.startLocal || ""}|${r.subject || r.discipline || ""}`;
    if (seen.has(key)) continue;
    seen.add(key);
    const sem = inferAbsenceSemester(r.date, r.semester);
    const subj = r.subject || r.discipline || "Disciplina";
    const status = r.status || (r.rawStatus ? (r.rawStatus === "motivat" ? "excused" : "unexcused") : "unknown");
    deduped.push({
      studentKey: r.studentKey || (studentKey ? String(studentKey) : null),
      date: r.date || null,
      lessonNumber: r.lessonNumber || null,
      startTime: r.startTime || r.startLocal || "",
      endTime: r.endTime || r.endLocal || "",
      subject: subj,
      room: r.room || null,
      teacher: r.teacher || null,
      status,
      reason: r.reason || r.note || null,
      semester: sem,
      startLocal: r.startTime || r.startLocal || "",
      endLocal: r.endTime || r.endLocal || "",
      discipline: r.discipline || subj,
      note: r.reason || r.note || null,
      rawStatus: r.rawStatus || (status === "excused" ? "motivat" : status === "unexcused" ? "nemotivat" : "necunoscut"),
    });
  }
  deduped.sort((a, b) => (b.date || "").localeCompare(a.date || "") || (a.lessonNumber || 0) - (b.lessonNumber || 0) || (b.startTime || b.startLocal || "").localeCompare(a.startTime || a.startLocal || ""));

  const sem1Records = deduped.filter((r) => r.semester === 1);
  const sem2Records = deduped.filter((r) => r.semester === 2);

  const sem1Motivate = summary?.sem1Excused ?? 0;
  const sem1Nemotivate = summary?.sem1Unexcused ?? 0;
  const sem1Total = summary?.sem1Total ?? (sem1Motivate + sem1Nemotivate);

  const sem2Motivate = summary?.sem2Excused ?? 0;
  const sem2Nemotivate = summary?.sem2Unexcused ?? 0;
  const sem2Total = summary?.sem2Total ?? (sem2Motivate + sem2Nemotivate);

  // Gate: Per-lesson details are shown for a semester only if the number of detected records
  // equals the portal's reported total for that semester. If they differ, return no detailed records
  // for that semester (records: []).
  const sem1DetailsValid = sem1Total > 0 && sem1Records.length === sem1Total;
  const effectiveSem1Records = sem1DetailsValid ? sem1Records : [];

  const sem2DetailsValid = sem2Total > 0 && sem2Records.length === sem2Total;
  const effectiveSem2Records = sem2DetailsValid ? sem2Records : [];

  const finalRecords = [...effectiveSem1Records, ...effectiveSem2Records];

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
    records: finalRecords,
    sem1: {
      semester: 1,
      total: sem1Total,
      motivate: sem1Motivate,
      nemotivate: sem1Nemotivate,
      records: effectiveSem1Records,
    },
    sem2: {
      semester: 2,
      total: sem2Total,
      motivate: sem2Motivate,
      nemotivate: sem2Nemotivate,
      records: effectiveSem2Records,
    },
  };
}

function parseTeacherTokens(name) {
  if (!name) return { fullTokens: [], initials: [] };
  const cleaned = name
    .replace(/\b(prof|profesor|d-na|dna|dl|invatator|învățător)\.?\b/gi, "")
    .replace(/[.,]/g, " ")
    .trim();
  const parts = cleaned.split(/\s+/).filter(Boolean);
  const fullTokens = [];
  const initials = [];
  for (const p of parts) {
    const lower = p.toLowerCase();
    if (lower.length === 1) initials.push(lower);
    else fullTokens.push(lower);
  }
  return { parts, fullTokens, initials };
}

function areTeacherNamesSimilar(nameA, nameB) {
  if (!nameA || !nameB) return false;
  if (nameA.trim().toLowerCase() === nameB.trim().toLowerCase()) return true;

  const a = parseTeacherTokens(nameA);
  const b = parseTeacherTokens(nameB);

  if (!a.fullTokens.length || !b.fullTokens.length) return false;

  const sharedFull = a.fullTokens.filter((tok) => b.fullTokens.includes(tok));
  if (sharedFull.length === 0) return false;

  const remA = a.fullTokens.filter((tok) => !sharedFull.includes(tok));
  const remB = b.fullTokens.filter((tok) => !sharedFull.includes(tok));

  if (remA.length && remB.length) {
    return remA.every((tok) => remB.includes(tok));
  }

  if (remA.length && b.initials.length) {
    return b.initials.some((init) => remA.some((full) => full.startsWith(init)));
  }
  if (remB.length && a.initials.length) {
    return a.initials.some((init) => remB.some((full) => full.startsWith(init)));
  }

  if (!remA.length && !remB.length && !a.initials.length && !b.initials.length) {
    return true;
  }

  if ((a.initials.length && !b.initials.length && !remB.length) || (b.initials.length && !a.initials.length && !remA.length)) {
    return true;
  }

  return false;
}

function buildTeachers(entries, metadata = null, catalogSubjects = []) {
  const teachersList = [];

  const findTeacher = (name) => {
    return teachersList.find((t) => areTeacherNamesSimilar(t.name, name));
  };

  const ensure = (name) => {
    if (!name || typeof name !== "string") return null;
    const cleanName = name.trim();
    if (!cleanName) return null;
    let existing = findTeacher(cleanName);
    if (!existing) {
      existing = { name: cleanName, subjects: new Set(), lessons: 0, note: null };
      teachersList.push(existing);
    } else {
      if (cleanName.length > existing.name.length && !cleanName.includes(".")) {
        existing.name = cleanName;
      }
    }
    return existing;
  };

  if (metadata?.classMaster) {
    const cm = ensure(metadata.classMaster);
    if (cm) cm.note = "Diriginte";
  }

  for (const s of catalogSubjects) {
    if (!s?.teacher) continue;
    const t = ensure(s.teacher);
    if (t && s.subject) t.subjects.add(s.subject);
  }

  for (const e of entries) {
    if (!e.teacher) continue;
    const t = ensure(e.teacher);
    if (t) {
      if (e.subject) t.subjects.add(e.subject);
      t.lessons += 1;
    }
  }

  return teachersList
    .map((t) => ({
      name: t.name,
      subjects: [...t.subjects].sort(),
      lessons: t.lessons,
      note: t.note,
    }))
    .sort((a, b) => {
      if (a.note === "Diriginte" && b.note !== "Diriginte") return -1;
      if (b.note === "Diriginte" && a.note !== "Diriginte") return 1;
      return a.name.localeCompare(b.name);
    });
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
  const { students } = mergeStudents(baseStudent, links);
  const baseKey = studentKey(baseStudent.id);
  const baseName = normalizeName(baseStudent.fullName);
  const student =
    students.find((s) => (baseKey && studentKey(s.id) === baseKey) || (baseName && normalizeName(s.fullName) === baseName)) ||
    students.find((_s, idx) => links[idx]?.isCurrent) ||
    students[0] ||
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
  const embeddedAbsences = extractEmbeddedAbsenceRecords($, today, student ? student.id : null);
  const rawRecords = embeddedAbsences.length
    ? embeddedAbsences
    : [...absenceRecords(calendarEntries), ...extractHtmlAbsenceRecords($, today)];
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

// Parser health diagnostics: returns which selectors matched, which fields were found,
// and counts per section. Never leaks personal names or sensitive data.
function inspectParserHealth(html) {
  const $ = cheerio.load(html || "");
  const out = {};

  // students
  const studentSelectors = ["#P1_ELEVE_ID", "#P1_ELEVE", "#P1_CLASS_NAME", "#P1_INSTITUTION_NAME", "#t_TreeNav", "select#P1_ELEVE_ID"];
  const matchedStudentSel = studentSelectors.filter((s) => $(s).length > 0);
  const links = parseStudentLinks($);
  const profile = parseStudentProfile($);
  const studentFields = [];
  if (profile.id) studentFields.push("id");
  if (profile.fullName) studentFields.push("fullName");
  if (profile.className) studentFields.push("className");
  if (profile.schoolName) studentFields.push("schoolName");
  out.students = {
    count: Math.max(links.length, profile.id || profile.fullName ? 1 : 0),
    selectorsMatched: matchedStudentSel,
    fieldsFound: studentFields,
  };

  // grades
  const gradeSelectors = ["#P1_LAST_3_EVAL_LIST", "input#P1_LAST_3_EVAL_LIST", ".eval-date", ".eval-discipline", ".grade"];
  const matchedGradeSel = gradeSelectors.filter((s) => $(s).length > 0);
  const evalVal = getInputValue($, ITEM_LAST_EVALS);
  const evalRoot = evalVal ? $(`<div>${evalVal}</div>`) : $("#P1_LAST_3_EVAL_LIST");
  const grades = evalRoot.length ? parseRecentGradesFromRoot($, evalRoot) : [];
  const gradeFields = new Set();
  for (const g of grades) {
    if (g.subject) gradeFields.add("subject");
    if (g.value != null || g.raw != null) gradeFields.add("value");
    if (g.date) gradeFields.add("date");
    if (g.semester != null) gradeFields.add("semester");
  }
  out.grades = {
    count: grades.length,
    selectorsMatched: matchedGradeSel,
    fieldsFound: [...gradeFields],
  };

  // catalog
  const catalogSelectors = ["#SEMESTER_REG_1", "#SEMESTER_REG_2", ".t-Report-report", "td[headers*='DISCIPLINA' i]", "td[headers*='MED_SEM' i]"];
  const matchedCatSel = catalogSelectors.filter((s) => $(s).length > 0);
  const catReports = [];
  for (const region of REGIONS) {
    const scope = findSemesterScope($, region);
    if (scope.length) {
      const rep = parseSemesterReportFromRoot($, scope);
      if (rep.size) catReports.push({ semester: region.semester, report: rep });
    }
  }
  const catalog = buildCatalog(catReports);
  const catFields = new Set();
  for (const subj of catalog.subjects) {
    if (subj.subject) catFields.add("subject");
    if (subj.marks?.length) catFields.add("marks");
    if (subj.average != null || subj.sem1Average != null) catFields.add("semesterAverage");
    if (subj.teacher) catFields.add("teacher");
  }
  out.catalog = {
    count: catalog.subjects.length,
    selectorsMatched: matchedCatSel,
    fieldsFound: [...catFields],
  };

  // absences summary
  const absSummarySelectors = ["#P1_MOTIVATED", "#P1_UNMOTIVATED", "#P1_MOTIVATED2", "#P1_UNMOTIVATED2", "#P1_TOTAL_ABSENCES1", "#P1_TOTAL_ABSENCES2", "#P1_JSON_ABSENCES", "#P1_PRESENCE_PERCENT", "#P1_PROC_PREZ"];
  const matchedAbsSel = absSummarySelectors.filter((s) => $(s).length > 0);
  const absSummary = parseAbsenceSummary($);
  const absFields = [];
  if (absSummary.presencePercent != null) absFields.push("presencePercent");
  if (absSummary.sem1Excused != null) absFields.push("sem1Excused");
  if (absSummary.sem1Unexcused != null) absFields.push("sem1Unexcused");
  if (absSummary.sem2Excused != null) absFields.push("sem2Excused");
  if (absSummary.sem2Unexcused != null) absFields.push("sem2Unexcused");
  if (absSummary.total != null) absFields.push("total");
  out.absencesSummary = {
    count: absSummary.total,
    selectorsMatched: matchedAbsSel,
    fieldsFound: absFields,
  };

  // calendar
  const calSelectors = ["#P1_CALENDAR_EVENTS", "script#P1_CALENDAR_EVENTS", "[data-start][data-end]"];
  const matchedCalSel = calSelectors.filter((s) => $(s).length > 0);
  const calEntries = extractEmbeddedCalendarEntries($);
  const calFields = new Set();
  for (const c of calEntries) {
    if (c.start) calFields.add("start");
    if (c.end) calFields.add("end");
    if (c.subject) calFields.add("subject");
    if (c.classroom) calFields.add("classroom");
    if (c.teacher) calFields.add("teacher");
  }
  out.calendar = {
    count: calEntries.length,
    selectorsMatched: matchedCalSel,
    fieldsFound: [...calFields],
  };

  // teachers
  const teacherSelectors = ["#P1_DIRIGINTE", "input#P1_DIRIGINTE", "td[headers*='PROFESOR' i]", ".lesson-teacher"];
  const matchedTeacherSel = teacherSelectors.filter((s) => $(s).length > 0);
  const metadata = parseStudentMetadata($);
  const teachers = buildTeachers(calEntries, metadata, catalog.subjects);
  const teacherFields = new Set();
  for (const t of teachers) {
    if (t.name) teacherFields.add("name");
    if (t.subjects?.length) teacherFields.add("subjects");
    if (t.lessons != null) teacherFields.add("lessons");
    if (t.note) teacherFields.add("note");
  }
  out.teachers = {
    count: teachers.length,
    selectorsMatched: matchedTeacherSel,
    fieldsFound: [...teacherFields],
  };

  return out;
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

// One fixed, modern, browser-like profile per process
const BROWSER_PROFILE = Object.freeze(chromeProfile(131, "Linux", 'Not_A Brand";v="24'));

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

const globalBreaker = new CircuitBreaker();
const accountBreakers = new Map();

function breakerFor(accountKey) {
  if (!accountBreakers.has(accountKey)) {
    accountBreakers.set(accountKey, new CircuitBreaker());
  }
  return accountBreakers.get(accountKey);
}

const breaker = globalBreaker;
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
  constructor({ gate = new RateGate(), fingerprint = BROWSER_PROFILE, breaker: br = globalBreaker, globalBreaker: gBr = globalBreaker, cookies = new Map() } = {}) {
    this.gate = gate;
    this.fingerprint = fingerprint;
    this.breaker = br;
    this.globalBreaker = gBr;
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
      this.globalBreaker.ensureClosed();
      this.breaker.ensureClosed();
      await this.gate.wait();
    }
    for (let hop = 0; hop <= MAX_REDIRECTS; hop += 1) {
      this.globalBreaker.ensureClosed();
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
        if (err instanceof SimeMaintenanceError) this.globalBreaker.trip(err.waitSeconds);
        if (err instanceof SimeError) throw err;
        if (err?.name === "TimeoutError" || err?.name === "AbortError") throw new SimeNetworkError("Request timed out");
        throw new SimeNetworkError("Network error");
      }
    }
    throw new SimeNetworkError("Too many redirects");
  }

  raiseForStatus(res) {
    const s = res.status;
    if (s === 429) throw new SimeRateLimitError(`HTTP 429 de la agenda.sime.md`, retryAfter(res.headers));
    if (s === 503) throw new SimeMaintenanceError(`HTTP 503 de la agenda.sime.md`, retryAfter(res.headers));
    if (s === 403) throw new SimeForbiddenError(`HTTP 403 de la agenda.sime.md`);
    if (s === 404) throw new SimeNotFoundError(`HTTP 404 de la agenda.sime.md`);
    if (s === 401 || s === 440) throw new SimeSessionExpiredError(`HTTP ${s} de la agenda.sime.md`);
    if (s >= 400) throw new SimeNetworkError(`HTTP ${s} de la agenda.sime.md`);
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

function extractPageFlowInfo(input) {
  if (!input) return { flowId: FLOW_ID, flowStepId: FLOW_STEP_ID };
  try {
    const $ = typeof input === "function" ? input : cheerio.load(input);
    const flowId = $("#pFlowId").val() || $('input[name="p_flow_id"]').val() || FLOW_ID;
    const flowStepId = $("#pFlowStepId").val() || $('input[name="p_flow_step_id"]').val() || FLOW_STEP_ID;
    return {
      flowId: String(flowId || FLOW_ID).trim(),
      flowStepId: String(flowStepId || FLOW_STEP_ID).trim(),
    };
  } catch {
    return { flowId: FLOW_ID, flowStepId: FLOW_STEP_ID };
  }
}

class SimeClient {
  constructor(username, password, role, { http, auth } = {}) {
    const accKey = sha(username.toLowerCase());
    const br = breakerFor(accKey);
    this.http = http || new SimeHttp({ gate: gateFor(accKey), breaker: br, globalBreaker });
    this.auth = auth || new SimeAuth(username, password, role, br);
    this.studentUrls = new Map();
    this.knownStudents = new Map(); // canonical key -> { id, fullName, className, schoolName }
    this.portalIds = new Set();
    this.pages = new Map();
    // One stable key per child for the life of this client. Ids come in two formats (nav-tree id
    // and P1_ELEVE_ID of the page), so every alias is mapped to the first key we settled on.
    this.aliases = new Map();
    this.studentPages = new Map(); // canonical key -> that student's own dashboard page
    this.currentKey = ""; // student the portal (global per APEX session) is on, per the last fetched page
    this.reloginFlight = null;
  }

  resetSession() {
    this.auth.invalidate();
    this.studentUrls.clear();
    this.portalIds.clear();
    this.pages.clear();
    this.studentPages.clear();
    this.currentKey = "";
    this.http.clearCookies();
  }

  async relogin() {
    if (!this.reloginFlight) {
      this.reloginFlight = (async () => {
        try {
          this.resetSession();
          await this.executeHandshake(this.http.cookies);
        } finally {
          this.reloginFlight = null;
        }
      })();
    }
    return this.reloginFlight;
  }

  async executeHandshake(cookieJar = this.http.cookies) {
    this.resetSession();
    const res = await executeHandshake(cookieJar, { http: this.http, auth: this.auth });
    const $ = cheerio.load(res.html);
    if (!isDashboard($)) throw new SimeSessionExpiredError("Portal returned a non-dashboard page");
    const page = { url: this.auth.portalUrl, html: res.html, $, fetchedAt: Date.now() };
    this.pages.set(this.auth.portalUrl, page);
    this.noteFetched(page);
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
    // Invariant: the page we parse must be the requested student's own page
    const pageKey = page.studentKey || this.learnPage(page);
    if (requestedStudentId && this.canon(requestedStudentId) !== pageKey) {
      throw new SimeStudentMismatchError(`Portal page belongs to another student than ${requestedStudentId}`);
    }
    this.registerStudents(page);
    const own = this.knownStudents.get(pageKey);
    if (pageKey) {
      data.metadata.id = pageKey;
      data.student = {
        id: pageKey,
        fullName: data.metadata.fullName || own?.fullName || pageKey,
        className: data.metadata.className || own?.className || null,
        schoolName: data.metadata.schoolName || own?.schoolName || null,
      };
      data.studentKey = pageKey;
    }
    data.students = this.listStudents();
    return data;
  }

  // Canonical key for any alias of a student ("" when this account has no such student)
  canon(id) {
    return this.aliases.get(studentKey(id)) || "";
  }

  // Known students with canonical ids, in first-seen order, with the best class/school we have
  listStudents() {
    return [...this.knownStudents.values()].map((s) => ({ ...s }));
  }

  // Test and route seam: makes already-known students addressable without a portal page
  seedStudents(list) {
    for (const s of Array.isArray(list) ? list : []) {
      const k = studentKey(s?.id);
      if (!k) continue;
      if (!this.aliases.has(k)) this.aliases.set(k, k);
      this.mergeKnown(this.aliases.get(k), s);
    }
  }

  mergeKnown(key, s) {
    const prev = this.knownStudents.get(key);
    this.knownStudents.set(key, {
      id: key,
      fullName: s.fullName || prev?.fullName || key,
      className: s.className || prev?.className || null,
      schoolName: s.schoolName || prev?.schoolName || null,
    });
  }

  // Learns the ids on a dashboard page and returns the canonical key of the page's own student
  learnPage(page) {
    const profile = parseStudentProfile(page.$);
    const links = parseStudentLinks(page.$);
    const pageId = studentKey(profile.id);
    const name = normalizeName(profile.fullName);
    const cur = links.find((l) => l.isCurrent) || (name ? links.find((l) => normalizeName(l.name) === name) : undefined);
    const curNav = cur ? studentKey(cur.studentId) : "";
    const key = (pageId && this.aliases.get(pageId)) || (curNav && this.aliases.get(curNav)) || pageId || curNav;
    if (key) {
      for (const id of [pageId, curNav, key]) if (id && !this.aliases.has(id)) this.aliases.set(id, key);
    }
    for (const l of links) {
      const k = studentKey(l.studentId);
      if (k && !this.aliases.has(k)) this.aliases.set(k, k);
    }
    return key || "";
  }

  // A page really came from the portal: it is that student's page, and the portal is now on them
  noteFetched(page) {
    const key = this.learnPage(page);
    page.studentKey = key;
    if (key) {
      this.studentPages.set(key, page);
      this.currentKey = key;
    }
    this.registerStudents(page);
    return key;
  }

  // Authenticates and returns { html, data: parseFullPortal(html) }, or null when credentials are rejected
  async loginAndParsePortal() {
    this.resetSession();
    try {
      const res = await executeHandshake(this.http.cookies, { http: this.http, auth: this.auth });
      const $ = cheerio.load(res.html);
      const page = { url: this.auth.portalUrl, html: res.html, $, fetchedAt: Date.now() };
      this.pages.set(this.auth.portalUrl, page);
      this.noteFetched(page);
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

  // Single-flight re-login on expiry, retrying once silently
  async withRetry(operation) {
    try {
      return await operation();
    } catch (err) {
      if (!(err instanceof SimeSessionExpiredError)) throw err;
      await this.relogin();
      return await operation();
    }
  }

  async fetchDashboard(url, { gated = false, bypassCache = false } = {}) {
    const cached = this.pages.get(url);
    if (!bypassCache && cached && Date.now() - cached.fetchedAt < PAGE_TTL_MS) return cached;
    const resp = await this.http.request("GET", url, { gated });
    const $ = cheerio.load(resp.text);
    const pathname = new URL(resp.url).pathname.toLowerCase();
    if (pathname.endsWith("/public") || pathname.includes("login")) {
      throw new SimeSessionExpiredError("Portal session expired (redirected to public or login)");
    }
    if (!isDashboard($)) {
      if (MAINTENANCE_RE.test(textOf($("body")[0] || $.root()[0], " ").slice(0, 2000))) {
        this.http.globalBreaker.trip(DEFAULT_WAIT_SECONDS);
        throw new SimeMaintenanceError("Portal reports maintenance", DEFAULT_WAIT_SECONDS);
      }
      throw new SimeUnexpectedPageError("Portal returned a non-dashboard page", "dashboard_fetch");
    }
    const page = { url, html: resp.text, $, fetchedAt: Date.now() };
    this.pages.set(url, page);
    this.noteFetched(page);
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
    const { students, linkMap } = mergeStudents(profile, links);
    this.learnPage(page);

    for (const [key, info] of linkMap) {
      try {
        const resolved = resolveSimeUrl(info.href);
        this.studentUrls.set(key, resolved);
        this.portalIds.add(key);
        const canonical = this.canon(key);
        if (canonical) this.studentUrls.set(canonical, resolved);
      } catch (err) {
        if (err instanceof SimeNetworkError) continue;
        throw err;
      }
    }

    const curKey = studentKey(profile.id);
    if (curKey) {
      this.portalIds.add(curKey);
      if (!this.studentUrls.has(curKey)) {
        this.studentUrls.set(curKey, page.url);
      }
    }

    for (const s of students) {
      const k = studentKey(s.id);
      this.portalIds.add(k);
      const canonical = this.canon(k) || k;
      this.mergeKnown(canonical, s);
    }

    return this.listStudents();
  }

  // Fetches one student's own dashboard through their checksummed nav href. APEX keeps ONE current
  // student per session, so the page is verified to be theirs; one retry, then STUDENT_MISMATCH.
  async fetchStudentPage(key, { gated = false } = {}) {
    const href = this.studentUrls.get(key);
    if (!href) throw new SimeStudentNotFoundError(`No switch URL with checksum found for student: ${key}`);
    for (let attempt = 0; attempt < 2; attempt += 1) {
      const page = await this.fetchDashboard(formatSwitchUrl(href, this.auth.sessionId), { gated: gated && attempt === 0, bypassCache: true });
      if (page.studentKey === key) return page;
    }
    throw new SimeStudentMismatchError(`Portal did not switch to student ${key}`);
  }

  // Switches active student via nav-tree checksum href and parses full student state
  async switchStudent(studentId, { gated = false } = {}) {
    const cleanId = String(studentId || "").trim();
    if (!cleanId) throw new SimeStudentNotFoundError("Student ID is required");
    return this.withRetry(async () => {
      await this.ensureLogin();
      const key = this.canon(cleanId);
      if (!key) throw new SimeStudentNotFoundError(`No such student: ${cleanId}`);
      const page = await this.fetchStudentPage(key, { gated });
      return this.enrichPortalPage(page, key);
    });
  }

  // The requested student's own page. `assertCurrent` is for AJAX calls: the portal's current student
  // is global, so it must be re-asserted to be this student right before fetching.
  async loadStudent(studentId, { gated = false, bypassCache = false, assertCurrent = false } = {}) {
    await this.ensureLogin();
    const key = this.canon(studentId);
    if (!key) throw new SimeStudentNotFoundError(`No such student: ${studentId}`);
    const own = this.studentPages.get(key);
    const fresh = own && Date.now() - own.fetchedAt < PAGE_TTL_MS;
    if (!bypassCache && fresh && (!assertCurrent || this.currentKey === key)) return own;
    return this.fetchStudentPage(key, { gated });
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
    const { flowId, flowStepId } = extractPageFlowInfo(page?.$ || page?.html);
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
        p_flow_id: flowId,
        p_flow_step_id: flowStepId,
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
    const page = await this.loadStudent(studentId, { assertCurrent: true });
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
      const page = await this.loadStudent(studentId, { assertCurrent: true });
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
      const page = await this.loadStudent(studentId);
      const embeddedRecords = extractEmbeddedAbsenceRecords(page.$, today, studentId);
      if (embeddedRecords.length) {
        return embeddedRecords;
      }
      const year = Number(today.slice(0, 4));
      const startYear = Number(today.slice(5, 7)) >= 9 ? year : year - 1;
      const calEntries = await this.calendarRange(studentId, `${startYear}-09-01`, today);
      const htmlRecords = extractHtmlAbsenceRecords(page.$, today, studentId);
      return [...absenceRecords(calEntries, studentId), ...htmlRecords];
    });
  }

  // Teachers are derived from the lesson entries of a two to three week window plus class master metadata and catalog
  getTeachers(studentId) {
    return this.withRetry(async () => {
      const page = await this.loadStudent(studentId);
      const meta = parseStudentMetadata(page.$);
      const today = todayChisinau();
      const entries = await this.calendarRange(studentId, addDays(today, -7), addDays(today, 14));
      let catalogSubjects = [];
      try {
        const catalog = await this.getCatalog(studentId);
        catalogSubjects = catalog?.subjects || catalog?.disciplines || [];
      } catch {
        // Degrade gracefully if catalog cannot be loaded
      }
      return buildTeachers(entries, meta, catalogSubjects);
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
  const id = String(studentId || data.studentKey || data.student?.id || data.metadata?.id || "");
  const now = Date.now();
  const payload = { ...data, cachedAt: now };
  const prevRecord = session.students[id];
  const record = {
    metadata: payload.metadata,
    reusita: payload.reusita,
    absente: payload.absente,
    orar: payload.orar,
    homework: payload.homework,
    teachersDetailed: prevRecord?.teachersDetailed,
    teachersDetailedAt: prevRecord?.teachersDetailedAt,
    absenteDetailed: prevRecord?.absenteDetailed,
    absenteDetailedAt: prevRecord?.absenteDetailedAt,
    cachedAt: now,
    data: payload,
  };
  if (id) {
    session.students[id] = record;
    session.cache.set(`overview:${id}`, { at: now, data: payload });
  }
  if (Array.isArray(payload.students) && payload.students.length) {
    session.studentList = payload.students;
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

function resolveStudentKey(session, requested) {
  const reqStr = typeof requested === "string" ? requested.trim().slice(0, 128) : "";
  const students = session.client.listStudents();
  if (!reqStr) {
    if (students.length > 1) {
      return { error: { status: 400, code: "INVALID_REQUEST", message: "student parameter is required" } };
    }
    const single = students[0]?.id || session.defaultStudentId || "";
    if (!single) {
      return { error: { status: 400, code: "INVALID_REQUEST", message: "student parameter is required" } };
    }
    const canon = session.client.canon(single) || single;
    return { key: canon };
  }
  const canonical = session.client.canon(reqStr);
  if (!canonical) {
    return { error: { status: 404, code: "STUDENT_NOT_FOUND", message: "Elevul nu a fost găsit." } };
  }
  return { key: canonical };
}

function makeErrorPayload(code, message, retryAfter = null) {
  const payload = {
    error: {
      code,
      message,
      retryAfter,
    },
  };
  if (retryAfter !== null) {
    payload.waitSeconds = retryAfter;
  }
  return payload;
}

function sendError(res, err, token) {
  if (err instanceof SimeRateLimitError) {
    const retryAfter = err.waitSeconds || DEFAULT_WAIT_SECONDS;
    res.set("Retry-After", String(retryAfter));
    return res.status(429).json(makeErrorPayload("PORTAL_THROTTLED", "Portalul școlii a limitat solicitările. Încearcă din nou mai târziu.", retryAfter));
  }
  if (err instanceof SimeMaintenanceError) {
    const retryAfter = err.waitSeconds || DEFAULT_WAIT_SECONDS;
    res.set("Retry-After", String(retryAfter));
    return res.status(503).json(makeErrorPayload("PORTAL_MAINTENANCE", "Portalul școlii este în mentenanță sau temporar indisponibil.", retryAfter));
  }
  if (err instanceof SimeForbiddenError) {
    return res.status(502).json(makeErrorPayload("PORTAL_FORBIDDEN", "Acces interzis de portalul școlii (posibil blocare de securitate)."));
  }
  if (err instanceof SimeNotFoundError) {
    return res.status(502).json(makeErrorPayload("PORTAL_NOT_FOUND", "Pagina solicitată nu a fost găsită pe portalul școlii."));
  }
  if (err instanceof SimeStudentMismatchError) {
    return res.status(409).json(makeErrorPayload("STUDENT_MISMATCH", "Portalul a returnat alt elev decât cel selectat."));
  }
  if (err instanceof SimeStudentNotFoundError) {
    return res.status(404).json(makeErrorPayload("STUDENT_NOT_FOUND", "Elevul nu a fost găsit."));
  }
  if (err instanceof SimeLoginRejectedError) {
    return res.status(401).json(makeErrorPayload("PORTAL_LOGIN_REJECTED", "Numele de utilizator sau parola sunt incorecte."));
  }
  if (err instanceof SimeAppSessionError) {
    if (token) sessions.delete(token);
    return res.status(401).json(makeErrorPayload("APP_SESSION_INVALID", "Sesiunea a expirat. Autentifică-te din nou."));
  }
  if (err instanceof SimeUnexpectedPageError) {
    return res.status(502).json(makeErrorPayload("PORTAL_UNEXPECTED_PAGE", `Portalul a returnat o pagină neașteptată (${err.step || "necunoscut"}).`));
  }
  if (err instanceof SimeParseError) {
    return res.status(502).json(makeErrorPayload("PORTAL_UNEXPECTED_PAGE", "Portalul a returnat un răspuns neașteptat."));
  }
  if (err instanceof SimeSessionExpiredError) {
    return res.status(502).json(makeErrorPayload("PORTAL_UNEXPECTED_PAGE", "Sesiunea portalului nu a putut fi reînnoită."));
  }
  if (err instanceof SimeError) {
    return res.status(502).json(makeErrorPayload("PORTAL_UNEXPECTED_PAGE", err.message || "Eroare la comunicarea cu portalul."));
  }
  return res.status(500).json(makeErrorPayload("INTERNAL_ERROR", "Eroare internă a serverului."));
}

function requireSession(req, res, next) {
  const m = /^Bearer ([A-Za-z0-9_-]{20,128})$/.exec(req.get("authorization") || "");
  const session = m ? sessions.get(m[1]) : undefined;
  if (!session) return res.status(401).json(makeErrorPayload("APP_SESSION_INVALID", "Sesiunea a expirat. Autentifică-te din nou."));
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
      if (problem) return res.status(400).json(makeErrorPayload("INVALID_REQUEST", problem));
      const resKey = resolveStudentKey(req.session, req.query.student);
      if (resKey.error) {
        return res.status(resKey.error.status).json(makeErrorPayload(resKey.error.code, resKey.error.message));
      }
      const canonicalKey = resKey.key;
      const key = `${name}:${canonicalKey}:${keyFor(req)}`;
      const result = await cached(req.session, key, ttl, () => build(req.session.client, { id: canonicalKey }, req));
      const students = req.session.client.listStudents();
      const student = students.find((s) => s.id === canonicalKey) || { id: canonicalKey };
      return res.json({
        studentKey: canonicalKey,
        data: result.data,
        student,
        students,
        cache: { ageSeconds: result.ageSeconds, stale: result.stale },
      });
    } catch (err) {
      return sendError(res, err, req.token);
    }
  });
}

function scrubLog(input) {
  if (input === null || input === undefined) return input;
  if (typeof input === "string") {
    return input
      .replace(/"(password|p_t02|token|secret|pSalt|pPageSubmissionId)":\s*"[^"]*"/gi, '"$1":"[REDACTED]"')
      .replace(/\b(password|p_t02|pPassword)=[^&\s]+/gi, "$1=[REDACTED]")
      .replace(/\bBearer\s+[A-Za-z0-9_\-\.]+/gi, "Bearer [REDACTED]")
      .replace(/\b(cookie|set-cookie):\s*[^;\r\n]+/gi, "$1: [REDACTED]")
      .replace(/\b(cs|checksum)=[A-Za-z0-9_\-]+/gi, "$1=[REDACTED]")
      .replace(/\b(pSalt|pPageSubmissionId)=[0-9]+/gi, "$1=[REDACTED]");
  }
  if (Array.isArray(input)) {
    return input.map(scrubLog);
  }
  if (typeof input === "object") {
    const sensitiveKeys = new Set([
      "password", "p_t02", "token", "secret", "psalt", "ppagesubmissionId",
      "cookie", "cookies", "set-cookie", "checksum", "cs", "rawpassword",
    ]);
    const out = {};
    for (const [k, v] of Object.entries(input)) {
      if (sensitiveKeys.has(k.toLowerCase())) {
        out[k] = "[REDACTED]";
      } else {
        out[k] = scrubLog(v);
      }
    }
    return out;
  }
  return input;
}

function deriveRememberKey(secret) {
  if (Buffer.isBuffer(secret) && secret.length === 32) return secret;
  return crypto.createHash("sha256").update(String(secret)).digest();
}

function encryptRememberToken(data, secret) {
  if (!secret) return null;
  try {
    const key = deriveRememberKey(secret);
    const iv = crypto.randomBytes(12);
    const cipher = crypto.createCipheriv("aes-256-gcm", key, iv);
    const plaintext = Buffer.from(JSON.stringify(data), "utf-8");
    const ciphertext = Buffer.concat([cipher.update(plaintext), cipher.final()]);
    const tag = cipher.getAuthTag();
    return `${iv.toString("base64url")}.${tag.toString("base64url")}.${ciphertext.toString("base64url")}`;
  } catch {
    return null;
  }
}

function decryptRememberToken(token, secret) {
  if (!secret || !token || typeof token !== "string") return null;
  const parts = token.split(".");
  if (parts.length !== 3) return null;
  try {
    const key = deriveRememberKey(secret);
    const iv = Buffer.from(parts[0], "base64url");
    const tag = Buffer.from(parts[1], "base64url");
    const ciphertext = Buffer.from(parts[2], "base64url");
    const decipher = crypto.createDecipheriv("aes-256-gcm", key, iv);
    decipher.setAuthTag(tag);
    const decrypted = Buffer.concat([decipher.update(ciphertext), decipher.final()]);
    return JSON.parse(decrypted.toString("utf-8"));
  } catch {
    return null;
  }
}

const REMEMBER_STORE_PATH = process.env.SIME_REMEMBER_STORE || path.resolve("data/remember-tokens.json");
const REMEMBER_KEY_PATH = process.env.SIME_REMEMBER_KEY_FILE || path.resolve("data/remember-secret.key");
let memRememberKey = null;

function getRememberSecretKey() {
  if (process.env.SIME_REMEMBER_SECRET) {
    return deriveRememberKey(process.env.SIME_REMEMBER_SECRET);
  }
  try {
    if (fs.existsSync(REMEMBER_KEY_PATH)) {
      const existing = fs.readFileSync(REMEMBER_KEY_PATH);
      if (existing.length === 32) return existing;
      return deriveRememberKey(existing);
    }
    const dir = path.dirname(REMEMBER_KEY_PATH);
    if (!fs.existsSync(dir)) {
      fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
    }
    const generated = crypto.randomBytes(32);
    fs.writeFileSync(REMEMBER_KEY_PATH, generated, { mode: 0o600 });
    return generated;
  } catch {
    if (!memRememberKey) {
      memRememberKey = crypto.randomBytes(32);
    }
    return memRememberKey;
  }
}

function loadRememberTokens() {
  try {
    if (!fs.existsSync(REMEMBER_STORE_PATH)) return {};
    const raw = fs.readFileSync(REMEMBER_STORE_PATH, "utf8");
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === "object" ? parsed : {};
  } catch {
    return {};
  }
}

function saveRememberTokens(tokens) {
  try {
    const dir = path.dirname(REMEMBER_STORE_PATH);
    if (!fs.existsSync(dir)) {
      fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
    }
    fs.writeFileSync(REMEMBER_STORE_PATH, JSON.stringify(tokens, null, 2), { mode: 0o600 });
  } catch {
    // Fail closed or keep in memory if file cannot be written
  }
}

function parseCookie(cookieHeader, name) {
  if (!cookieHeader || typeof cookieHeader !== "string") return null;
  for (const part of cookieHeader.split(";")) {
    const [k, ...v] = part.trim().split("=");
    if (k === name) return decodeURIComponent(v.join("="));
  }
  return null;
}

function makeRememberCookie(token, req, maxAge = 30 * 24 * 3600) {
  const isSecure = req.secure || req.get("x-forwarded-proto") === "https";
  const secureFlag = isSecure ? "; Secure" : "";
  return `sime_remember=${encodeURIComponent(token)}; HttpOnly; SameSite=Lax; Path=/api/auth; Max-Age=${maxAge}${secureFlag}`;
}

function makeClearCookie(req) {
  const isSecure = req.secure || req.get("x-forwarded-proto") === "https";
  const secureFlag = isSecure ? "; Secure" : "";
  return `sime_remember=; HttpOnly; SameSite=Lax; Path=/api/auth; Max-Age=0${secureFlag}`;
}

function isCrossOrigin(req) {
  const secFetchSite = (req.get("sec-fetch-site") || "").toLowerCase();
  if (secFetchSite === "cross-site") return true;
  const host = req.get("host");
  const origin = req.get("origin");
  if (origin && host) {
    try {
      const u = new URL(origin);
      if (u.host !== host) return true;
    } catch {
      return true;
    }
  }
  const referer = req.get("referer");
  if (!origin && referer && host) {
    try {
      const u = new URL(referer);
      if (u.host !== host) return true;
    } catch {
      return true;
    }
  }
  return false;
}

const resumeRateMap = new Map();
const RESUME_MAX_ATTEMPTS = 5;
const RESUME_WINDOW_MS = 15 * 60 * 1000;

function checkResumeRate(ip) {
  const now = Date.now();
  const history = (resumeRateMap.get(ip) || []).filter((t) => now - t < RESUME_WINDOW_MS);
  if (history.length >= RESUME_MAX_ATTEMPTS) {
    resumeRateMap.set(ip, history);
    const oldest = history[0];
    const retryAfter = Math.ceil((oldest + RESUME_WINDOW_MS - now) / 1000);
    return { allowed: false, retryAfter: Math.max(1, retryAfter) };
  }
  history.push(now);
  resumeRateMap.set(ip, history);
  return { allowed: true };
}

function createApp() {
  const app = express();
  app.disable("x-powered-by");

  app.use((req, res, next) => {
    const headers = {
      "Content-Security-Policy":
        "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
      "X-Content-Type-Options": "nosniff",
      "X-Frame-Options": "DENY",
      "Referrer-Policy": "no-referrer",
    };
    const isSecure = req.secure || req.get("x-forwarded-proto") === "https";
    if (isSecure) {
      headers["Strict-Transport-Security"] = "max-age=31536000; includeSubDomains";
    }
    res.set(headers);
    next();
  });
  app.use("/api", (_req, res, next) => {
    res.set({
      "Cache-Control": "no-store",
      "Content-Type": "application/json; charset=utf-8",
    });
    next();
  });
  app.use(express.json({ limit: "4kb" }));

  app.get("/api/auth/config", (_req, res) => {
    res.json({
      rememberMeAvailable: true,
    });
  });

  app.post("/api/auth/login", async (req, res) => {
    const { username, password, role, remember, rememberMe } = req.body ?? {};
    if (typeof username !== "string" || typeof password !== "string" || !username || !password || username.length > 128 || password.length > 256) {
      return res.status(400).json(makeErrorPayload("INVALID_REQUEST", "Username and password are required"));
    }
    const guardKey = sha(username.toLowerCase());
    const guard = loginGuard.get(guardKey);
    if (guard && guard.until > Date.now()) {
      const waitSeconds = Math.ceil((guard.until - Date.now()) / 1000);
      res.set("Retry-After", String(waitSeconds));
      return res.status(429).json(makeErrorPayload("PORTAL_THROTTLED", "Login locked after repeated rejections", waitSeconds));
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
        return res.status(401).json(makeErrorPayload("PORTAL_LOGIN_REJECTED", "Credentials were rejected"));
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

      const shouldRemember = Boolean(remember || rememberMe);
      if (shouldRemember) {
        const secretKey = getRememberSecretKey();
        const encrypted = encryptRememberToken({ username, password, role: normalizedRole }, secretKey);
        if (encrypted) {
          const rawToken = crypto.randomBytes(32).toString("base64url");
          const tokenHash = crypto.createHash("sha256").update(rawToken).digest("hex");
          const tokens = loadRememberTokens();
          tokens[tokenHash] = {
            tokenHash,
            encrypted,
            role: normalizedRole,
            createdAt: now,
            lastUsedAt: now,
            userAgent: req.get("user-agent") || "",
          };
          saveRememberTokens(tokens);
          res.setHeader("Set-Cookie", makeRememberCookie(rawToken, req, 30 * 24 * 3600));
        }
      }

      return res.json({ token, role: normalizedRole, student: record.data.student, students: record.data.students, data: record.data });
    } catch (err) {
      return sendError(res, err, null);
    }
  });

  app.post("/api/auth/resume", async (req, res) => {
    if (isCrossOrigin(req)) {
      return res.status(403).json(makeErrorPayload("FORBIDDEN", "Cross-origin request blocked"));
    }
    const ip = req.ip || req.socket?.remoteAddress || "unknown";
    const rate = checkResumeRate(ip);
    if (!rate.allowed) {
      res.set("Retry-After", String(rate.retryAfter));
      return res.status(429).json(makeErrorPayload("PORTAL_THROTTLED", "Too many resume attempts", rate.retryAfter));
    }

    const rawToken = parseCookie(req.headers.cookie, "sime_remember");
    if (!rawToken) {
      return res.status(401).json(makeErrorPayload("APP_SESSION_INVALID", "No remember token"));
    }

    const tokenHash = crypto.createHash("sha256").update(rawToken).digest("hex");
    const tokens = loadRememberTokens();
    const stored = tokens[tokenHash];
    if (!stored) {
      res.setHeader("Set-Cookie", makeClearCookie(req));
      return res.status(401).json(makeErrorPayload("APP_SESSION_INVALID", "Invalid remember token"));
    }

    const now = Date.now();
    const INACTIVITY_MS = 30 * 24 * 3600 * 1000;
    if (now - (stored.lastUsedAt || 0) > INACTIVITY_MS) {
      delete tokens[tokenHash];
      saveRememberTokens(tokens);
      res.setHeader("Set-Cookie", makeClearCookie(req));
      return res.status(401).json(makeErrorPayload("APP_SESSION_INVALID", "Remember token expired"));
    }

    const secretKey = getRememberSecretKey();
    const creds = decryptRememberToken(stored.encrypted, secretKey);
    if (!creds || !creds.username || !creds.password) {
      delete tokens[tokenHash];
      saveRememberTokens(tokens);
      res.setHeader("Set-Cookie", makeClearCookie(req));
      return res.status(401).json(makeErrorPayload("APP_SESSION_INVALID", "Invalid remember token payload"));
    }

    const normalizedRole = creds.role === "student" ? "student" : "parent";
    const client = new SimeClient(creds.username, creds.password, normalizedRole);
    try {
      const loginResult = await client.loginAndParsePortal();
      if (!loginResult) {
        delete tokens[tokenHash];
        saveRememberTokens(tokens);
        res.setHeader("Set-Cookie", makeClearCookie(req));
        return res.status(401).json(makeErrorPayload("PORTAL_LOGIN_REJECTED", "Remembered credentials were rejected"));
      }

      delete tokens[tokenHash];
      const newRawToken = crypto.randomBytes(32).toString("base64url");
      const newTokenHash = crypto.createHash("sha256").update(newRawToken).digest("hex");
      tokens[newTokenHash] = {
        tokenHash: newTokenHash,
        encrypted: stored.encrypted,
        role: normalizedRole,
        createdAt: stored.createdAt || now,
        lastUsedAt: now,
        userAgent: req.get("user-agent") || "",
      };
      saveRememberTokens(tokens);
      res.setHeader("Set-Cookie", makeRememberCookie(newRawToken, req, 30 * 24 * 3600));

      const { data } = loginResult;
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
      delete tokens[tokenHash];
      saveRememberTokens(tokens);
      res.setHeader("Set-Cookie", makeClearCookie(req));
      return sendError(res, err, null);
    }
  });

  app.post("/api/auth/forget-device", (req, res) => {
    if (isCrossOrigin(req)) {
      return res.status(403).json(makeErrorPayload("FORBIDDEN", "Cross-origin request blocked"));
    }
    const rawToken = parseCookie(req.headers.cookie, "sime_remember");
    if (rawToken) {
      const tokenHash = crypto.createHash("sha256").update(rawToken).digest("hex");
      const tokens = loadRememberTokens();
      if (tokens[tokenHash]) {
        delete tokens[tokenHash];
        saveRememberTokens(tokens);
      }
    }
    res.setHeader("Set-Cookie", makeClearCookie(req));
    return res.json({ ok: true });
  });

  app.post("/api/auth/logout", requireSession, (req, res) => {
    if (req.session) {
      req.session.cache.clear();
      for (const k of Object.keys(req.session.students)) {
        delete req.session.students[k];
      }
      if (req.session.client?.studentPages) {
        req.session.client.studentPages.clear();
      }
    }
    sessions.delete(req.token);
    const rawToken = parseCookie(req.headers.cookie, "sime_remember");
    if (rawToken) {
      const tokenHash = crypto.createHash("sha256").update(rawToken).digest("hex");
      const tokens = loadRememberTokens();
      if (tokens[tokenHash]) {
        delete tokens[tokenHash];
        saveRememberTokens(tokens);
      }
    }
    res.setHeader("Set-Cookie", makeClearCookie(req));
    res.status(204).end();
  });

  app.post("/api/student/switch", requireSession, async (req, res) => {
    if (!req.session) return res.status(401).json(makeErrorPayload("APP_SESSION_INVALID", "Not authenticated"));
    if (req.session.role === "student" || req.session.role === "E") return res.status(400).json(makeErrorPayload("INVALID_REQUEST", "Students cannot switch students"));
    const rawId = req.body?.studentId;
    const studentId = typeof rawId === "string" || typeof rawId === "number" ? String(rawId).trim().slice(0, 128) : "";
    if (!studentId) {
      return res.status(400).json(makeErrorPayload("INVALID_REQUEST", "studentId is required"));
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
        role: req.session.role,
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
      const resKey = resolveStudentKey(req.session, req.query.student);
      if (resKey.error) {
        return res.status(resKey.error.status).json(makeErrorPayload(resKey.error.code, resKey.error.message));
      }
      const canonicalKey = resKey.key;
      const force = req.query.force === "1" || req.query.force === "true";
      const key = `overview:${canonicalKey}`;
      const hit = req.session.cache.get(key);
      const now = Date.now();

      if (!force && hit && now - hit.at < TTL.overview) {
        const students = req.session.client.listStudents();
        const student = students.find((s) => s.id === canonicalKey) || hit.data.student || { id: canonicalKey };
        return res.json({
          role: req.session.role,
          studentKey: canonicalKey,
          data: hit.data,
          student,
          students,
          cache: { ageSeconds: Math.floor((now - hit.at) / 1000), stale: false },
        });
      }

      const result = await serialize(req.session, async () => {
        const currentHit = req.session.cache.get(key);
        if (!force && currentHit && Date.now() - currentHit.at < TTL.overview) {
          return { data: currentHit.data, ageSeconds: Math.floor((Date.now() - currentHit.at) / 1000), stale: false };
        }
        if (currentHit && breaker.remainingSeconds() > 0) {
          return { data: currentHit.data, ageSeconds: Math.floor((Date.now() - currentHit.at) / 1000), stale: true };
        }
        const shortWindow = Date.now() - (req.session.lastSyncAt || 0) < TTL.overview;
        const shouldGate = force || shortWindow;
        try {
          const isSwitching = Boolean(canonicalKey && canonicalKey !== req.session.client.currentKey);
          const data = isSwitching
            ? await req.session.client.switchStudent(canonicalKey, { gated: shouldGate })
            : await req.session.client.fetchStudentPortalData(canonicalKey, { gated: shouldGate, bypassCache: true });
          const at = Date.now();
          req.session.lastSyncAt = at;
          req.session.defaultStudentId = canonicalKey;
          const entry = { at, data };
          req.session.cache.set(key, entry);
          const prevStudentSession = req.session.students[canonicalKey];
          req.session.students[canonicalKey] = {
            metadata: data.metadata,
            reusita: data.reusita,
            absente: data.absente,
            orar: data.orar,
            homework: data.homework,
            teachersDetailed: prevStudentSession?.teachersDetailed,
            teachersDetailedAt: prevStudentSession?.teachersDetailedAt,
            absenteDetailed: prevStudentSession?.absenteDetailed,
            absenteDetailedAt: prevStudentSession?.absenteDetailedAt,
            cachedAt: at,
            data,
          };
          return { data, ageSeconds: 0, stale: false };
        } catch (err) {
          if (currentHit && isUpstreamFailure(err)) {
            return { data: currentHit.data, ageSeconds: Math.floor((Date.now() - currentHit.at) / 1000), stale: true };
          }
          throw err;
        }
      });

      const students = req.session.client.listStudents();
      const student = students.find((s) => s.id === canonicalKey) || result.data.student || { id: canonicalKey };

      return res.json({
        role: req.session.role,
        studentKey: canonicalKey,
        data: result.data,
        student,
        students,
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
    if (hit?.teachersDetailed && Date.now() - hit.teachersDetailedAt < TTL.teachers) {
      return { teachers: hit.teachersDetailed };
    }
    const teachers = await client.getTeachers(student.id);
    if (hit) {
      hit.teachersDetailed = teachers;
      hit.teachersDetailedAt = Date.now();
    }
    return { teachers };
  });
  dataRoute(app, "grades", TTL.grades, async (client, student) => ({ grades: await client.getLatestGrades(student.id) }));
  dataRoute(app, "catalog", TTL.catalog, (client, student) => client.getCatalog(student.id));
  dataRoute(app, "reusita", TTL.reusita, (client, student) => client.getCatalog(student.id));
  dataRoute(app, "homework", TTL.homework, async (client, student) => ({ homework: await client.getHomework(student.id) }));
  dataRoute(app, "absences", TTL.absences, async (client, student, req) => {
    const hit = req.session.students[student.id];
    if (hit?.absenteDetailed && Date.now() - hit.absenteDetailedAt < TTL.absences) {
      return hit.absenteDetailed;
    }
    const summary = await client.getAbsenceSummary(student.id);
    const records = await client.getAbsenceRecords(student.id);
    const detailed = buildSemesterAbsences(summary, records, student.id);
    detailed.detail = true;
    if (hit) {
      hit.absenteDetailed = detailed;
      hit.absenteDetailedAt = Date.now();
    }
    return detailed;
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

  app.get("/api/debug/parser-health", (req, res) => {
    if (process.env.SIME_DEBUG !== "1") {
      return res.status(404).json({ error: "Debug endpoints disabled" });
    }
    return requireSession(req, res, async () => {
      try {
        const requested = typeof req.query.student === "string" ? req.query.student.trim() : "";
        const targetId = requested || req.session.defaultStudentId || "";
        const page = await req.session.client.loadStudent(targetId);
        const health = inspectParserHealth(page.html);
        return res.json(health);
      } catch (err) {
        return sendError(res, err, req.token);
      }
    });
  });

  app.get("/", (_req, res) => {
    res.set("Content-Type", "text/html; charset=utf-8");
    res.sendFile(path.join(ROOT, "index.html"));
  });
  app.get("/sw.js", (_req, res) => {
    res.set({
      "Content-Type": "application/javascript; charset=utf-8",
      "Service-Worker-Allowed": "/",
      "Cache-Control": "no-cache",
    });
    res.sendFile(path.join(ROOT, "public", "sw.js"));
  });
  app.use(
    "/public",
    express.static(path.join(ROOT, "public"), {
      index: false,
      dotfiles: "ignore",
      setHeaders: (res, filePath) => {
        if (filePath.endsWith(".js")) res.set("Content-Type", "application/javascript; charset=utf-8");
        else if (filePath.endsWith(".css")) res.set("Content-Type", "text/css; charset=utf-8");
        else if (filePath.endsWith(".html")) res.set("Content-Type", "text/html; charset=utf-8");
        else if (filePath.endsWith(".json")) res.set("Content-Type", "application/json; charset=utf-8");
      },
    })
  );

  app.use((err, _req, res, _next) => {
    if (err?.type === "entity.parse.failed" || err?.type === "entity.too.large") {
      return res.status(400).json(makeErrorPayload("INVALID_REQUEST", "Invalid request body"));
    }
    return res.status(500).json(makeErrorPayload("INTERNAL_ERROR", "Internal error"));
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
  formatSwitchUrl,
  inferAbsenceSemester,
  inspectParserHealth,
  loginGuard,
  parseAbsenceSummary,
  parseCalendarEvents,
  parseDate,
  parseFullPortal,
  parseHomework,
  parseRecentGrades,
  parseSemesterReport,
  parseStudentMetadata,
  BROWSER_PROFILE,
  extractPageFlowInfo,
  extractHtmlAbsenceRecords,
  extractEmbeddedAbsenceRecords,
  findCalendarAjaxId,
  findReportAjaxId,
  REGIONS,
  todayChisinau,
  scrubLog,
  encryptRememberToken,
  decryptRememberToken,
  loadRememberTokens,
  saveRememberTokens,
  makeRememberCookie,
  makeClearCookie,
  parseCookie,
  getRememberSecretKey,
  RateGate,
  requireSimeUrl,
  resolveSimeUrl,
  sessions,
  SimeAppSessionError,
  SimeAuth,
  SimeClient,
  SimeError,
  SimeForbiddenError,
  SimeHttp,
  SimeLoginRejectedError,
  SimeMaintenanceError,
  SimeNetworkError,
  SimeNotFoundError,
  SimeParseError,
  SimeRateLimitError,
  SimeSessionExpiredError,
  SimeStudentMismatchError,
  SimeStudentNotFoundError,
  SimeUnexpectedPageError,
  studentKey,
  studentSwitchUrl,
  normalizeName,
  normalizeMark,
  normalizeQualifierKey,
  splitMarkTokens,
  QUALIFIERS,
  normalizeSubjectName,
  getSubjectScale,
  SUBJECT_SCALE_REGISTRY,
  parseMarkCell,
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
