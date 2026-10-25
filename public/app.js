"use strict";

const TZ = "Europe/Chisinau";
const KEYS = {
  token: "open-sime.token",
  student: "open-sime.student",
  data: "open-sime.data",
  sync: "open-sime.sync",
  theme: "open-sime.theme",
  role: "open-sime.role",
  tab: "open-sime.tab",
  lang: "open-sime.lang",
  textSize: "open-sime.text-size",
  simpleMode: "open-sime.simple-mode",
  contrast: "open-sime.contrast",
  reduceMotion: "open-sime.reduce-motion",
};
const TICK_MS = 30000;
const SYNC_COOLDOWN_MS = 30000;
const STALE_AFTER_MS = 5 * 60000;
const DAY_MS = 86400000;
const THEMES = ["light", "dark"];
const THEME_LABELS = { light: "luminoasă", dark: "întunecată" };
const THEME_ICONS = { light: "dark_mode", dark: "light_mode" };

const TABS = [
  { id: "home", label: "Acasă", title: "Acasă", icon: "home" },
  { id: "marks", label: "Reușita", title: "Reușita", icon: "grade" },
  { id: "timetable", label: "Orar", title: "Orar", icon: "schedule" },
  { id: "absences", label: "Absențe", title: "Absențe", icon: "event_busy" },
  { id: "homework", label: "Teme", title: "Teme", icon: "assignment" },
  { id: "profile", label: "Profil", title: "Profil și profesori", icon: "person", railOnly: true },
];

const ICONS = {
  home: "M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z",
  grade: "M12 17.27 18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z",
  schedule: "M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zm.5-13H11v6l5.25 3.15.75-1.23-4.5-2.67z",
  event_busy: "M9.31 17l2.44-2.44L14.19 17l1.06-1.06-2.44-2.44 2.44-2.44L14.19 10l-2.44 2.44L9.31 10l-1.06 1.06 2.44 2.44-2.44 2.44L9.31 17zM19 3h-1V1h-2v2H8V1H6v2H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 16H5V8h14v11z",
  assignment: "M19 3h-4.18C14.4 1.84 13.3 1 12 1s-2.4.84-2.82 2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm-7 0c.55 0 1 .45 1 1s-.45 1-1 1-1-.45-1-1 .45-1 1-1zm2 14H7v-2h7v2zm3-4H7v-2h10v2zm0-4H7V7h10v2z",
  person: "M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z",
  sync: "M12 4V1L8 5l4 4V6c3.31 0 6 2.69 6 6 0 1.01-.25 1.97-.7 2.8l1.46 1.46C19.54 15.03 20 13.57 20 12c0-4.42-3.58-8-8-8zm0 14c-3.31 0-6-2.69-6-6 0-1.01.25-1.97.7-2.8L5.24 7.74C4.46 8.97 4 10.43 4 12c0 4.42 3.58 8 8 8v3l4-4-4-4v3z",
  logout: "M17 7l-1.41 1.41L18.17 11H8v2h10.17l-2.58 2.58L17 17l5-5zM4 5h8V3H4c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h8v-2H4V5z",
  chevron_left: "M15.41 7.41 14 6l-6 6 6 6 1.41-1.41L10.83 12z",
  chevron_right: "M10 6 8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z",
  contrast: "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18V4c4.41 0 8 3.59 8 8s-3.59 8-8 8z",
  light_mode: "M12 7c-2.76 0-5 2.24-5 5s2.24 5 5 5 5-2.24 5-5-2.24-5-5-5zM2 13h2c.55 0 1-.45 1-1s-.45-1-1-1H2c-.55 0-1 .45-1 1s.45 1 1 1zm18 0h2c.55 0 1-.45 1-1s-.45-1-1-1h-2c-.55 0-1 .45-1 1s.45 1 1 1zM11 2v2c0 .55.45 1 1 1s1-.45 1-1V2c0-.55-.45-1-1-1s-1 .45-1 1zm0 18v2c0 .55.45 1 1 1s1-.45 1-1v-2c0-.55-.45-1-1-1s-1 .45-1 1zM5.99 4.58a.996.996 0 0 0-1.41 0 .996.996 0 0 0 0 1.41l1.06 1.06c.39.39 1.03.39 1.41 0s.39-1.03 0-1.41L5.99 4.58zm12.37 12.37a.996.996 0 0 0-1.41 0 .996.996 0 0 0 0 1.41l1.06 1.06c.39.39 1.03.39 1.41 0a.996.996 0 0 0 0-1.41l-1.06-1.06zm1.06-10.96a.996.996 0 0 0 0-1.41.996.996 0 0 0-1.41 0l-1.06 1.06c-.39.39-.39 1.03 0 1.41s1.03.39 1.41 0l1.06-1.06zM7.05 18.36a.996.996 0 0 0 0-1.41.996.996 0 0 0-1.41 0l-1.06 1.06c-.39.39-.39 1.03 0 1.41s1.03.39 1.41 0l1.06-1.06z",
  dark_mode: "M9.37 5.51A7.35 7.35 0 0 0 9.1 7.5c0 4.08 3.32 7.4 7.4 7.4.68 0 1.35-.09 1.99-.27A7.014 7.014 0 0 1 12 19c-3.86 0-7-3.14-7-7 0-2.93 1.81-5.45 4.37-6.49zM12 3a9 9 0 1 0 9 9c0-.46-.04-.92-.1-1.36a5.389 5.389 0 0 1-4.4 2.26 5.403 5.403 0 0 1-3.14-9.8c-.44-.06-.9-.1-1.36-.1z",
  visibility: "M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zM12 17c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z",
  visibility_off: "M12 7c2.76 0 5 2.24 5 5 0 .65-.13 1.26-.36 1.83l2.92 2.92c1.51-1.26 2.7-2.89 3.43-4.75-1.73-4.39-6-7.5-11-7.5-1.4 0-2.74.25-3.98.7l2.16 2.16C10.74 7.13 11.35 7 12 7zM2 4.27l2.28 2.28.46.46A11.804 11.804 0 0 0 1 12c1.73 4.39 6 7.5 11 7.5 1.55 0 3.03-.3 4.38-.84l.42.42L19.73 22 21 20.73 3.27 3 2 4.27zM7.53 9.8l1.55 1.55c-.05.21-.08.43-.08.65 0 1.66 1.34 3 3 3 .22 0 .44-.03.65-.08l1.55 1.55c-.67.33-1.41.53-2.2.53-2.76 0-5-2.24-5-5 0-.79.2-1.53.53-2.2zm4.31-.78 3.15 3.15.02-.16c0-1.66-1.34-3-3-3l-.17.01z",
  room: "M12 2C8.13 2 5 5.13 5 9c0 5.25 7 13 7 13s7-7.75 7-13c0-3.87-3.13-7-7-7zm0 9.5a2.5 2.5 0 0 1 0-5 2.5 2.5 0 0 1 0 5z",
  error: "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z",
  info: "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z",
  check_circle: "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z",
  school: "M5 13.18v4L12 21l7-3.82v-4L12 17l-7-3.82zM12 3 1 9l11 6 9-4.91V17h2V9L12 3z",
  settings: "M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58a.49.49 0 0 0 .12-.61l-1.92-3.32a.488.488 0 0 0-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54a.484.484 0 0 0-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58a.49.49 0 0 0-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z",
  close: "M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z",
};

const MONTHS = ["ianuarie", "februarie", "martie", "aprilie", "mai", "iunie", "iulie", "august", "septembrie", "octombrie", "noiembrie", "decembrie"];
const MONTHS_SHORT = ["ian", "feb", "mar", "apr", "mai", "iun", "iul", "aug", "sep", "oct", "nov", "dec"];
const WEEKDAYS = ["Luni", "Marți", "Miercuri", "Joi", "Vineri", "Sâmbătă", "Duminică"];
const WEEKDAYS_SHORT = ["Lu", "Ma", "Mi", "Jo", "Vi"];
const BAND_LABELS = { "band-top": "Excelent", "band-good": "Bine", "band-ok": "Suficient", "band-low": "Insuficient", "band-none": "Fără medie" };

const SIMPLE_TAB_TITLES = {
  home: "Acasă",
  marks: "Notele mele",
  timetable: "Orarul meu",
  absences: "Cât am lipsit",
  homework: "Ce am de învățat",
  profile: "Despre mine",
};

const state = {
  token: null,
  studentKey: "",
  data: null,
  students: [],
  tab: "home",
  theme: "light",
  role: "student",
  lang: "ro",
  textSize: "M",
  simpleMode: false,
  contrast: false,
  reduceMotion: false,
  semester: 1,
  semesterTouched: false,
  day: null,
  today: "",
  syncedAt: 0,
  cooldownUntil: 0,
  limitedUntil: 0,
  syncing: false,
  switching: null,
  stale: false,
  offline: false,
  error: "",
  seq: 0,
  authBusy: false,
  authLockUntil: 0,
};

const $ = (id) => document.getElementById(id);

const store = {
  get(key) {
    try {
      return window.localStorage.getItem(key);
    } catch {
      return null;
    }
  },
  set(key, value) {
    try {
      window.localStorage.setItem(key, value);
    } catch {
      return;
    }
  },
  del(key) {
    try {
      window.localStorage.removeItem(key);
    } catch {
      return;
    }
  },
};

class ApiError extends Error {
  constructor(message, status, wait = 0, code = null) {
    super(message);
    this.status = status;
    this.wait = wait || 0;
    this.code = code;
  }
}

// formatting and dates

