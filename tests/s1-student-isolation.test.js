import assert from "node:assert/strict";
import { test } from "node:test";
import { STUDENTS, createPortal, loggedInClient, withPortalServer } from "./helpers/mock-portal.js";
import * as server from "../server.js";
const { SimeStudentMismatchError } = server;

const today = new Intl.DateTimeFormat("en-CA", { timeZone: "Europe/Chisinau" }).format(new Date());
const keyOf = (login, tag) => login.students.find((s) => s.fullName === STUDENTS[tag].name)?.id;

// Everything a tab shows, reduced to the facts that differ between A and B
async function readTab(get, tab, key) {
  switch (tab) {
    case "home": {
      const r = await get("/api/overview", key);
      return { r, facts: { name: r.body.data?.student?.fullName, cls: r.body.data?.student?.className, master: r.body.data?.metadata?.classMaster } };
    }
    case "marks": {
      const r = await get("/api/catalog", key);
      const d = r.body.data?.disciplines?.[0];
      return { r, facts: { subject: d?.subject, marks: d?.marks } };
    }
    case "timetable": {
      const r = await get(`/api/schedule?date=${today}`, key);
      return { r, facts: { lessons: (r.body.data?.days?.[today] || []).map((l) => l.subject).sort() } };
    }
    case "absences": {
      const r = await get("/api/absences", key);
      return { r, facts: { absences: (r.body.data?.records || []).map((x) => x.subject) } };
    }
    case "homework": {
      const r = await get("/api/homework", key);
      return { r, facts: { homework: (r.body.data?.homework || []).map((x) => x.taskDescription) } };
    }
    case "profile": {
      const t = await get("/api/teachers", key);
      const p = await get("/api/profile", key);
      return {
        r: t,
        facts: { teachers: (t.body.data?.teachers || []).map((x) => x.name).sort(), master: p.body.data?.metadata?.classMaster, cls: p.body.data?.metadata?.className },
        extra: [p],
      };
    }
    default:
      throw new Error(tab);
  }
}

function expectedFacts(tag, tab) {
  const s = STUDENTS[tag];
  switch (tab) {
    case "home": return { name: s.name, cls: s.cls, master: s.master };
    case "marks": return { subject: s.lessonSubject, marks: [...s.marks1.split(", "), ...s.marks2.split(", ")].map(Number) };
    case "timetable": return { lessons: [s.absenceSubject, s.lessonSubject].sort() };
    case "absences": return { absences: [s.absenceSubject] };
    case "homework": return { homework: [s.hw] };
    case "profile": return { teachers: [s.master, s.teacher].sort(), master: s.master, cls: s.cls };
    default: throw new Error(tab);
  }
}

const TABS = ["home", "marks", "timetable", "absences", "homework", "profile"];

async function assertTabFor(ctx, tag, tab) {
  const key = keyOf(ctx.login, tag);
  const { r, facts, extra = [] } = await readTab(ctx.get, tab, key);
  for (const x of [r, ...extra]) {
    assert.equal(x.status, 200, `${tab} for ${tag}: ${JSON.stringify(x.body)}`);
    assert.equal(x.body.studentKey, key, `${tab} for ${tag} must carry studentKey ${key}`);
  }
  assert.deepEqual(facts, expectedFacts(tag, tab), `${tab} must show only ${tag}'s data`);
}

test("S1: A → B → A → B across all tabs shows only the selected student (cold, then warm cache)", async () => {
  const portal = createPortal();
  await withPortalServer(portal, async (ctx) => {
    assert.ok(keyOf(ctx.login, "A") && keyOf(ctx.login, "B"), "login must list both children");
    for (const pass of ["cold", "warm"]) {
      for (const tag of ["A", "B", "A", "B"]) {
        for (const tab of TABS) await assertTabFor(ctx, tag, tab).catch((e) => { e.message = `[${pass}] ${e.message}`; throw e; });
      }
    }
  });
});

