// Mocked agenda.sime.md upstream with TWO children whose data deliberately differs everywhere.
// The portal's "current student" is global and mutable (like an APEX session): a GET to a
// student's checksummed switch URL changes it, and AJAX answers are derived from it, not from
// the submitted P1_ELEVE_ID (worst case). `stuck: true` makes the portal ignore switches.
import http from "node:http";
import { app, SimeClient, sessions } from "../../server.js";

export const SESSION = "777888";
export const PORTAL_URL = `https://agenda.sime.md/ords/r/agenda/main/dashboard?session=${SESSION}`;

const today = new Intl.DateTimeFormat("en-CA", { timeZone: "Europe/Chisinau" }).format(new Date());
const esc = (s) => String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");

export const STUDENTS = {
  A: {
    tag: "A", pid: "101", nav: "5B1E59D6A1", cs: "CS_A", name: "Ion Ciobanu", cls: "11R", school: "Liceul Alfa",
    master: "Alpha Dirigintele", teacher: "Alpha Profesor", lessonSubject: "Matematica",
    marks1: "7, 8", marks2: "9", absenceSubject: "Fizica", hw: "TEMA-A-ALGEBRA",
  },
  B: {
    tag: "B", pid: "202", nav: "8F7E6D5C4B", cs: "CS_B", name: "Elena Ciobanu", cls: "8B", school: "Gimnaziul Beta",
    master: "Beta Dirigintele", teacher: "Beta Profesor", lessonSubject: "Istorie",
    marks1: "4, 5", marks2: "10", absenceSubject: "Chimie", hw: "TEMA-B-CRONOLOGIE",
  },
};

function pageFor(s) {
  const link = (x, current) =>
    `<li${current ? ' data-current="true"' : ""}><a href="/ords/f?p=AGENDA:1:${SESSION}::NO::P1_ELEVE_ID:${x.nav}&cs=${x.cs}">${x.name}</a></li>`;
  const homework = `<b>${today}</b><ul><li><span>${s.lessonSubject}</span><span>${s.hw}</span></li></ul>`;
  return (
    `<input id="pFlowId" value="104"><input id="pFlowStepId" value="1"><input id="pSalt" value="SALT"><input id="pPageItemsProtected" value="PROT">` +
    `<input id="P1_ELEVE_ID" value="${s.pid}"><input type="hidden" data-for="P1_ELEVE_ID" value="CK_${s.pid}">` +
    `<input id="P1_ELEVE" value="${s.name}"><input id="P1_CLASS_NAME" value="${s.cls}">` +
    `<input id="P1_INSTITUTION_NAME" value="${s.school}"><input id="P1_DIRIGINTE" value="${s.master}">` +
    `<input id="P1_MOTIVATED" value="0"><input id="P1_UNMOTIVATED" value="1">` +
    `<input id="P1_HOMEWORK" value="${esc(homework)}">` +
    `<div id="t_TreeNav"><ul>${link(STUDENTS.A, s.tag === "A")}${link(STUDENTS.B, s.tag === "B")}</ul></div>` +
    `<script>apex.widget.fullCalendar({"x":1,"ajaxIdentifier":"CALAJAX"});` +
    `apex.widget.report.init("sem1","SEM1AJAX",{});apex.widget.report.init("sem2","SEM2AJAX",{});</script>`
  );
}

const reportTable = (s, marks) =>
  `<table><tr><td headers="DISCIPLINA">${s.lessonSubject}</td><td headers="LISTA_EVALUATION">${marks}</td></tr></table>`;

function calendarFor(s) {
  return [
    {
      start: `${today}T08:00:00`, end: `${today}T08:45:00`,
      title: `<span class="lesson-text-wrap">${s.lessonSubject}</span><span class="lesson-teacher">${s.teacher}</span>`,
    },
    {
      start: `${today}T09:00:00`, end: `${today}T09:45:00`, className: "absence nemotivat",
      title: `<span class="lesson-text-wrap">${s.absenceSubject}</span><span class="lesson-teacher">${s.teacher}</span>`,
    },
  ];
}

export function createPortal({ delayFor = () => 0, stuck = false } = {}) {
  const portal = { current: "A", stuck, requests: [] };
  const sleep = (ms) => (ms > 0 ? new Promise((r) => setTimeout(r, ms)) : null);

  portal.request = async (method, url, opts = {}) => {
    const info = { method, url: String(url), ajax: String(opts.data?.p_request || "") };
    portal.requests.push(info);
    await sleep(delayFor({ ...info, current: portal.current }));
    if (method === "POST") {
      const s = STUDENTS[portal.current];
      if (info.ajax.includes("CALAJAX")) return { status: 200, url, text: JSON.stringify(calendarFor(s)) };
      if (info.ajax.includes("SEM1AJAX")) return { status: 200, url, text: reportTable(s, s.marks1) };
      if (info.ajax.includes("SEM2AJAX")) return { status: 200, url, text: reportTable(s, s.marks2) };
      return { status: 200, url, text: "" };
    }
    const m = /P1_ELEVE_ID:([^&#]+)/.exec(info.url);
    if (m && !portal.stuck) {
      const target = Object.values(STUDENTS).find((x) => x.nav.toLowerCase() === decodeURIComponent(m[1]).toLowerCase());
      if (target) portal.current = target.tag;
    }
    return { status: 200, url, text: pageFor(STUDENTS[portal.current]) };
  };
  portal.count = (re) => portal.requests.filter((r) => re.test(`${r.method} ${r.url} ${r.ajax}`)).length;
  return portal;
}

// A SimeClient already logged in against the mock (A is the portal's landing student)
export async function loggedInClient(portal) {
  const client = new SimeClient("parent1", "pw", "parent");
  client.auth.sessionId = SESSION;
  client.auth.portalUrl = PORTAL_URL;
  client.http.request = portal.request;
  await client.loadPortal();
  return client;
}

// Real express app + real login route; only the login handshake is replaced by the mock portal.
export async function withPortalServer(portal, fn) {
  const orig = SimeClient.prototype.loginAndParsePortal;
  SimeClient.prototype.loginAndParsePortal = async function () {
    this.auth.sessionId = SESSION;
    this.auth.portalUrl = PORTAL_URL;
    this.http.request = portal.request;
    const page = await this.fetchDashboard(PORTAL_URL, { bypassCache: true });
    this.registerStudents(page);
    const data = await this.enrichPortalPage(page);
    return { html: page.html, data };
  };
  const server = http.createServer(app);
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    const res = await fetch(`${base}/api/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: `parent-${Math.random().toString(36).slice(2)}`, password: "pw", role: "parent" }),
    });
    const login = await res.json();
    if (res.status !== 200) throw new Error(`login failed ${res.status} ${JSON.stringify(login)}`);
    const get = async (path, student) => {
      const sep = path.includes("?") ? "&" : "?";
      const r = await fetch(`${base}${path}${student === undefined ? "" : `${sep}student=${encodeURIComponent(student)}`}`, {
        headers: { Authorization: `Bearer ${login.token}` },
      });
      return { status: r.status, body: await r.json() };
    };
    return await fn({ base, login, get, token: login.token, session: sessions.get(login.token) });
  } finally {
    SimeClient.prototype.loginAndParsePortal = orig;
    await new Promise((r) => server.close(r));
  }
}