const num = (value) => (typeof value === "number" && Number.isFinite(value) ? value : null);
const pad2 = (n) => String(n).padStart(2, "0");
const canonKey = (id) => (typeof id === "string" || typeof id === "number" ? String(id).trim().toLowerCase() : "");
const markFmt = new Intl.NumberFormat("ro-RO", { maximumFractionDigits: 2 });
const avgFmt = new Intl.NumberFormat("ro-RO", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const fmtMark = (value) => (value == null || Number.isNaN(Number(value)) ? "" : markFmt.format(value));
const fmtAvg = (value) => (value == null || Number.isNaN(Number(value)) ? "-" : avgFmt.format(value));
const tzFmt = new Intl.DateTimeFormat("en-GB", { timeZone: TZ, year: "numeric", month: "2-digit", day: "2-digit" });

function todayIso(now = Date.now()) {
  const parts = {};
  for (const part of tzFmt.formatToParts(new Date(now))) parts[part.type] = part.value;
  return parts.year + "-" + parts.month + "-" + parts.day;
}

const validIso = (value) => typeof value === "string" && /^\d{4}-\d{2}-\d{2}$/.test(value);
const isoMs = (iso) => Date.UTC(Number(iso.slice(0, 4)), Number(iso.slice(5, 7)) - 1, Number(iso.slice(8, 10)));
const addDays = (iso, n) => new Date(isoMs(iso) + n * DAY_MS).toISOString().slice(0, 10);
const weekdayIndex = (iso) => (new Date(isoMs(iso)).getUTCDay() + 6) % 7;
const mondayOf = (iso) => addDays(iso, -weekdayIndex(iso));
const dayDiff = (from, to) => Math.round((isoMs(to) - isoMs(from)) / DAY_MS);
const dayNum = (iso) => Number(iso.slice(8, 10));
const monthIdx = (iso) => Number(iso.slice(5, 7)) - 1;
const shortDate = (iso) => (validIso(iso) ? dayNum(iso) + " " + (MONTHS_SHORT[monthIdx(iso)] || "") : "");
const longDate = (iso) => (validIso(iso) ? (WEEKDAYS[weekdayIndex(iso)] || "") + ", " + dayNum(iso) + " " + (MONTHS[monthIdx(iso)] || "") : "");

function relativeDay(iso, today) {
  const diff = dayDiff(today, iso);
  if (diff === 0) return "Astăzi";
  if (diff === 1) return "Mâine";
  if (diff === -1) return "Ieri";
  if (diff > 1) return "Peste " + diff + " zile";
  return "Acum " + Math.abs(diff) + " zile";
}

function minutesText(mins) {
  if (mins < 60) return mins + " min";
  const hours = Math.floor(mins / 60);
  const rest = mins % 60;
  return rest ? hours + " h " + rest + " min" : hours + " h";
}

function agoText(ms) {
  const secs = Math.max(0, Math.floor(ms / 1000));
  if (secs < 45) return "chiar acum";
  const mins = Math.round(secs / 60);
  if (mins < 60) return "acum " + Math.max(1, mins) + " min";
  const hours = Math.floor(mins / 60);
  if (hours < 24) return "acum " + hours + " h";
  const days = Math.floor(hours / 24);
  return "acum " + days + (days === 1 ? " zi" : " zile");
}

function waitText(secs) {
  if (secs < 90) return secs + " secunde";
  return Math.ceil(secs / 60) + " minute";
}

function initials(name) {
  const words = String(name || "").split(/\s+/).filter(Boolean);
  const letters = words.slice(0, 2).map((w) => w.charAt(0));
  return letters.join("").toUpperCase() || "?";
}

function toTitleCase(str) {
  if (!str) return "";
  return String(str).toLowerCase().replace(/(?:^|\s|-)\S/g, (c) => c.toUpperCase());
}

function bandOf(value, label = "") {
  const l = String(label || "").toUpperCase();
  if (l === "FB") return "band-top";
  if (l === "B") return "band-good";
  if (l === "S") return "band-ok";
  if (l === "I") return "band-low";

  if (value == null) return "band-none";
  if (value >= 9) return "band-top";
  if (value >= 7) return "band-good";
  if (value >= 5) return "band-ok";
  return "band-low";
}

// DOM construction

const SVG_NS = "http://www.w3.org/2000/svg";

function append(parent, kids) {
  for (const kid of kids) {
    if (kid == null || kid === false || Number.isNaN(kid)) continue;
    if (Array.isArray(kid)) append(parent, kid);
    else if (kid instanceof Node) parent.appendChild(kid);
    else if (typeof kid !== "object") parent.appendChild(document.createTextNode(String(kid)));
  }
}

function setChildren(parent, ...kids) {
  parent.replaceChildren();
  append(parent, kids);
  return parent;
}

function h(tag, attrs, ...kids) {
  const el = document.createElement(tag);
  if (attrs) {
    for (const key of Object.keys(attrs)) {
      const value = attrs[key];
      if (value == null || value === false || Number.isNaN(value)) continue;
      if (key === "class") el.className = value;
      else if (key === "text") el.textContent = String(value);
      else if (key === "on") for (const type of Object.keys(value)) el.addEventListener(type, value[type]);
      else el.setAttribute(key, value === true ? "" : String(value));
    }
  }
  append(el, kids);
  return el;
}

function svgEl(name, attrs) {
  const el = document.createElementNS(SVG_NS, name);
  for (const key of Object.keys(attrs)) el.setAttribute(key, attrs[key]);
  return el;
}

function icon(name) {
  const svg = svgEl("svg", { viewBox: "0 0 24 24", class: "icon", "aria-hidden": "true", focusable: "false" });
  svg.appendChild(svgEl("path", { d: ICONS[name] || ICONS.info }));
  return svg;
}

function setIcon(el, name) {
  const old = el.querySelector("svg");
  if (old) old.remove();
  el.prepend(icon(name));
}

const COOKIE_PATH = (() => {
  const lobes = 10;
  const depth = 0.07;
  const steps = 120;
  const points = [];
  for (let i = 0; i < steps; i += 1) {
    const t = (i / steps) * Math.PI * 2;
    const r = (45 * (1 + depth * Math.cos(lobes * t))) / (1 + depth);
    points.push((50 + r * Math.cos(t)).toFixed(2) + " " + (50 + r * Math.sin(t)).toFixed(2));
  }
  return "M" + points.join("L") + "Z";
})();

function cookieSvg(cls) {
  const svg = svgEl("svg", { viewBox: "0 0 100 100", class: cls, "aria-hidden": "true", focusable: "false" });
  svg.appendChild(svgEl("path", { d: COOKIE_PATH }));
  return svg;
}

function cookie(cls, text) {
  return h("span", { class: cls }, cookieSvg("cookie-shape"), h("span", { class: "cookie-text", text }));
}

function avatar(student, index, size) {
  const el = cookie("cookie avatar avatar-" + size + " tone-" + (index % 4), initials(student && student.fullName));
  el.setAttribute("aria-hidden", "true");
  return el;
}

function skeleton(height) {
  const el = h("div", { class: "skeleton", "aria-hidden": "true" });
  el.style.height = height + "px";
  return el;
}

function emptyState(title, text) {
  return h("div", { class: "empty" }, h("p", { class: "empty-title", text: title }), text ? h("p", { class: "empty-text", text }) : null);
}

function errorPanel() {
  return h(
    "div",
    { class: "empty empty-error", role: "alert" },
    icon("error"),
    h("p", { class: "empty-title", text: "Datele nu au putut fi încărcate" }),
    h("p", { class: "empty-text", text: state.error }),
    h("button", { type: "button", class: "btn btn-tonal", on: { click: () => refresh({ force: false }) } }, "Încearcă din nou")
  );
}

function pending(height) {
  return state.error && !state.switching ? errorPanel() : skeleton(height);
}

function chip(text, tone, iconName) {
  if (text == null || text === false || Number.isNaN(text) || text === "") return null;
  return h("span", { class: "chip chip-" + (tone || "neutral") }, iconName ? icon(iconName) : null, text);
}

function bandChip(value) {
  if (value == null || Number.isNaN(Number(value))) return null;
  const band = bandOf(value);
  return h("span", { class: "chip chip-band " + band, text: BAND_LABELS[band] });
}

function textButton(label, onClick) {
  return h("button", { type: "button", class: "text-btn", on: { click: onClick } }, label);
}

const QUALIFIER_NAMES = {
  FB: "Foarte Bine",
  B: "Bine",
  S: "Suficient",
  I: "Insuficient",
};

// UNVERIFIED: Subject scale lookup based on maintainer description.
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

function scaleChip(scale) {
  if (scale === "qualifier") {
    return h("span", { class: "scale-chip scale-chip-qualifier", role: "note", "aria-label": "Sistem de evaluare: Calificative" }, (typeof getString !== "undefined" ? getString("scale_qualifiers") : null) || "Calificative");
  }
  if (scale === "descriptor") {
    return h("span", { class: "scale-chip scale-chip-descriptor", role: "note", "aria-label": "Sistem de evaluare: Descriptori" }, (typeof getString !== "undefined" ? getString("scale_descriptors") : null) || "Descriptori");
  }
  return null;
}

function emptyMarkText(scale) {
  if (scale === "qualifier") return (typeof getString !== "undefined" ? getString("no_qualifiers_yet") : null) || "Încă nu sunt calificative";
  if (scale === "descriptor") return (typeof getString !== "undefined" ? getString("no_descriptors_yet") : null) || "Încă nu sunt descriptori";
  return (typeof getString !== "undefined" ? getString("no_marks_yet") : null) || "Încă nu sunt note";
}

function score(valueOrMark, size, date, isThesis = false) {
  let val = valueOrMark;
  let labelText = "";
  let markDate = date;
  let markThesis = isThesis;
  let markIsNew = false;
  let isQual = false;
  let isOther = false;
  let markKind = "numeric";

  if (valueOrMark && typeof valueOrMark === "object") {
    val = valueOrMark.value;
    labelText = valueOrMark.label || (val != null ? String(val) : (valueOrMark.raw ? String(valueOrMark.raw) : ""));
    markDate = valueOrMark.date || date;
    markThesis = Boolean(valueOrMark.isThesis || isThesis);
    markIsNew = Boolean(valueOrMark.isNew);
    markKind = valueOrMark.kind || (val != null ? "numeric" : (QUALIFIER_NAMES[String(labelText).toUpperCase()] ? "qualifier" : "other"));
    isQual = markKind === "qualifier" || Boolean(QUALIFIER_NAMES[String(labelText).toUpperCase()]);
    isOther = markKind === "other" || markKind === "descriptor";
  } else if (typeof valueOrMark === "string" && QUALIFIER_NAMES[valueOrMark.toUpperCase()]) {
    isQual = true;
    markKind = "qualifier";
    labelText = valueOrMark.toUpperCase();
    val = null;
  } else {
    labelText = val != null ? fmtMark(val) : "";
    if (val == null && typeof valueOrMark === "string") {
      labelText = valueOrMark;
      isOther = true;
      markKind = "other";
    }
  }

  const band = bandOf(val, labelText);
  const qualFull = isQual ? (QUALIFIER_NAMES[String(labelText).toUpperCase()] || labelText) : "";
  const dateStr = markDate ? (longDate(markDate) || shortDate(markDate)) : "";

  let accessibleLabel;
  if (isQual) {
    accessibleLabel = qualFull + " (" + labelText + ")" + (dateStr ? " din " + dateStr : "");
  } else if (isOther) {
    accessibleLabel = labelText + (dateStr ? " (" + dateStr + ")" : "");
  } else {
    accessibleLabel = (markThesis ? "Teză: " : "Nota ") + labelText + (dateStr ? " (" + dateStr + ")" : "");
  }
  if (markIsNew) {
    accessibleLabel += " (Nou)";
  }

  return h(
    "span",
    {
      class: "score score-" + size + " " + band + (markThesis ? " is-thesis" : "") + (isQual ? " is-qualifier" : "") + (isOther ? " is-other" : "") + (markIsNew ? " has-new" : ""),
      role: "img",
      "aria-label": accessibleLabel,
      title: accessibleLabel,
    },
    markThesis ? h("span", { class: "thesis-tag", text: "Teză" }) : null,
    markIsNew ? h("span", { class: "badge-new", text: "Nou" }) : null,
    labelText
  );
}

function avgBadge(value) {
  const label = value == null ? "Fără medie" : "Media " + fmtAvg(value);
  return h("span", { class: "score score-md " + bandOf(value), role: "img", "aria-label": label, title: label }, fmtAvg(value));
}

function progress(pct, text) {
  const fill = h("span", { class: "progress-fill" });
  fill.style.setProperty("--p", pct + "%");
  const bar = h("div", { class: "progress", role: "progressbar", "aria-valuemin": "0", "aria-valuemax": "100", "aria-valuenow": String(pct) }, fill);
  return h("div", { class: "progress-wrap" }, bar, text ? h("p", { class: "progress-text", text }) : null);
}

function metaItem(name, text) {
  return h("span", { class: "meta-item" }, icon(name), h("span", { text }));
}

function lessonMeta(raw) {
  if (!raw) return null;
  const items = [];
  if (raw.classroom && raw.classroom !== "null" && raw.classroom !== "undefined") items.push(metaItem("room", raw.classroom));
  if (raw.teacher && raw.teacher !== "null" && raw.teacher !== "undefined") items.push(metaItem("person", raw.teacher));
  return items.length ? h("p", { class: "meta-row" }, items) : null;
}

let toastTimer = 0;

function toast(text) {
  const host = $("toast-host");
  host.replaceChildren(h("div", { class: "toast", role: "status" }, text));
  window.clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => host.replaceChildren(), 5000);
}

// network

async function apiFetch(path, init) {
  return fetch(path, init);
}

async function api(path, options = {}) {
  const headers = { Accept: "application/json" };
  if (state.token && options.auth !== false) headers.Authorization = "Bearer " + state.token;
  const init = { method: options.method || "GET", headers };
  if (options.body !== undefined) {
    headers["Content-Type"] = "application/json";
    init.body = JSON.stringify(options.body);
  }
  let res;
  try {
    res = await apiFetch(path, init);
  } catch {
    throw new ApiError("Serverul nu răspunde. Verifică conexiunea.", 0, 0);
  }
  if (res.status === 204) return null;
  let payload = null;
  try {
    payload = await res.json();
  } catch {
    payload = null;
  }
  if (!res.ok) {
    const errObj = payload && typeof payload.error === "object" ? payload.error : null;
    const code = errObj?.code || null;
    const message = errObj?.message || (typeof payload?.error === "string" ? payload.error : "Cerere eșuată");
    const wait = Number(errObj?.retryAfter || payload?.waitSeconds) || 0;
    if (code === "APP_SESSION_INVALID" || (res.status === 401 && options.auth !== false && path !== "/api/auth/login")) {
      endSession("Sesiunea a expirat. Autentifică-te din nou.");
    }
    throw new ApiError(message, res.status, wait, code);
  }
  return payload;
}