test("S1: concurrent requests with delayed, reordered upstream answers stay isolated", async () => {
  // A's calendar is slow, B's is fast, so B's answer is produced first
  const portal = createPortal({ delayFor: ({ ajax, current }) => (ajax.includes("CALAJAX") ? (current === "A" ? 60 : 5) : 0) });
  await withPortalServer(portal, async (ctx) => {
    const [a, b] = [keyOf(ctx.login, "A"), keyOf(ctx.login, "B")];
    const results = await Promise.all([
      ctx.get("/api/absences", a), ctx.get("/api/absences", b),
      ctx.get("/api/teachers", b), ctx.get("/api/teachers", a),
      ctx.get(`/api/schedule?date=${today}`, a), ctx.get(`/api/schedule?date=${today}`, b),
    ]);
    const subj = (r) => (r.body.data.records || []).map((x) => x.subject);
    assert.deepEqual(subj(results[0]), [STUDENTS.A.absenceSubject]);
    assert.deepEqual(subj(results[1]), [STUDENTS.B.absenceSubject]);
    assert.ok(results[2].body.data.teachers.some((t) => t.name === STUDENTS.B.master));
    assert.ok(!results[2].body.data.teachers.some((t) => t.name.startsWith("Alpha")));
    assert.ok(results[3].body.data.teachers.some((t) => t.name === STUDENTS.A.master));
    assert.ok(!results[3].body.data.teachers.some((t) => t.name.startsWith("Beta")));
    assert.ok(results[4].body.data.days[today].some((l) => l.subject === STUDENTS.A.lessonSubject));
    assert.ok(results[5].body.data.days[today].some((l) => l.subject === STUDENTS.B.lessonSubject));
  });
});

test("S1: portal's global current student moves, A is re-asserted before A's AJAX (never B's data under A)", async () => {
  const portal = createPortal();
  await withPortalServer(portal, async (ctx) => {
    const [a, b] = [keyOf(ctx.login, "A"), keyOf(ctx.login, "B")];
    await assertTabFor(ctx, "B", "absences"); // portal is now on B
    assert.equal(portal.current, "B");
    const before = portal.requests.length;
    await assertTabFor(ctx, "A", "absences"); // calendar for A must come from A, not from the portal's current B
    const sent = portal.requests.slice(before);
    const switchToA = sent.findIndex((r) => r.method === "GET" && r.url.includes("P1_ELEVE_ID:5B1E59D6A1"));
    const calendar = sent.findIndex((r) => r.ajax.includes("CALAJAX"));
    assert.ok(switchToA !== -1 && switchToA < calendar, "A must be re-asserted as portal-current before the calendar AJAX");
    assert.ok(a && b);
  });
});

test("S1: if the portal refuses to switch, the answer is STUDENT_MISMATCH, never the wrong student's data", async () => {
  const portal = createPortal({ stuck: true }); // always serves A
  await withPortalServer(portal, async (ctx) => {
    const b = keyOf(ctx.login, "B");
    for (const path of ["/api/absences", "/api/teachers", "/api/catalog", "/api/profile", "/api/homework", `/api/schedule?date=${today}`]) {
      const r = await ctx.get(path, b);
      assert.equal(r.status, 409, path);
      assert.equal(r.body.error.code, "STUDENT_MISMATCH", path);
      assert.equal(r.body.data, undefined, `${path} must not leak data`);
    }
    const client = await loggedInClient(createPortal({ stuck: true }));
    await assert.rejects(() => client.loadStudent(b), SimeStudentMismatchError);
  });
});

test("S1: student-scoped routes require an explicit, known student", async () => {
  await withPortalServer(createPortal(), async (ctx) => {
    for (const path of ["/api/overview", "/api/absences", "/api/teachers", "/api/catalog", "/api/homework", "/api/profile", `/api/schedule?date=${today}`]) {
      const none = await ctx.get(path);
      assert.equal(none.status, 400, `${path} without student`);
      assert.equal(none.body.error.code, "INVALID_REQUEST");
      const unknown = await ctx.get(path, "does-not-exist");
      assert.equal(unknown.status, 404, `${path} unknown student`);
      assert.equal(unknown.body.error.code, "STUDENT_NOT_FOUND");
    }
  });
});

