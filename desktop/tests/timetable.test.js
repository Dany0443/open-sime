import assert from "node:assert/strict";
import { test } from "node:test";
import { createPortal, loggedInClient, withPortalServer, STUDENTS } from "../../web/tests/helpers/mock-portal.js";
import { SimeNetworkError, SimeParseError } from "../../web/server.js";

const today = () => {
  const parts = new Intl.DateTimeFormat("en-GB", { timeZone: "Europe/Chisinau", year: "numeric", month: "2-digit", day: "2-digit" }).formatToParts(new Date());
  const values = Object.fromEntries(parts.map(p => [p.type, p.value]));
  return `${values.year}-${values.month}-${values.day}`;
};

// Controlled failures use the existing synthetic portal, never live account captures.
for (const [label, body] of [
  ["invalid JSON", "<html>unavailable</html>"],
  ["unexpected object", '{"error":"expired"}'],
  ["discarded event", '[{"start":"invalid","end":"invalid","title":"Lesson"}]'],
  ["partly parsed array", null],
  ["unrecognized lesson", JSON.stringify([{start: `${today()}T08:00:00`, end: `${today()}T08:45:00`}])],
  ["wrong period", JSON.stringify([{start: "2000-01-03T08:00:00", end: "2000-01-03T08:45:00", title: "Lesson"}])],
]) {
  test(`Desktop calendar: ${label} fails rather than confirming an empty week`, async () => {
    const portal = createPortal();
    const request = portal.request;
    portal.request = async (method, url, options) => {
      const response = await request(method, url, options);
      if (String(options?.data?.p_request).includes("CALAJAX")) {
        response.text = body ?? JSON.stringify([...JSON.parse(response.text), {start: "invalid"}]);
      }
      return response;
    };
    const client = await loggedInClient(portal);
    await assert.rejects(client.getWeek(STUDENTS.A.nav, today(), {strict: true}), SimeParseError);
    assert.equal(portal.count(/CALAJAX/), 1, "Parse failures must not cause automatic calendar retries");
  });
}

test("Desktop calendar: explicit successful empty array confirms an empty week", async () => {
  const portal = createPortal();
  const client = await loggedInClient(portal);
  client.ajax = async () => "[]";
  const week = await client.getWeek(STUDENTS.A.nav, today(), {strict: true});
  assert.deepEqual(week.days, {});
  assert.match(week.weekStart, /^\d{4}-\d{2}-\d{2}$/);
});

test("Desktop calendar: cache isolation, delayed students, stale data and refresh recovery", async () => {
  const portal = createPortal({delayFor: info => info.ajax.includes("CALAJAX") ? 10 : 0});
  const request = portal.request;
  let failure = false;
  portal.request = async (method, url, options) => {
    if (failure && String(options?.data?.p_request).includes("CALAJAX")) throw new SimeNetworkError("Controlled timeout");
    return request(method, url, options);
  };
  await withPortalServer(portal, async ({get, session}) => {
    const route = `/api/schedule?desktop=1&date=${today()}`;
    const [a, b] = await Promise.all([get(route, STUDENTS.A.nav), get(route, STUDENTS.B.nav)]);
    assert.equal(a.status, 200);
    assert.equal(b.status, 200);
    assert.equal(a.body.data.days[today()][0].subject, STUDENTS.A.lessonSubject);
    assert.equal(b.body.data.days[today()][0].subject, STUDENTS.B.lessonSubject);
    const calls = portal.count(/CALAJAX/);
    const cached = await get(route, STUDENTS.A.nav);
    assert.deepEqual(cached.body.data, a.body.data);
    assert.equal(portal.count(/CALAJAX/), calls);
    const expire = () => {
      for (const [key, entry] of session.cache) if (key.startsWith("schedule:")) entry.at = Date.now() - 61_000;
    };
    expire();
    failure = true;
    const stale = await get(route, STUDENTS.A.nav);
    assert.equal(stale.status, 200);
    assert.equal(stale.body.cache.stale, true);
    assert.deepEqual(stale.body.data, a.body.data);
    failure = false;
    expire();
    const recovered = await get(route, STUDENTS.A.nav);
    assert.equal(recovered.status, 200);
    assert.equal(recovered.body.cache.stale, false);
    assert.deepEqual(recovered.body.data, a.body.data);
  });
});

test("Desktop calendar: unverified legacy cache cannot confirm empty; failed load can recover", async () => {
  const portal = createPortal();
  const request = portal.request;
  let malformed = true;
  portal.request = async (method, url, options) => {
    const response = await request(method, url, options);
    if (malformed && String(options?.data?.p_request).includes("CALAJAX")) {
      response.text = '[{"start":"invalid","end":"invalid","title":"Lesson"}]';
    }
    return response;
  };
  await withPortalServer(portal, async ({get}) => {
    const legacy = await get(`/api/schedule?date=${today()}`, STUDENTS.A.nav);
    assert.equal(legacy.status, 200);
    assert.deepEqual(legacy.body.data.days, {});
    const failed = await get(`/api/schedule?desktop=1&date=${today()}`, STUDENTS.A.nav);
    assert.equal(failed.status, 502);
    assert.ok(failed.body.error);
    malformed = false;
    const recovered = await get(`/api/schedule?desktop=1&date=${today()}`, STUDENTS.A.nav);
    assert.equal(recovered.status, 200);
    assert.equal(recovered.body.data.days[today()][0].subject, STUDENTS.A.lessonSubject);
    assert.equal(portal.count(/CALAJAX/), 3);
  });
});