function describeError(err, context) {
  if (!(err instanceof ApiError)) return "A apărut o eroare neașteptată.";
  if (err.code) {
    switch (err.code) {
      case "APP_SESSION_INVALID":
        return "Sesiunea a expirat. Autentifică-te din nou.";
      case "PORTAL_LOGIN_REJECTED":
        return "Numele de utilizator sau parola sunt incorecte.";
      case "PORTAL_UNEXPECTED_PAGE":
        return err.message || "Portalul a returnat o pagină neașteptată.";
      case "PORTAL_FORBIDDEN":
        return "Acces interzis de portalul școlii (posibil blocare de securitate).";
      case "PORTAL_NOT_FOUND":
        return "Pagina solicitată nu a fost găsită pe portalul școlii.";
      case "PORTAL_THROTTLED":
        return "Portalul limitează cererile. Reîncearcă peste " + waitText(err.wait || 60) + ".";
      case "PORTAL_MAINTENANCE":
        return "Portalul școlii este în mentenanță sau temporar indisponibil.";
      case "STUDENT_NOT_FOUND":
        return "Elevul nu a fost găsit.";
      default:
        return err.message;
    }
  }
  switch (err.status) {
    case 0:
      return "Serverul nu răspunde. Verifică conexiunea și încearcă din nou.";
    case 400:
      return "Verifică datele introduse și încearcă din nou.";
    case 401:
      return context === "login" ? "Numele de utilizator sau parola sunt incorecte." : "Sesiunea a expirat. Autentifică-te din nou.";
    case 404:
      return "Elevul selectat nu a fost găsit.";
    case 429:
      return "Portalul limitează cererile. Reîncearcă peste " + waitText(err.wait || 60) + ".";
    case 502:
      return "Portalul școlii nu a putut fi contactat. Reîncearcă mai târziu.";
    default:
      return err.message || "Eroare de server. Reîncearcă mai târziu.";
  }
}

// data model

function markKey(subject, m) {
  const s = String(subject || "").trim().toLowerCase();
  const d = String(m?.date || "").trim();
  const v = m?.value ?? "";
  const l = String(m?.label || "").trim();
  const t = m?.isThesis ? "t" : "";
  const sem = m?.semester || "";
  return `${s}:${sem}:${d}:${v}:${l}:${t}`;
}

function diffNewMarks(currentCatalog, prevCatalog) {
  if (!Array.isArray(currentCatalog)) return;
  if (!Array.isArray(prevCatalog) || !prevCatalog.length) {
    for (const subj of currentCatalog) {
      for (const sem of (subj.semesters || [])) {
        for (const mk of (sem.marks || [])) {
          mk.isNew = false;
        }
      }
    }
    return;
  }
  const prevKeys = new Set();
  for (const subj of prevCatalog) {
    const sName = subj.subject || subj.discipline || "";
    for (const sem of (subj.semesters || [])) {
      for (const mk of (sem.marks || [])) {
        prevKeys.add(markKey(sName, mk));
      }
    }
  }
  for (const subj of currentCatalog) {
    const sName = subj.subject || subj.discipline || "";
    for (const sem of (subj.semesters || [])) {
      for (const mk of (sem.marks || [])) {
        const k = markKey(sName, mk);
        mk.isNew = !prevKeys.has(k);
      }
    }
  }
}

function setData(data, syncedAt) {
  const prevData = state.data;
  const newStudentKey = data.studentKey || (data.student && data.student.id) || state.studentKey || "";
  const sameStudent = Boolean(
    state.studentKey &&
    newStudentKey &&
    canonKey(state.studentKey) === canonKey(newStudentKey)
  );

  if (sameStudent && prevData) {
    if ((!data.teachers || data.teachers.length <= 1) && Array.isArray(prevData.teachers) && prevData.teachers.length > 1) {
      data.teachers = prevData.teachers;
    }
    if ((!data.absences || !data.absences.detail) && prevData.absences?.detail) {
      data.absences = prevData.absences;
      data.absente = prevData.absente;
    }
    if ((!data.catalog || !data.catalog.length) && Array.isArray(prevData.catalog) && prevData.catalog.length) {
      data.catalog = prevData.catalog;
      data.reusita = prevData.reusita;
    }
    if ((!data.schedule || !data.schedule.days || !Object.keys(data.schedule.days).length) && prevData.schedule?.days) {
      data.schedule = prevData.schedule;
      data.orar = prevData.orar;
    }
    if ((!data.homework || !data.homework.length) && Array.isArray(prevData.homework) && prevData.homework.length) {
      data.homework = prevData.homework;
    }
  }

  state.data = data;
  state.studentKey = newStudentKey;
  const list = Array.isArray(data.students) ? data.students.filter((s) => s && s.id) : [];
  if (data.student && data.student.id && !list.some((s) => s.id === data.student.id)) list.unshift(data.student);
  if (list.length) {
    const merged = list.map((s) => {
      const prev = state.students.find((x) => x.id === s.id);
      return {
        ...s,
        className: s.className || prev?.className || null,
        schoolName: s.schoolName || prev?.schoolName || null,
        fullName: s.fullName || prev?.fullName || s.id,
      };
    });
    state.students = merged;
  }
  state.syncedAt = syncedAt || Date.now();
  if (!state.semesterTouched) state.semester = defaultSemester(data);

  const studentId = state.studentKey;
  if (studentId && Array.isArray(data.catalog) && data.catalog.length) {
    const marksKey = "open-sime.prev-marks." + studentId;
    let prevCatalog = null;
    try {
      const prev = store.get(marksKey);
      if (prev) prevCatalog = JSON.parse(prev);
    } catch {
      prevCatalog = null;
    }
    diffNewMarks(data.catalog, prevCatalog);
    try {
      store.set(marksKey, JSON.stringify(data.catalog));
    } catch {}
  }

  persist();
}

function persist() {
  if (!state.data) return;
  const key = state.studentKey ? `${KEYS.data}.${state.studentKey}` : KEYS.data;
  store.set(key, JSON.stringify(state.data));
  store.set(KEYS.sync, String(state.syncedAt));
  if (state.studentKey) store.set(KEYS.student, state.studentKey);
}

function loadCached(targetKey) {
  const k = targetKey || state.studentKey || store.get(KEYS.student);
  const raw = (k && store.get(`${KEYS.data}.${k}`)) || store.get(KEYS.data);
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === "object" ? parsed : null;
  } catch {
    return null;
  }
}

function defaultSemester(data) {
  const meta = data && data.metadata;
  const current = meta && num(meta.currentSemester);
  if (current === 1 || current === 2) return current;
  return Number(todayIso().slice(5, 7)) >= 9 ? 1 : 2;
}

let modelSource = null;
let modelValue = null;

function model() {
  const data = state.data;
  if (!data) return null;
  if (modelSource === data) return modelValue;
  const reusita = data.reusita || data.catalog || {};
  const schedule = data.schedule || data.orar || {};
  const subjects = Array.isArray(reusita.subjects) ? reusita.subjects : Array.isArray(reusita.disciplines) ? reusita.disciplines : [];
  const absences = data.absences || data.absente || {};
  modelSource = data;
  modelValue = {
    student: data.student || {},
    metadata: data.metadata || {},
    subjects,
    averages: {
      general: num(data.generalAverage) ?? num(reusita.generalAverage) ?? num(reusita.annualGeneralAverage),
      annual: num(reusita.annualGeneralAverage) ?? num(data.generalAverage),
      sem1: num(reusita.sem1GeneralAverage),
      sem2: num(reusita.sem2GeneralAverage),
    },
    grades: Array.isArray(data.grades) ? data.grades : [],
    absences,
    absenceSummary: data.absenceSummary || absences.summary || {},
    days: schedule.days && typeof schedule.days === "object" ? schedule.days : {},
    homework: Array.isArray(data.homework) ? data.homework : [],
    teachers: Array.isArray(data.teachers) ? data.teachers : [],
  };
  return modelValue;
}

function recentMarks(m) {
  const list = [];
  for (const g of m.grades) {
    if (!g) continue;
    const value = num(g.value);
    const label = g.label || (value != null ? String(value) : (g.raw != null ? String(g.raw) : ""));
    const isQual = g.kind === "qualifier" || Boolean(QUALIFIER_NAMES[String(label).toUpperCase()]);
    const isOther = g.kind === "other" || g.kind === "descriptor" || (value == null && !isQual && Boolean(label));
    if (value == null && !isQual && !isOther) continue;
    list.push({
      subject: String(g.subject || ""),
      value,
      label,
      raw: g.raw || label,
      kind: isQual ? "qualifier" : (isOther ? (g.kind || "other") : "numeric"),
      isThesis: Boolean(g.isThesis),
      date: validIso(g.date) ? g.date : null,
      i: list.length,
    });
  }
  if (!list.length) {
    for (const s of m.subjects) {
      for (const d of Array.isArray(s.markDetails) ? s.markDetails : []) {
        if (!d) continue;
        const value = num(d.value);
        const label = d.label || (value != null ? String(value) : (d.raw != null ? String(d.raw) : ""));
        const isQual = d.kind === "qualifier" || Boolean(QUALIFIER_NAMES[String(label).toUpperCase()]);
        const isOther = d.kind === "other" || d.kind === "descriptor" || (value == null && !isQual && Boolean(label));
        if (value == null && !isQual && !isOther) continue;
        if (validIso(d.date)) {
          list.push({
            subject: String(s.subject || ""),
            value,
            label,
            raw: d.raw || label,
            kind: isQual ? "qualifier" : (isOther ? (d.kind || "other") : "numeric"),
            isThesis: Boolean(d.isThesis),
            date: d.date,
            i: list.length,
          });
        }
      }
    }
  }
  list.sort((a, b) => (b.date || "").localeCompare(a.date || "") || a.i - b.i);
  return list;
}

function sortHomework(list, today) {
  const items = list.map((t, i) => ({
    subject: String((t && t.subject) || ""),
    text: String((t && t.taskDescription) || ""),
    due: t && validIso(t.dueDate) ? t.dueDate : null,
    i,
  }));
  const rank = (t) => (t.due == null ? 2 : t.due >= today ? 0 : 1);
  items.sort((a, b) => {
    const ra = rank(a);
    const rb = rank(b);
    if (ra !== rb) return ra - rb;
    if (ra === 0) return a.due.localeCompare(b.due) || a.i - b.i;
    if (ra === 1) return b.due.localeCompare(a.due) || a.i - b.i;
    return a.i - b.i;
  });
  return items;
}

function markGroups(subject) {
  let details = Array.isArray(subject.markDetails) ? subject.markDetails : [];
  if (!details.length && Array.isArray(subject.semesters)) {
    details = [];
    for (const sem of subject.semesters) {
      for (const mk of Array.isArray(sem.marks) ? sem.marks : []) {
        details.push({
          value: mk.value,
          label: mk.label,
          raw: mk.raw,
          kind: mk.kind,
          date: mk.date,
          isThesis: Boolean(mk.isThesis),
          semester: sem.semester,
        });
      }
    }
  }
  if (!details.length && Array.isArray(subject.marks)) {
    details = subject.marks.map((value) => ({
      value: typeof value === "number" ? value : null,
      label: String(value ?? ""),
      raw: String(value ?? ""),
      kind: typeof value === "number" ? "numeric" : (QUALIFIER_NAMES[String(value).toUpperCase()] ? "qualifier" : "other"),
      date: null,
      isThesis: false,
      semester: 0,
    }));
  }
  const groups = new Map();
  for (const d of details) {
    if (!d) continue;
    const value = num(d.value);
    const label = d.label || (value != null ? String(value) : (d.raw != null ? String(d.raw) : ""));
    const kind = d.kind || (value != null ? "numeric" : (QUALIFIER_NAMES[String(label).toUpperCase()] ? "qualifier" : "other"));
    if (value == null && !label) continue;
    const key = d.semester === 1 || d.semester === 2 ? d.semester : 0;
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push({
      value,
      label,
      raw: d.raw || label,
      kind,
      date: validIso(d.date) ? d.date : null,
      isThesis: Boolean(d.isThesis),
    });
  }
  const out = [];
  for (const key of [1, 2, 0]) {
    const marks = groups.get(key);
    if (!marks) continue;
    marks.sort((a, b) => (a.date || "").localeCompare(b.date || ""));
    out.push({ semester: key, marks });
  }
  return out;
}