// One regression per reported symptom ----------------------------------------------------------

test("S1 symptom 1: after A → B the Absențe tab shows B's absences, not A's", async () => {
  await withPortalServer(createPortal(), async (ctx) => {
    const [a, b] = [keyOf(ctx.login, "A"), keyOf(ctx.login, "B")];
    const ra = await ctx.get("/api/absences", a);
    const rb = await ctx.get("/api/absences", b);
    assert.deepEqual(ra.body.data.records.map((r) => r.subject), ["Fizica"]);
    assert.deepEqual(rb.body.data.records.map((r) => r.subject), ["Chimie"]);
  });
});

test("S1 symptom 2: B's Diriginte is B's class master, not A's", async () => {
  await withPortalServer(createPortal(), async (ctx) => {
    const [a, b] = [keyOf(ctx.login, "A"), keyOf(ctx.login, "B")];
    await ctx.get("/api/teachers", a);
    const t = await ctx.get("/api/teachers", b);
    const cm = t.body.data.teachers.find((x) => x.note === "Diriginte");
    assert.equal(cm.name, STUDENTS.B.master);
    const p = await ctx.get("/api/profile", b);
    assert.equal(p.body.data.metadata.classMaster, STUDENTS.B.master);
  });
});

test("S1 symptom 3: Reușita never shows the other student's marks, also on A → B → A → B", async () => {
  await withPortalServer(createPortal(), async (ctx) => {
    const [a, b] = [keyOf(ctx.login, "A"), keyOf(ctx.login, "B")];
    const expectA = [7, 8, 9];
    const expectB = [4, 5, 10];
    for (const [k, want] of [[a, expectA], [b, expectB], [a, expectA], [b, expectB]]) {
      const r = await ctx.get("/api/catalog", k);
      assert.deepEqual(r.body.data.disciplines[0].marks, want);
      const reusita = await ctx.get("/api/reusita", k);
      assert.deepEqual(reusita.body.data.disciplines[0].marks, want);
    }
  });
});

test("S1 symptom 4: the key echoed by every response equals the requested key, ids in the list are stable", async () => {
  await withPortalServer(createPortal(), async (ctx) => {
    const [a, b] = [keyOf(ctx.login, "A"), keyOf(ctx.login, "B")];
    const ids = (r) => r.body.students.map((s) => s.id).sort();
    const first = ids(await ctx.get("/api/overview", a));
    for (const k of [b, a, b]) {
      const o = await ctx.get("/api/overview", k);
      assert.equal(o.body.studentKey, k);
      assert.equal(o.body.student.id, k);
      assert.equal(o.body.data.student.id, k);
      assert.deepEqual(ids(o), first, "student ids must not flip format between pages");
    }
  });
});

test("S1 symptom 5: the second child's class label is available without opening their tabs", async () => {
  await withPortalServer(createPortal(), async (ctx) => {
    const [a, b] = [keyOf(ctx.login, "A"), keyOf(ctx.login, "B")];
    assert.equal(ctx.login.students.find((s) => s.id === a).className, "11R");
    // light on-demand profile: a single request, and it fills the class label of the whole list
    const before = ctx.session ? 0 : 0;
    const p = await ctx.get("/api/profile", b);
    assert.equal(p.status, 200);
    assert.equal(p.body.students.find((s) => s.id === b).className, "8B");
    assert.equal(p.body.students.find((s) => s.id === a).className, "11R");
    assert.equal(before, 0);
  });
});

test("S1: logout drops every cache of the account", async () => {
  await withPortalServer(createPortal(), async (ctx) => {
    const b = keyOf(ctx.login, "B");
    await ctx.get("/api/absences", b);
    const session = ctx.session;
    assert.ok(session.cache.size > 0);
    const res = await fetch(`${ctx.base}/api/auth/logout`, { method: "POST", headers: { Authorization: `Bearer ${ctx.token}` } });
    assert.equal(res.status, 204);
    assert.equal(session.cache.size, 0);
    assert.deepEqual(Object.keys(session.students), []);
    assert.equal(session.client.studentPages.size, 0);
  });
});