function absenceSemester(m, n) {
  const a = m.absences;
  const block = (n === 2 ? a.sem2 : a.sem1) || {};
  const all = Array.isArray(a.records) ? a.records : [];
  const records = Array.isArray(block.records) ? block.records : all.filter((r) => r.semester === n);
  const isExc = (r) => r.status === "excused" || r.status === "motivat";
  const isUnexc = (r) => r.status === "unexcused" || r.status === "nemotivat";
  const motivate = num(block.motivate) ?? records.filter(isExc).length;
  const nemotivate = num(block.nemotivate) ?? records.filter(isUnexc).length;
  const total = num(block.total) ?? Math.max(records.length, motivate + nemotivate);
  return { records, motivate, nemotivate, total };
}

function lessonList(entries, now) {
  const out = [];
  for (const raw of Array.isArray(entries) ? entries : []) {
    if (!raw) continue;
    const start = Date.parse(raw.start);
    const end = Date.parse(raw.end);
    if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) continue;
    out.push({ raw, start, end, state: now >= end ? "past" : now >= start ? "active" : "upcoming" });
  }
  out.sort((a, b) => a.start - b.start);
  return out;
}

function weekDays() {
  const monday = mondayOf(todayIso());
  return [0, 1, 2, 3, 4].map((i) => addDays(monday, i));
}

function ensureDay() {
  const days = weekDays();
  if (state.day && days.includes(state.day)) return;
  const today = todayIso();
  state.day = days.includes(today) ? today : days[0];
}

// rendering: home

function renderHome() {
  const m = model();
  renderLive();
  renderMetrics(m);
  renderRecent(m);
  renderHomeworkPreview(m);
}

function renderMetrics(m) {
  const host = $("home-metrics");
  if (!m) {
    host.replaceChildren(pending(160));
    return;
  }
  const gpa = m.averages.general;
  const gpaCard = h(
    "article",
    { class: "metric metric-gpa" },
    h("span", { class: "metric-deco", "aria-hidden": "true" }, cookieSvg("deco-shape")),
    h("span", { class: "metric-label", text: getString("general_average_estimated", {}, state.lang || "ro") || "Media generală (estimată)" }),
    h("p", { class: "metric-value", text: fmtAvg(gpa) }),
    h(
      "div",
      { class: "chip-row" },
      gpa != null ? bandChip(gpa) : null,
      m.averages.sem1 != null ? chip("Sem. 1: " + fmtAvg(m.averages.sem1), "neutral") : null,
      m.averages.sem2 != null ? chip("Sem. 2: " + fmtAvg(m.averages.sem2), "neutral") : null
    )
  );

  const sum = m.absenceSummary;
  const excused = num(sum.totalExcused) ?? 0;
  const unexcused = num(sum.totalUnexcused) ?? 0;
  const total = num(sum.total) ?? excused + unexcused;
  const absBody = [];
  if (excused + unexcused > 0) {
    const okBar = h("span", { class: "split-ok" });
    const badBar = h("span", { class: "split-bad" });
    okBar.style.flexGrow = String(excused);
    badBar.style.flexGrow = String(unexcused);
    absBody.push(h("div", { class: "split-bar", "aria-hidden": "true" }, okBar, badBar));
    absBody.push(h("p", { class: "legend" }, h("span", { text: "Motivate " + excused }), h("span", { class: "legend-bad", text: "Nemotivate " + unexcused })));
  }
  const absCard = h(
    "article",
    { class: "metric metric-abs" },
    h("span", { class: "metric-label", text: "Absențe" }),
    h("p", { class: "metric-value", text: String(total) }),
    h("p", { class: "metric-note", text: total === 0 ? "Nicio absență în acest an" : "în acest an școlar" }),
    absBody
  );

  const last = recentMarks(m)[0];
  const lastCard = h(
    "article",
    { class: "metric metric-last" },
    h("span", { class: "metric-label", text: "Ultima notă" }),
    last
      ? h(
          "div",
          { class: "last-row" },
          cookie(
            "cookie cookie-lg " + bandOf(last.value, last.label),
            last.kind === "qualifier" || last.value == null ? (last.label || "") : fmtMark(last.value)
          ),
          h("div", { class: "last-text" }, h("p", { class: "last-subject", text: last.subject }), last.date ? h("p", { class: "last-date", text: shortDate(last.date) }) : null)
        )
      : h("p", { class: "metric-note", text: "Încă nu există note." })
  );

  setChildren(host, gpaCard, absCard, lastCard);
}

function dayTrack(lessons, now) {
  const track = h("div", { class: "track", "aria-hidden": "true" });
  for (const l of lessons) {
    const pct = l.state === "past" ? 100 : l.state === "active" ? Math.round(((now - l.start) / (l.end - l.start)) * 100) : 0;
    const fill = h("span", { class: "track-fill" });
    fill.style.setProperty("--p", pct + "%");
    const seg = h("span", { class: "track-seg" }, fill);
    seg.style.flexGrow = String(Math.max(1, Math.round((l.end - l.start) / 60000)));
    track.appendChild(seg);
  }
  return track;
}

function renderLive() {
  const host = $("home-live");
  const m = model();
  if (!m) {
    host.replaceChildren(pending(300));
    return;
  }
  const now = Date.now();
  const today = todayIso(now);
  const lessons = lessonList(m.days[today], now);
  const active = lessons.find((l) => l.state === "active");
  const next = lessons.find((l) => l.state === "upcoming");
  const anyPast = lessons.some((l) => l.state === "past");
  let hero;
  let nextRow = null;

  if (active) {
    const r = active.raw;
    const left = Math.max(1, Math.ceil((active.end - now) / 60000));
    const pct = Math.min(100, Math.max(0, Math.round(((now - active.start) / (active.end - active.start)) * 100)));
    hero = h(
      "div",
      { class: "live-hero is-active" },
      h("div", { class: "live-row" }, chip("În desfășurare", "live"), h("span", { class: "live-range", text: r.startLocal + " - " + r.endLocal })),
      h("h3", { class: "live-title", text: r.subject || "Lecție" }),
      lessonMeta(r),
      progress(pct, "Mai sunt " + minutesText(left))
    );
    if (next) {
      const until = Math.max(1, Math.ceil((next.start - now) / 60000));
      nextRow = h(
        "div",
        { class: "next-row" },
        chip("Urmează", "next"),
        h("span", { class: "next-text", text: next.raw.subject || "Lecție" }),
        h("span", { class: "next-when", text: next.raw.startLocal + ", în " + minutesText(until) })
      );
    }
  } else if (next) {
    const until = Math.max(1, Math.ceil((next.start - now) / 60000));
    hero = h(
      "div",
      { class: "live-hero is-break" },
      h("div", { class: "live-row" }, chip(anyPast ? "Pauză" : "Urmează", "next"), h("span", { class: "live-range", text: next.raw.startLocal + " - " + next.raw.endLocal })),
      h("h3", { class: "live-title", text: next.raw.subject || "Lecție" }),
      lessonMeta(next.raw),
      h("p", { class: "live-note", text: (anyPast ? "Următoarea oră începe" : "Prima oră începe") + " în " + minutesText(until) })
    );
  } else if (lessons.length) {
    const lastLesson = lessons[lessons.length - 1];
    hero = h(
      "div",
      { class: "live-hero" },
      h("div", { class: "live-row" }, chip("Încheiat", "neutral")),
      h("h3", { class: "live-title", text: "Orele de azi s-au terminat" }),
      h("p", { class: "live-note", text: "Ultima oră: " + (lastLesson.raw.subject || "Lecție") + ", până la " + lastLesson.raw.endLocal })
    );
  } else {
    const weekend = weekdayIndex(today) > 4;
    hero = h(
      "div",
      { class: "live-hero" },
      h("div", { class: "live-row" }, chip(weekend ? "Weekend" : "Liber", "neutral")),
      h("h3", { class: "live-title", text: "Nicio oră în orar azi" }),
      h("p", { class: "live-note", text: weekend ? "Orarul continuă luni." : "Pentru astăzi nu sunt lecții programate." })
    );
  }

  const children = [
    h("div", { class: "card-head" }, h("h2", { class: "card-title", text: "Orele de azi" }), h("span", { class: "card-sub", text: longDate(today) })),
    hero,
    nextRow,
  ];
  if (lessons.length) {
    children.push(dayTrack(lessons, now));
    children.push(
      h(
        "ul",
        { class: "mini-list" },
        lessons.map((l) => {
          const isNext = next === l;
          return h(
            "li",
            { class: "mini-item is-" + l.state + (isNext ? " is-next" : "") },
            h("span", { class: "mini-time", text: l.raw.startLocal }),
            h("span", { text: l.raw.subject || "Lecție" }),
            l.raw.classroom ? h("span", { class: "mini-room", text: l.raw.classroom }) : null
          );
        })
      )
    );
  }
  setChildren(host, children);
}

function renderRecent(m) {
  const host = $("home-marks");
  if (!m) {
    setChildren(host, pending(220));
    return;
  }
  const rows = recentMarks(m).slice(0, 5);
  setChildren(
    host,
    h("div", { class: "card-head" }, h("h2", { class: "card-title", text: "Note recente" }), textButton("Toate notele", () => goTab("marks"))),
    rows.length
      ? h(
          "ul",
          { class: "recent-list" },
          rows.map((r) =>
            h(
              "li",
              { class: "recent-item" },
              score(r, "md", r.date),
              h("div", { class: "recent-text" }, h("p", { class: "recent-subject", text: r.subject || "Disciplină" }), r.date ? h("p", { class: "recent-date", text: longDate(r.date) }) : null)
            )
          )
        )
      : emptyState("Nicio notă încă", "Notele noi apar aici după sincronizare.")
  );
}

function taskRow(task, today, compact) {
  let due = null;
  if (task.due) {
    const diff = dayDiff(today, task.due);
    due = chip(relativeDay(task.due, today) + ", " + shortDate(task.due), diff < 0 ? "neutral" : diff <= 1 ? "warn" : "info");
  }
  return h(
    "li",
    { class: "task" + (compact ? " task-compact" : "") },
    h("div", { class: "task-top" }, h("span", { class: "task-subject", text: task.subject || "Disciplină" }), due),
    task.text ? h("p", { class: "task-desc", text: task.text }) : null
  );
}

function renderHomeworkPreview(m) {
  const host = $("home-homework");
  if (!m) {
    setChildren(host, pending(200));
    return;
  }
  const today = todayIso();
  const items = sortHomework(m.homework, today).slice(0, 4);
  setChildren(
    host,
    h("div", { class: "card-head" }, h("h2", { class: "card-title", text: "Teme și termene" }), textButton("Toate temele", () => goTab("homework"))),
    items.length ? h("ul", { class: "task-list" }, items.map((t) => taskRow(t, today, true))) : emptyState("Nicio temă", "Nu ai teme de făcut acum.")
  );
}

// rendering: marks

function stat(label, value, variant) {
  const safeVal = value == null || Number.isNaN(Number(value)) ? "0" : String(value);
  return h("div", { class: "stat stat-" + (variant || "neutral") }, h("span", { class: "stat-label", text: label }), h("span", { class: "stat-value", text: safeVal }));
}

function avgTile(label, value) {
  return h("div", { class: "avg-tile" }, h("span", { class: "avg-label", text: label }), avgBadge(value));
}

function annualTile(value) {
  const el = cookie("cookie cookie-md " + bandOf(value), fmtAvg(value));
  el.setAttribute("role", "img");
  el.setAttribute("aria-label", value == null ? "Fără medie anuală" : "Media anuală " + fmtAvg(value));
  return h("div", { class: "avg-tile avg-annual" }, h("span", { class: "avg-label", text: "Media anuală" }), el);
}

function markGroupBlock(group) {
  const title = group.semester === 1 ? "Semestrul 1" : group.semester === 2 ? "Semestrul 2" : "Note";
  return h(
    "section",
    { class: "mark-group" },
    h("h4", { class: "mark-group-title", text: title }),
    h(
      "ul",
      { class: "marks" },
      group.marks.map((mk) =>
        h(
          "li",
          { class: "mark" },
          score(mk, "md", mk.date, mk.isThesis),
          mk.date ? h("time", { class: "mark-date", datetime: mk.date, text: shortDate(mk.date) }) : null
        )
      )
    )
  );
}

function subjectCard(s) {
  if (!s) return null;
  const groups = markGroups(s);
  const teacher = s.teacher && s.teacher !== "null" && s.teacher !== "undefined" ? s.teacher : null;
  const scale = s.scale || getSubjectScale(s.subject || s.discipline);
  return h(
    "article",
    { class: "subject" },
    h(
      "header",
      null,
      h(
        "div",
        { class: "subject-title-row" },
        h("h3", { class: "subject-name", text: toTitleCase(s.subject || s.discipline || "Disciplină") }),
        scaleChip(scale)
      ),
      teacher ? h("p", { class: "subject-teacher", text: teacher }) : null
    ),
    groups.length ? h("div", { class: "mark-groups" }, groups.map(markGroupBlock)) : h("p", { class: "muted", text: emptyMarkText(scale) })
  );
}

function marksCell(content, cls) { return h("td", { class: cls || "" }, content); }
function renderMarks() {
  const summary = $("marks-summary");
  const list = $("marks-list");
  const m = model();
  if (!m) {
    if (summary) setChildren(summary);
    setChildren(list, pending(260));
    return;
  }
  // Remove all averages from Reușita tab
  if (summary) setChildren(summary);

  if (!m.subjects.length) {
    setChildren(list, emptyState("Nicio disciplină încă", "Notele apar aici după sincronizarea cu portalul."));
    return;
  }

  const head = h(
    "thead",
    null,
    h(
      "tr",
      null,
      h("th", { scope: "col", text: "Disciplină" }),
      h("th", { scope: "col", text: "Semestrul 1: Note" }),
      h("th", { scope: "col", text: "Semestrul 2: Note" })
    )
  );

  const rows = m.subjects.map((subject) => {
    const groups = markGroups(subject);
    const sem = (n) => groups.find((g) => g.semester === n);
    const scale = subject.scale || getSubjectScale(subject.subject || subject.discipline);
    const pills = (g) => {
      if (g && g.marks.length) {
        return h(
          "div",
          { class: "grade-pills" },
          g.marks.map((mk) => score(mk, "md", mk.date, mk.isThesis))
        );
      }
      const emptyText = emptyMarkText(scale);
      return h("span", { class: "muted empty-mark-dash", title: emptyText, "aria-label": emptyText, text: "—" });
    };

    return h(
      "tr",
      null,
      marksCell(
        h(
          "div",
          null,
          h(
            "div",
            { class: "subject-title-row" },
            h("strong", { class: "subject-name", text: toTitleCase(subject.subject || subject.discipline || "Disciplină") }),
            scaleChip(scale)
          ),
          subject.teacher && subject.teacher !== "null" && subject.teacher !== "undefined" ? h("div", { class: "subject-teacher muted", text: subject.teacher }) : null
        )
      ),
      marksCell(pills(sem(1))),
      marksCell(pills(sem(2)))
    );
  });

  const tableSurface = h(
    "div",
    { class: "marks-table-surface" },
    h("table", { class: "marks-table" }, head, h("tbody", null, rows))
  );

  const cardsSurface = h(
    "div",
    { class: "marks-cards-surface" },
    m.subjects.map(subjectCard)
  );

  setChildren(list, tableSurface, cardsSurface);
}

// rendering: timetable

const dayUi = { prev: null, next: null, tabs: new Map() };

function buildDaySwitch() {
  ensureDay();
  const days = weekDays();
  const today = todayIso();
  dayUi.tabs.clear();
  dayUi.prev = h("button", { type: "button", class: "icon-btn tonal day-nav", "aria-label": "Ziua anterioară", on: { click: () => stepDay(-1) } }, icon("chevron_left"));
  dayUi.next = h("button", { type: "button", class: "icon-btn tonal day-nav", "aria-label": "Ziua următoare", on: { click: () => stepDay(1) } }, icon("chevron_right"));
  const tabs = days.map((iso, i) => {
    const btn = h(
      "button",
      { type: "button", class: "day-tab" + (iso === today ? " is-today" : ""), "aria-label": longDate(iso), on: { click: () => selectDay(iso) } },
      h("span", { class: "day-name", text: WEEKDAYS_SHORT[i] }),
      h("span", { class: "day-num", text: String(dayNum(iso)) })
    );
    dayUi.tabs.set(iso, btn);
    return btn;
  });
  $("day-switch").replaceChildren(dayUi.prev, h("div", { class: "day-tabs" }, tabs), dayUi.next);
  updateDaySwitch();
}

function updateDaySwitch() {
  const days = weekDays();
  const idx = days.indexOf(state.day);
  for (const [iso, btn] of dayUi.tabs) {
    const selected = iso === state.day;
    btn.classList.toggle("is-selected", selected);
    btn.setAttribute("aria-pressed", String(selected));
  }
  if (dayUi.prev) dayUi.prev.disabled = idx <= 0;
  if (dayUi.next) dayUi.next.disabled = idx >= days.length - 1;
}

function selectDay(iso) {
  state.day = iso;
  updateDaySwitch();
  renderLessons();
  if (state.tab === "timetable" && state.token) {
    loadTabData("timetable");
  }
}

function stepDay(delta) {
  const days = weekDays();
  const idx = days.indexOf(state.day) + delta;
  if (idx >= 0 && idx < days.length) selectDay(days[idx]);
}

function lessonCard(l, isNext) {
  const r = l.raw;
  const now = Date.now();
  let badge = null;
  if (l.state === "active") badge = chip("În desfășurare", "live");
  else if (isNext) badge = chip("Urmează", "next");
  else if (l.state === "past") badge = chip("Încheiată", "quiet");
  const body = [
    h("div", { class: "lesson-head" }, h("h3", { class: "lesson-name", text: r.subject || "Lecție" }), badge),
    lessonMeta(r),
    r.topic ? h("p", { class: "lesson-topic", text: r.topic }) : null,
  ];
  if (l.state === "active") {
    const pct = Math.min(100, Math.max(0, Math.round(((now - l.start) / (l.end - l.start)) * 100)));
    body.push(progress(pct, "Mai sunt " + minutesText(Math.max(1, Math.ceil((l.end - now) / 60000)))));
  }
  return h(
    "li",
    { class: "lesson is-" + l.state + (isNext ? " is-next" : ""), "aria-current": l.state === "active" ? "true" : null },
    h(
      "div",
      { class: "lesson-time" },
      h("span", { class: "lesson-start", text: r.startLocal }),
      h("span", { class: "lesson-end", text: r.endLocal }),
      r.periodIndex ? h("span", { class: "lesson-index", text: "Ora " + r.periodIndex }) : null
    ),
    h("div", { class: "lesson-body" }, body)
  );
}

function renderLessons() {
  ensureDay();
  const host = $("lesson-list");
  const heading = $("day-heading");
  const m = model();
  if (!m) {
    setChildren(heading);
    setChildren(host, pending(300));
    return;
  }
  const now = Date.now();
  const today = todayIso(now);
  const lessons = lessonList(m.days[state.day], now);
  setChildren(
    heading,
    h("h2", { class: "day-title", text: longDate(state.day) }),
    state.day === today ? chip("Astăzi", "info") : null,
    lessons.length ? chip(lessons.length + (lessons.length === 1 ? " oră" : " ore"), "neutral") : null
  );
  if (!lessons.length) {
    setChildren(host, emptyState("Nicio oră în orar", "Pentru această zi nu există lecții programate."));
    return;
  }
  const nextIdx = state.day === today ? lessons.findIndex((l) => l.state === "upcoming") : -1;
  const nodes = [];
  lessons.forEach((l, i) => {
    if (i > 0) {
      const gap = Math.round((l.start - lessons[i - 1].end) / 60000);
      if (gap >= 5) nodes.push(h("li", { class: "break", text: "Pauză " + minutesText(gap) }));
    }
    nodes.push(lessonCard(l, i === nextIdx));
  });
  setChildren(host, h("ol", { class: "lesson-ol" }, nodes));
}

// rendering: absences

function buildAbsToggle() {
  const host = $("abs-toggle");
  const buttons = [1, 2].map((n) =>
    h(
      "button",
      {
        type: "button",
        class: "seg-btn",
        "data-sem": String(n),
        "aria-pressed": String(state.semester === n),
        on: {
          click: () => {
            state.semester = n;
            state.semesterTouched = true;
            for (const b of host.querySelectorAll(".seg-btn")) b.setAttribute("aria-pressed", String(b.dataset.sem === String(n)));
            renderAbsences();
          },
        },
      },
      "Semestrul " + n
    )
  );
  host.replaceChildren(...buttons);
}

function statusChip(status) {
  if (status === "excused" || status === "motivat") return chip("Motivată", "success");
  if (status === "unexcused" || status === "nemotivat") return chip("Nemotivată", "error");
  return chip("Neclasificată", "neutral");
}

function absenceRow(r) {
  if (!r) return null;
  const valid = validIso(r.date);
  const time = [r.startTime || r.startLocal, r.endTime || r.endLocal].filter((x) => Boolean(x) && x !== "null" && x !== "undefined").join(" - ");
  const lessonOra = r.lessonNumber ? ((typeof getString !== "undefined" ? getString("lesson_period", { period: r.lessonNumber }) : null) || "Ora " + r.lessonNumber) : "";
  const roomText = r.room ? (String(r.room).startsWith("Sala") ? String(r.room) : `Sala ${r.room}`) : null;
  const teacher = r.teacher && r.teacher !== "null" && r.teacher !== "undefined" ? r.teacher : null;
  const metaParts = [lessonOra, time, roomText, teacher].filter(Boolean);
  const note = r.reason || r.note;
  const safeNote = note && note !== "null" && note !== "undefined" ? note : null;

  return h(
    "li",
    { class: "absence" },
    h("div", { class: "abs-date" }, h("span", { class: "abs-day", text: valid ? String(dayNum(r.date)) : "-" }), h("span", { class: "abs-month", text: valid ? (MONTHS_SHORT[monthIdx(r.date)] || "") : "" })),
    h(
      "div",
      { class: "abs-body" },
      h("p", { class: "abs-subject", text: r.subject || r.discipline || "Disciplină" }),
      metaParts.length ? h("p", { class: "abs-meta", text: metaParts.join(", ") }) : null,
      safeNote ? h("p", { class: "abs-note", text: safeNote }) : null
    ),
    statusChip(r.status)
  );
}

function renderAbsences() {
  const stats = $("abs-stats");
  const list = $("abs-list");
  const m = model();
  if (!m) {
    setChildren(stats);
    setChildren(list, pending(240));
    return;
  }
  const sem = absenceSemester(m, state.semester);
  setChildren(
    stats,
    stat("Total", String(sem.total), "primary"),
    stat("Motivate", String(sem.motivate), "success"),
    stat("Nemotivate", String(sem.nemotivate), "error")
  );

  if (!sem.records.length) {
    setChildren(
      list,
      sem.total > 0
        ? emptyState(
            getString("absences_no_details_title") || "Detaliile nu sunt disponibile pe portal",
            getString("absences_no_details_sub", { total: sem.total }) ||
              ("Portalul raportează " + sem.total + " absențe în acest semestru, dar fără detalii pe ore.")
          )
        : emptyState(
            getString("no_absences_title") || "Nicio absență",
            getString("no_absences_sub", { sem: state.semester }) ||
              ("Nu sunt absențe înregistrate în Semestrul " + state.semester + ".")
          )
    );
    return;
  }

  const sections = [];

  // Reconciliation note if portal summary exceeds detailed records
  const missingCount = sem.total - sem.records.length;
  if (missingCount > 0) {
    sections.push(
      h(
        "div",
        { class: "abs-reconciliation-note", role: "note" },
        icon("info"),
        h("p", {
          text: "Portalul raportează " + sem.total + " absențe în Semestrul " + state.semester + ", dintre care " + sem.records.length + " au detalii pe ore. Pentru restul de " + missingCount + (missingCount === 1 ? " absență" : " absențe") + ", detaliile nu sunt publicate în orar."
        })
      )
    );
  }

  // Per-subject summary (collapsible dates), sorted with most absences first
  const bySubject = new Map();
  for (const r of sem.records) {
    const subj = r.subject || r.discipline || "Disciplină";
    if (!bySubject.has(subj)) bySubject.set(subj, []);
    bySubject.get(subj).push(r);
  }

  if (bySubject.size > 0) {
    const sortedSubjects = Array.from(bySubject.entries()).sort((a, b) => b[1].length - a[1].length);
    const isUnexc = (r) => r.status === "unexcused" || r.status === "nemotivat";
    const isExc = (r) => r.status === "excused" || r.status === "motivat";

    const subjectCards = sortedSubjects.map(([subj, records]) => {
      const motCount = records.filter(isExc).length;
      const nemotCount = records.filter(isUnexc).length;
      return h(
        "details",
        { class: "abs-subject-card" },
        h(
          "summary",
          { class: "abs-subject-summary" },
          h(
            "div",
            { class: "abs-subject-header" },
            h("span", { class: "abs-subject-title", text: toTitleCase(subj) }),
            h(
              "div",
              { class: "abs-subject-badges" },
              chip(records.length + (records.length === 1 ? " absență" : " absențe"), "neutral"),
              nemotCount ? chip(nemotCount + " nemotivate", "error") : null,
              motCount ? chip(motCount + " motivate", "success") : null
            )
          )
        ),
        h("ul", { class: "abs-ul abs-subject-list" }, records.map(absenceRow))
      );
    });

    sections.push(
      h(
        "section",
        { class: "abs-section", "aria-label": "Absențe pe discipline" },
        h("h3", { class: "section-title", text: "Pe discipline" }),
        h("div", { class: "abs-subject-grid" }, subjectCards)
      )
    );
  }

  // Chronological list
  sections.push(
    h(
      "section",
      { class: "abs-section", "aria-label": "Toate absențele" },
      h("h3", { class: "section-title", text: "Cronologic" }),
      h("ul", { class: "abs-ul" }, sem.records.map(absenceRow))
    )
  );

  setChildren(list, sections);
}

// rendering: homework

function renderHomework() {
  const host = $("homework-list");
  const m = model();
  if (!m) {
    setChildren(host, pending(260));
    return;
  }
  const today = todayIso();
  const items = sortHomework(m.homework, today);
  if (!items.length) {
    setChildren(host, emptyState("Nicio temă", "Temele noi apar aici după sincronizare."));
    return;
  }
  const groups = [];
  for (const task of items) {
    const key = task.due || "none";
    let group = groups.find((g) => g.key === key);
    if (!group) {
      group = { key, due: task.due, tasks: [] };
      groups.push(group);
    }
    group.tasks.push(task);
  }
  setChildren(
    host,
    groups.map((g) =>
      h(
        "section",
        { class: "task-group" },
        h("h2", { class: "group-title", text: g.due ? relativeDay(g.due, today) + ", " + longDate(g.due) : "Fără termen" }),
        h("ul", { class: "task-list" }, g.tasks.map((t) => taskRow(t, today, false)))
      )
    )
  );
}

// rendering: profile

function renderProfile() {
  const card = $("profile-card");
  const grid = $("teacher-grid");
  const m = model();
  if (!m) {
    setChildren(card, pending(220));
    setChildren(grid);
    return;
  }
  const s = m.student;
  const meta = m.metadata;
  const name = toTitleCase(s.fullName || meta.fullName || "Elev");
  const className = s.className || meta.className;
  const school = s.schoolName || meta.schoolName;
  const idx = Math.max(0, state.students.findIndex((x) => x.id === s.id));
  const rows = [
    ["Școala", school],
    ["Diriginte", meta.classMaster],
    ["Anul de studiu", meta.studyYear],
    ["Data nașterii", meta.birthDate],
  ].filter((row) => row[1]);

  setChildren(
    card,
    h(
      "section",
      { class: "profile-card", "aria-label": "Elev" },
      avatar(s, idx, "xl"),
      h(
        "div",
        null,
        h("h2", { class: "profile-name", text: name }),
        h("div", { class: "profile-chips" }, className ? chip("Clasa " + className, "info", "school") : null, meta.enrollmentStatus ? chip(meta.enrollmentStatus, "success", "check_circle") : null)
      )
    ),
    rows.length ? h("dl", { class: "info-grid" }, rows.map((row) => h("div", { class: "info" }, h("dt", { text: row[0] }), h("dd", { text: String(row[1]) })))) : null
  );

  const teachers = m.teachers;
  setChildren(
    grid,
    h("h2", { class: "section-title", text: "Profesori" }),
    teachers.length
      ? h(
          "div",
          { class: "teacher-grid" },
          teachers.map((t, i) =>
            h(
              "article",
              { class: "teacher-card" },
              h("div", { class: "teacher-head" }, avatar({ fullName: t.name }, i + 1, "md"), h("h3", { class: "teacher-name", text: t.name }), t.note ? chip(t.note, "info") : null),
              Array.isArray(t.subjects) && t.subjects.length ? h("div", { class: "chip-row" }, t.subjects.map((subject) => chip(subject, "neutral"))) : null
            )
          )
        )
      : emptyState("Lista profesorilor nu este disponibilă", "Profesorii apar după ce orarul și notele sunt încărcate.")
  );
}

// rendering: shell

function renderStudents() {
  const host = $("student-list");
  if (!host) return;
  const strip = host.closest(".student-strip");
  const list = Array.from(new Map(state.students.map((s) => [String(s.id), s])).values());
  const multiple = list.length > 1 && state.role !== "student";

  if (!multiple) {
    if (strip) strip.hidden = true;
    host.replaceChildren();
    return;
  }
  if (strip) strip.hidden = false;
  if (!list.length) {
    host.replaceChildren(h("div", { class: "skeleton student-skel", "aria-hidden": "true" }));
    return;
  }
  const hadFocus = host.contains(document.activeElement);
  const currentId = state.studentKey || state.switching || (state.data && state.data.student && state.data.student.id) || "";
  const nodes = list.map((s, i) => {
    const active = String(s.id) === String(currentId);
    const known = state.students.find((x) => String(x.id) === String(s.id));
    const cls = s.className || known?.className || null;
    const body = [
      avatar(s, i, "sm"),
      h("span", { class: "student-text" }, h("span", { class: "student-name", text: toTitleCase(s.fullName || "Elev") }), cls ? h("span", { class: "student-class", text: cls }) : null),
    ];
    return h(
      "button",
      {
        type: "button",
        class: "student-chip" + (active ? " is-active" : "") + (state.switching === s.id ? " is-loading" : ""),
        "aria-pressed": String(active),
        on: { click: () => switchStudent(s.id) },
      },
      body
    );
  });
  host.replaceChildren(...nodes);
  if (hadFocus) {
    const el = host.querySelector("button.is-active");
    if (el) el.focus({ preventScroll: true });
  }
}

function renderHeaderState() {
  const prog = $("global-progress");
  if (prog) prog.hidden = !state.syncing && !state.switching;

  const off = $("offline-chip");
  if (off) off.hidden = !state.offline;

  const subtitle = $("header-student-subtitle");
  if (subtitle) {
    if (state.role === "parent" && state.studentKey) {
      const s = state.students.find((x) => String(x.id) === String(state.studentKey));
      const name = s?.fullName || state.data?.student?.fullName || "";
      const cls = s?.className || state.data?.student?.className || "";
      subtitle.textContent = [name, cls ? `Clasa ${cls}` : ""].filter(Boolean).join(" • ");
      subtitle.hidden = !subtitle.textContent;
    } else {
      subtitle.hidden = true;
    }
  }

  const avatarEl = $("account-avatar");
  if (avatarEl) {
    if (state.role === "student") {
      avatarEl.textContent = (state.data?.student?.fullName || "E").slice(0, 1).toUpperCase();
    } else {
      avatarEl.textContent = "P";
    }
  }

  const syncTimeEl = $("settings-sync-time");
  if (syncTimeEl) {
    if (state.syncedAt) {
      const age = Date.now() - state.syncedAt;
      syncTimeEl.textContent = `Ultima sincronizare: ${agoText(age)}`;
    } else {
      syncTimeEl.textContent = "Nesincronizat";
    }
  }
}

let cooldownTimer = 0;

function scheduleCooldown() {
  window.clearTimeout(cooldownTimer);
  renderHeaderState();
  if (Math.max(state.cooldownUntil, state.limitedUntil) > Date.now()) cooldownTimer = window.setTimeout(scheduleCooldown, 1000);
}

function renderTheme() {
  const btn = $("theme-btn");
  if (!btn) return;
  const isDark = state.theme === "dark";
  const label = isDark ? "Comută la tema luminoasă" : "Comută la tema întunecată";
  setIcon(btn, isDark ? "light_mode" : "dark_mode");
  btn.setAttribute("aria-label", label);
  btn.title = label;
}

function render() {
  renderStudents();
  renderHome();
  renderMarks();
  buildDaySwitch();
  renderLessons();
  buildAbsToggle();
  renderAbsences();
  renderHomework();
  renderProfile();
  renderHeaderState();
}

function tick() {
  const today = todayIso();
  if (state.today && today !== state.today) {
    state.today = today;
    state.day = null;
    if (state.token) render();
    return;
  }
  state.today = today;
  renderHeaderState();
  if (!state.token || !state.data) return;
  renderLive();
  if (state.tab === "timetable") renderLessons();
}

// navigation and theme

function tabFromHash() {
  const id = window.location.hash.replace(/^#/, "");
  return TABS.some((t) => t.id === id) ? id : null;
}

function goTab(id) {
  if (window.location.hash !== "#" + id) window.location.hash = id;
  else setTab(id);
}

function bindNavKeyboard(container, orientation = "vertical") {
  if (!container) return;
  container.addEventListener("keydown", (e) => {
    const items = [...container.querySelectorAll(".nav-item, button")];
    const currentIndex = items.indexOf(document.activeElement);
    if (currentIndex === -1) return;

    let nextIndex = -1;
    if (orientation === "vertical") {
      if (e.key === "ArrowDown") nextIndex = (currentIndex + 1) % items.length;
      else if (e.key === "ArrowUp") nextIndex = (currentIndex - 1 + items.length) % items.length;
    } else {
      if (e.key === "ArrowRight") nextIndex = (currentIndex + 1) % items.length;
      else if (e.key === "ArrowLeft") nextIndex = (currentIndex - 1 + items.length) % items.length;
    }

    if (nextIndex !== -1) {
      e.preventDefault();
      items[nextIndex].focus();
    }
  });
}

function buildNav() {
  const mk = (tab) =>
    h(
      "button",
      { type: "button", class: "nav-item", "data-tab": tab.id, on: { click: () => goTab(tab.id) } },
      h("span", { class: "nav-pill" }, icon(tab.icon)),
      h("span", { class: "nav-label", text: tab.label })
    );
  for (const tab of TABS) {
    if (tab.id === "profile") continue;
    $("rail-nav").appendChild(mk(tab));
    $("bottom-nav").appendChild(mk(tab));
  }

  const profPill = $("drawer-profile-btn")?.querySelector(".nav-pill");
  if (profPill && !profPill.firstChild) profPill.appendChild(icon("person"));
  const setPill = $("drawer-settings-btn")?.querySelector(".nav-pill");
  if (setPill && !setPill.firstChild) setPill.appendChild(icon("settings"));

  $("drawer-profile-btn")?.addEventListener("click", () => goTab("profile"));
  $("drawer-settings-btn")?.addEventListener("click", () => {
    $("settings-dialog")?.showModal();
  });

  bindNavKeyboard($("rail-nav"), "vertical");
  bindNavKeyboard(document.querySelector(".drawer-pinned-bottom"), "vertical");
  bindNavKeyboard($("bottom-nav"), "horizontal");
}

const tabLoadedAt = new Map();
const tabInFlight = new Map();

const TAB_TTLS = {
  marks: 120_000,
  timetable: 60_000,
  absences: 120_000,
  homework: 60_000,
  profile: 15 * 60_000,
};

async function loadTabData(tabId, { force = false } = {}) {
  if (!state.token || !tabId || tabId === "home") return;
  const student = state.studentKey || (state.data && state.data.student && state.data.student.id) || "";
  if (!student) return;
  const currentSeq = state.seq;
  const subKey = tabId === "timetable" ? `${tabId}:${student}:${state.day || todayIso()}` : `${tabId}:${student}`;
  const now = Date.now();
  const lastLoaded = tabLoadedAt.get(subKey) || 0;
  const ttl = TAB_TTLS[tabId] || 60_000;

  if (!force && lastLoaded && now - lastLoaded < ttl) {
    return;
  }
  if (tabInFlight.has(subKey)) {
    return tabInFlight.get(subKey);
  }

  const promise = (async () => {
    try {
      const q = `?student=${encodeURIComponent(student)}`;
      if (tabId === "marks") {
        const res = await api(`/api/catalog${q}`);
        if (currentSeq !== state.seq || canonKey(student) !== canonKey(state.studentKey)) return;
        if (res && res.studentKey && canonKey(res.studentKey) !== canonKey(student)) return;
        if (res && res.data) {
          if (!state.data) state.data = {};
          if (Array.isArray(res.data) && res.data.length) {
            const marksKey = "open-sime.prev-marks." + student;
            let prevCatalog = null;
            try {
              const prev = store.get(marksKey);
              if (prev) prevCatalog = JSON.parse(prev);
            } catch {
              prevCatalog = null;
            }
            diffNewMarks(res.data, prevCatalog);
            try {
              store.set(marksKey, JSON.stringify(res.data));
            } catch {}
          }
          state.data.catalog = res.data;
          state.data.reusita = res.data;
          modelSource = null;
          if (state.tab === "marks") renderMarks();
        }
      } else if (tabId === "timetable") {
        const d = state.day || todayIso();
        const schedQ = `?date=${encodeURIComponent(d)}&student=${encodeURIComponent(student)}`;
        const res = await api(`/api/schedule${schedQ}`);
        if (currentSeq !== state.seq || canonKey(student) !== canonKey(state.studentKey)) return;
        if (res && res.studentKey && canonKey(res.studentKey) !== canonKey(student)) return;
        if (res && res.data) {
          if (!state.data) state.data = {};
          state.data.schedule = res.data;
          state.data.orar = res.data;
          modelSource = null;
          if (state.tab === "timetable") {
            buildDaySwitch();
            renderLessons();
          }
        }
      } else if (tabId === "absences") {
        const res = await api(`/api/absences${q}`);
        if (currentSeq !== state.seq || canonKey(student) !== canonKey(state.studentKey)) return;
        if (res && res.studentKey && canonKey(res.studentKey) !== canonKey(student)) return;
        if (res && res.data) {
          if (!state.data) state.data = {};
          state.data.absente = res.data;
          state.data.absences = res.data;
          modelSource = null;
          if (state.tab === "absences") renderAbsences();
        }
      } else if (tabId === "profile") {
        const [teachersRes, profileRes] = await Promise.allSettled([
          api(`/api/teachers${q}`),
          api(`/api/profile${q}`),
        ]);
        if (currentSeq !== state.seq || canonKey(student) !== canonKey(state.studentKey)) return;
        if (!state.data) state.data = {};
        if (teachersRes.status === "fulfilled" && teachersRes.value && teachersRes.value.data) {
          const resKey = teachersRes.value.studentKey;
          const matchStudent = !resKey || canonKey(resKey) === canonKey(student) || state.role === "student" || (state.students && state.students.length <= 1);
          if (matchStudent) {
            const list = teachersRes.value.data.teachers || teachersRes.value.data;
            if (Array.isArray(list) && list.length) {
              state.data.teachers = list;
            }
          }
        }
        if (profileRes.status === "fulfilled" && profileRes.value && profileRes.value.data) {
          const resKey = profileRes.value.studentKey;
          const matchStudent = !resKey || canonKey(resKey) === canonKey(student) || state.role === "student" || (state.students && state.students.length <= 1);
          if (matchStudent) {
            state.data.metadata = profileRes.value.data.metadata || profileRes.value.data;
          }
          if (Array.isArray(profileRes.value.students)) {
            for (const updated of profileRes.value.students) {
              const found = state.students.find((s) => canonKey(s.id) === canonKey(updated.id));
              if (found) {
                if (updated.className) found.className = updated.className;
                if (updated.schoolName) found.schoolName = updated.schoolName;
              } else {
                state.students.push(updated);
              }
            }
            renderStudents();
          }
        }
        modelSource = null;
        if (state.tab === "profile") renderProfile();
      } else if (tabId === "homework") {
        const res = await api(`/api/homework${q}`);
        if (currentSeq !== state.seq || canonKey(student) !== canonKey(state.studentKey)) return;
        if (res && res.studentKey && canonKey(res.studentKey) !== canonKey(student)) return;
        if (res && res.data) {
          if (!state.data) state.data = {};
          state.data.homework = res.data.homework || res.data;
          modelSource = null;
          if (state.tab === "homework") renderHomework();
        }
      }
      tabLoadedAt.set(subKey, Date.now());
    } catch (err) {
      if (currentSeq === state.seq && student === state.studentKey && !state.data) {
        toast(describeError(err, "data"));
      }
    } finally {
      tabInFlight.delete(subKey);
    }
  })();

  tabInFlight.set(subKey, promise);
  return promise;
}

function setTab(id) {
  const tab = TABS.find((t) => t.id === id) || TABS[0];
  state.tab = tab.id;
  store.set(KEYS.tab, tab.id);
  for (const view of document.querySelectorAll(".view")) view.hidden = view.dataset.view !== tab.id;
  for (const item of document.querySelectorAll(".nav-item")) {
    if (item.dataset.tab === tab.id) item.setAttribute("aria-current", "page");
    else item.removeAttribute("aria-current");
  }
  $("profile-btn").classList.toggle("is-current", tab.id === "profile");
  const title = (state.simpleMode && SIMPLE_TAB_TITLES[tab.id]) ? SIMPLE_TAB_TITLES[tab.id] : tab.title;
  $("page-title").textContent = title;
  document.title = title + " - Open SIME";
  if (tab.id === "timetable") renderLessons();
  window.scrollTo(0, 0);
  if (state.token) {
    loadTabData(tab.id);
  }
}

const colorQuery = window.matchMedia("(prefers-color-scheme: dark)");

function getEffectiveTheme() {
  return state.theme === "dark" ? "dark" : "light";
}

function applyTheme() {
  const effective = getEffectiveTheme();
  document.documentElement.dataset.theme = effective;
  const color = window.getComputedStyle(document.documentElement).getPropertyValue("--surface").trim();
  const meta = document.querySelector('meta[name="theme-color"]');
  if (meta && color) meta.setAttribute("content", color);
  if ($("theme-btn")) renderTheme();
}

function cycleTheme() {
  state.theme = state.theme === "dark" ? "light" : "dark";
  store.set(KEYS.theme, state.theme);
  applyTheme();
  const themeRadio = document.querySelector(`input[name="setting-theme"][value="${state.theme}"]`);
  if (themeRadio) themeRadio.checked = true;
}

function applySettings() {
  document.documentElement.lang = state.lang || "ro";
  document.documentElement.dataset.textSize = state.textSize || "M";
  document.documentElement.dataset.simpleMode = String(Boolean(state.simpleMode));
  document.documentElement.dataset.contrast = state.contrast ? "high" : "normal";
  document.documentElement.dataset.reduceMotion = String(Boolean(state.reduceMotion));

  const langRadio = document.querySelector(`input[name="setting-lang"][value="${state.lang}"]`);
  if (langRadio) langRadio.checked = true;

  const sizeRadio = document.querySelector(`input[name="setting-text-size"][value="${state.textSize}"]`);
  if (sizeRadio) sizeRadio.checked = true;

  const themeRadio = document.querySelector(`input[name="setting-theme"][value="${state.theme}"]`);
  if (themeRadio) themeRadio.checked = true;

  const simpleCheckbox = $("setting-simple-mode");
  if (simpleCheckbox) simpleCheckbox.checked = Boolean(state.simpleMode);

  const contrastCheckbox = $("setting-contrast");
  if (contrastCheckbox) contrastCheckbox.checked = Boolean(state.contrast);

  const reduceMotionCheckbox = $("setting-reduce-motion");
  if (reduceMotionCheckbox) reduceMotionCheckbox.checked = Boolean(state.reduceMotion);

  applyTheme();
  setTab(state.tab);
}

function initSettings() {
  state.lang = store.get(KEYS.lang) || "ro";
  state.textSize = store.get(KEYS.textSize) || "M";
  state.simpleMode = store.get(KEYS.simpleMode) === "true";
  state.contrast = store.get(KEYS.contrast) === "true";
  state.reduceMotion = store.get(KEYS.reduceMotion) === "true";

  const dialog = $("settings-dialog");
  const openBtn = $("settings-btn");
  const closeBtn = $("settings-close-btn");

  if (openBtn && dialog) {
    openBtn.addEventListener("click", () => {
      if (typeof dialog.showModal === "function") {
        dialog.showModal();
      } else {
        dialog.setAttribute("open", "");
      }
    });
  }

  if (closeBtn && dialog) {
    closeBtn.addEventListener("click", () => {
      if (typeof dialog.close === "function") {
        dialog.close();
      } else {
        dialog.removeAttribute("open");
      }
    });
  }

  if (dialog) {
    dialog.addEventListener("click", (e) => {
      const rect = dialog.getBoundingClientRect();
      const inDialog =
        rect.top <= e.clientY &&
        e.clientY <= rect.top + rect.height &&
        rect.left <= e.clientX &&
        e.clientX <= rect.left + rect.width;
      if (!inDialog) {
        if (typeof dialog.close === "function") dialog.close();
        else dialog.removeAttribute("open");
      }
    });
  }

  for (const radio of document.querySelectorAll('input[name="setting-lang"]')) {
    radio.addEventListener("change", (e) => {
      if (e.target.checked) {
        state.lang = e.target.value;
        store.set(KEYS.lang, state.lang);
        applySettings();
        render();
      }
    });
  }

  for (const radio of document.querySelectorAll('input[name="setting-text-size"]')) {
    radio.addEventListener("change", (e) => {
      if (e.target.checked) {
        state.textSize = e.target.value;
        store.set(KEYS.textSize, state.textSize);
        applySettings();
      }
    });
  }

  for (const radio of document.querySelectorAll('input[name="setting-theme"]')) {
    radio.addEventListener("change", (e) => {
      if (e.target.checked) {
        state.theme = e.target.value;
        store.set(KEYS.theme, state.theme);
        applyTheme();
      }
    });
  }

  const simpleInput = $("setting-simple-mode");
  if (simpleInput) {
    simpleInput.addEventListener("change", (e) => {
      state.simpleMode = e.target.checked;
      store.set(KEYS.simpleMode, String(state.simpleMode));
      applySettings();
      render();
    });
  }

  const contrastInput = $("setting-contrast");
  if (contrastInput) {
    contrastInput.addEventListener("change", (e) => {
      state.contrast = e.target.checked;
      store.set(KEYS.contrast, String(state.contrast));
      applySettings();
    });
  }

  const motionInput = $("setting-reduce-motion");
  if (motionInput) {
    motionInput.addEventListener("change", (e) => {
      state.reduceMotion = e.target.checked;
      store.set(KEYS.reduceMotion, String(state.reduceMotion));
      applySettings();
    });
  }

  const forgetBtn = $("forget-device-btn");
  if (forgetBtn) {
    forgetBtn.addEventListener("click", async () => {
      forgetBtn.disabled = true;
      try {
        await api("/api/auth/forget-device", { method: "POST", auth: false, retry: false });
        toast(getString("device_forgotten"));
      } catch {
        toast(getString("device_forgotten"));
      } finally {
        forgetBtn.disabled = false;
      }
    });
  }

  applySettings();
}

// auth

function showAuthMessage(text, tone) {
  const box = $("auth-message");
  box.className = "banner" + (tone === "info" ? " banner-info" : "");
  box.hidden = false;
  $("auth-message-icon").replaceChildren(icon(tone === "info" ? "info" : "error"));
  $("auth-message-text").textContent = text;
}

function clearAuthMessage() {
  $("auth-message").hidden = true;
}

function setAuthBusy(busy) {
  state.authBusy = busy;
  const btn = $("login-submit");
  btn.disabled = busy || Date.now() < state.authLockUntil;
  btn.classList.toggle("is-busy", busy);
  $("login-label").textContent = busy ? "Se verifică datele" : "Intră în cont";
}

function lockLogin(seconds) {
  state.authLockUntil = Date.now() + seconds * 1000;
  window.setTimeout(() => setAuthBusy(state.authBusy), seconds * 1000 + 50);
}

function showAuth(message) {
  $("splash").hidden = true;
  $("shell").hidden = true;
  $("auth-view").hidden = false;
  if (message) showAuthMessage(message, "info");
  else clearAuthMessage();
  document.title = "Autentificare - Open SIME";
  setAuthBusy(false);
}

function showShell() {
  $("splash").hidden = true;
  $("auth-view").hidden = true;
  $("shell").hidden = false;
}

async function onLogin(event) {
  event.preventDefault();
  if (state.authBusy || Date.now() < state.authLockUntil) return;
  const username = $("username").value.trim();
  const password = $("password").value;
  const role = $("role-parent").checked ? "parent" : "student";
  clearAuthMessage();
  if (!username || !password) {
    showAuthMessage("Introdu numele de utilizator și parola.", "error");
    (username ? $("password") : $("username")).focus();
    return;
  }
  setAuthBusy(true);
  try {
    const remember = Boolean($("auth-remember")?.checked || $("remember-me")?.checked);
    const res = await api("/api/auth/login", { method: "POST", body: { username, password, role, remember, rememberMe: remember }, auth: false });
    if (!res || !res.token || !res.data) throw new ApiError("bad response", 502, 0);
    state.seq += 1;
    state.token = res.token;
    state.role = res.role || role;
    store.set(KEYS.token, res.token);
    store.set(KEYS.role, state.role);
    store.del("open_sime_creds");
    const payload = res.data;
    if (!Array.isArray(payload.students) && Array.isArray(res.students)) payload.students = res.students;
    state.error = "";
    state.stale = false;
    state.offline = false;
    state.cooldownUntil = 0;
    state.limitedUntil = 0;
    state.semesterTouched = false;
    state.students = [];
    setData(payload, Date.now());
    $("password").value = "";
    showShell();
    setTab(state.tab);
    render();
  } catch (err) {
    showAuthMessage(describeError(err, "login"), "error");
    if (err instanceof ApiError && err.status === 429 && err.wait) lockLogin(err.wait);
  } finally {
    setAuthBusy(false);
  }
}

function endSession(message) {
  state.seq += 1;
  state.token = null;
  state.studentKey = "";
  state.data = null;
  state.students = [];
  state.switching = null;
  state.syncing = false;
  state.stale = false;
  state.offline = false;
  state.error = "";
  state.cooldownUntil = 0;
  state.limitedUntil = 0;
  state.syncedAt = 0;
  store.del(KEYS.token);
  store.del(KEYS.student);
  store.del(KEYS.data);
  store.del(KEYS.sync);
  tabLoadedAt.clear();
  tabInFlight.clear();
  window.clearTimeout(cooldownTimer);
  showAuth(message);
}

async function logout() {
  const btn = $("logout-btn");
  btn.disabled = true;
  try {
    await api("/api/auth/logout", { method: "POST" });
  } catch {
    btn.disabled = false;
  }
  btn.disabled = false;
  endSession("");
}

// sync and student switching

async function refresh({ force = false, background = false } = {}) {
  if (!state.token || state.syncing) return;
  const seq = state.seq;
  state.syncing = true;
  renderHeaderState();
  const student = state.studentKey || (state.data && state.data.student && state.data.student.id) || "";
  const query = [];
  if (student) query.push("student=" + encodeURIComponent(student));
  if (force) query.push("force=1");
  try {
    const res = await api("/api/overview" + (query.length ? "?" + query.join("&") : ""));
    if (seq !== state.seq || !res || !res.data) return;
    if (res.studentKey && state.studentKey && res.studentKey !== state.studentKey) return;
    if (res.role) {
      state.role = res.role;
      store.set(KEYS.role, res.role);
    }
    const ageMs = ((res.cache && num(res.cache.ageSeconds)) || 0) * 1000;
    state.stale = Boolean(res.cache && res.cache.stale);
    state.offline = false;
    state.error = "";
    if (!Array.isArray(res.data.students) && Array.isArray(res.students)) res.data.students = res.students;
    setData(res.data, Date.now() - ageMs);
    render();
    if (force) {
      tabLoadedAt.clear();
      if (state.tab && state.tab !== "home") loadTabData(state.tab, { force: true });
    }
  } catch (err) {
    if (seq !== state.seq) return;
    if (err instanceof ApiError && err.status === 401) {
      endSession(describeError(err, "data"));
      return;
    }
    if (err instanceof ApiError && err.status === 429) state.limitedUntil = Date.now() + Math.min(err.wait || 60, 3600) * 1000;
    state.offline = true;
    if (!state.data) {
      state.error = describeError(err, "data");
      render();
    } else if (!background) {
      toast(describeError(err, "data"));
    }
  } finally {
    state.syncing = false;
    scheduleCooldown();
  }
}

async function switchStudent(id) {
  const current = state.studentKey || (state.data && state.data.student && state.data.student.id);
  if (!id || state.switching === id || (current && String(current) === String(id))) return;
  const previous = { data: state.data, syncedAt: state.syncedAt, stale: state.stale, studentKey: state.studentKey };
  state.seq += 1;
  const seq = state.seq;
  state.studentKey = id;
  state.switching = id;
  state.data = null;
  state.error = "";
  render();
  try {
    const res = await api("/api/student/switch", { method: "POST", body: { studentId: String(id) } });
    if (seq !== state.seq || !res || !res.data) return;
    if (res.role) {
      state.role = res.role;
      store.set(KEYS.role, res.role);
    }
    if (res.token) {
      state.token = res.token;
      store.set(KEYS.token, res.token);
    }
    const ageMs = ((res.cache && num(res.cache.ageSeconds)) || 0) * 1000;
    state.studentKey = res.studentKey || res.student?.id || id;
    state.switching = null;
    state.stale = Boolean(res.cache && res.cache.stale);
    state.offline = false;
    tabLoadedAt.clear();
    const students = Array.isArray(res.students) ? res.students : res.data?.students;
    if (Array.isArray(students)) state.students = students;
    setData(res.data, Date.now() - ageMs);
    render();
    if (state.tab && state.tab !== "home") loadTabData(state.tab, { force: true });
  } catch (err) {
    if (seq !== state.seq) return;
    state.switching = null;
    if (err instanceof ApiError && err.status === 401) {
      endSession(describeError(err, "data"));
      return;
    }
    if (err instanceof ApiError && err.status === 429) state.limitedUntil = Date.now() + Math.min(err.wait || 60, 3600) * 1000;
    state.studentKey = previous.studentKey;
    state.data = previous.data;
    state.syncedAt = previous.syncedAt;
    state.stale = previous.stale;
    render();
    scheduleCooldown();
    toast(describeError(err, "data"));
  }
}

function bindAccountMenu() {
  const btn = $("account-btn");
  const menu = $("account-menu");
  if (!btn || !menu) return;

  btn.addEventListener("click", (e) => {
    e.stopPropagation();
    const open = !menu.hidden;
    menu.hidden = open;
    btn.setAttribute("aria-expanded", String(!open));
  });

  document.addEventListener("click", (e) => {
    if (!menu.hidden && !menu.contains(e.target) && e.target !== btn) {
      menu.hidden = true;
      btn.setAttribute("aria-expanded", "false");
    }
  });

  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && !menu.hidden) {
      menu.hidden = true;
      btn.setAttribute("aria-expanded", "false");
      btn.focus();
    }
  });
}

// startup

function bindEvents() {
  $("login-form").addEventListener("submit", onLogin);
  $("toggle-password").addEventListener("click", () => {
    const input = $("password");
    const show = input.type === "password";
    input.type = show ? "text" : "password";
    const btn = $("toggle-password");
    btn.setAttribute("aria-pressed", String(show));
    btn.setAttribute("aria-label", show ? "Ascunde parola" : "Arată parola");
    setIcon(btn, show ? "visibility_off" : "visibility");
  });
  $("theme-btn").addEventListener("click", cycleTheme);
  bindAccountMenu();
  $("logout-btn").addEventListener("click", () => {
    const menu = $("account-menu");
    if (menu) {
      menu.hidden = true;
      $("account-btn")?.setAttribute("aria-expanded", "false");
    }
    logout();
  });
  $("profile-btn").addEventListener("click", () => {
    const menu = $("account-menu");
    if (menu) {
      menu.hidden = true;
      $("account-btn")?.setAttribute("aria-expanded", "false");
    }
    goTab("profile");
  });
  window.addEventListener("hashchange", () => {
    if (state.token) setTab(tabFromHash() || "home");
  });
  colorQuery.addEventListener("change", () => {
    applyTheme();
  });
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState !== "visible" || !state.token) return;
    tick();
    if (Date.now() - state.syncedAt > STALE_AFTER_MS) refresh({ background: true });
  });
}

function boot() {
  store.del("open_sime_creds");
  const savedTheme = store.get(KEYS.theme);
  if (savedTheme === "light" || savedTheme === "dark") {
    state.theme = savedTheme;
  } else {
    state.theme = colorQuery.matches ? "dark" : "light";
    store.set(KEYS.theme, state.theme);
  }

  for (const el of document.querySelectorAll("[data-icon]")) el.prepend(icon(el.dataset.icon));
  for (const el of document.querySelectorAll("[data-cookie]")) el.appendChild(cookieSvg("deco-shape"));
  applyTheme();
  buildNav();
  bindEvents();
  initSettings();

  api("/api/auth/config", { auth: false, retry: false })
    .then((cfg) => {
      const row = document.querySelector(".remember-row");
      if (row) row.hidden = !cfg?.rememberMeAvailable;
    })
    .catch(() => {
      const row = document.querySelector(".remember-row");
      if (row) row.hidden = true;
    });

  if (store.get(KEYS.role) === "parent") $("role-parent").checked = true;
  state.today = todayIso();
  state.tab = tabFromHash() || (TABS.some((t) => t.id === store.get(KEYS.tab)) ? store.get(KEYS.tab) : "home");

  const token = store.get(KEYS.token);
  if (token) {
    state.role = store.get(KEYS.role) || "student";
    state.token = token;
    const cached = loadCached();
    if (cached) setData(cached, Number(store.get(KEYS.sync)) || 0);
    showShell();
    setTab(state.tab);
    render();
    refresh({ background: true });
  } else {
    api("/api/auth/resume", { method: "POST", auth: false, retry: false })
      .then((res) => {
        if (!res || !res.token || !res.data) throw new Error("no session");
        state.seq += 1;
        state.token = res.token;
        state.role = res.role || "student";
        store.set(KEYS.token, res.token);
        store.set(KEYS.role, state.role);
        const payload = res.data;
        if (!Array.isArray(payload.students) && Array.isArray(res.students)) payload.students = res.students;
        setData(payload, Date.now());
        showShell();
        setTab(state.tab);
        render();
      })
      .catch(() => {
        showAuth("");
      });
  }
  window.setInterval(tick, TICK_MS);

  if ("serviceWorker" in navigator) {
    navigator.serviceWorker.register("/sw.js").catch(() => {});
  }
  window.addEventListener("online", () => {
    state.offline = false;
    renderHeaderState();
  });
  window.addEventListener("offline", () => {
    state.offline = true;
    renderHeaderState();
  });
}

boot();