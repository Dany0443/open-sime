import assert from "node:assert/strict";
import { test } from "node:test";
import { SimeClient, SimeStudentNotFoundError, SimeSessionExpiredError } from "../server.js";

const STUDENT_A_HTML =
  '<input id="P1_ELEVE_ID" value="101">' +
  '<input id="P1_ELEVE" value="Ion Ciobanu">' +
  '<input id="P1_CLASS_NAME" value="10A">' +
  '<div id="t_TreeNav"><ul>' +
  '<li data-current="true"><a href="/ords/f?p=AGENDA:1:555666::NO::P1_ELEVE_ID:5B1E59D6A1&cs=CS_ION">Ion Ciobanu</a></li>' +
  '<li><a href="/ords/f?p=AGENDA:1:555666::NO::P1_ELEVE_ID:8F7E6D5C4B&cs=CS_ELENA">Elena Ciobanu</a></li>' +
  '</ul></div>';

const STUDENT_B_HTML =
  '<input id="P1_ELEVE_ID" value="202">' +
  '<input id="P1_ELEVE" value="Elena Ciobanu">' +
  '<input id="P1_CLASS_NAME" value="8B">' +
  '<div id="t_TreeNav"><ul>' +
  '<li><a href="/ords/f?p=AGENDA:1:555666::NO::P1_ELEVE_ID:5B1E59D6A1&cs=CS_ION">Ion Ciobanu</a></li>' +
  '<li data-current="true"><a href="/ords/f?p=AGENDA:1:555666::NO::P1_ELEVE_ID:8F7E6D5C4B&cs=CS_ELENA">Elena Ciobanu</a></li>' +
  '</ul></div>';

test("W2: switching back and forth never emits checksum-less URLs and handles switches cleanly", async () => {
  const requestedUrls = [];
  const client = new SimeClient("testparent", "pass", "parent");

  // Mock authenticated state
  client.auth.sessionId = "777888";
  client.auth.portalUrl = "https://agenda.sime.md/ords/r/agenda/main/dashboard?session=777888";

  client.http.request = async (method, url) => {
    requestedUrls.push(url);
    if (url.includes("CS_ELENA")) {
      return { status: 200, url, text: STUDENT_B_HTML };
    }
    return { status: 200, url, text: STUDENT_A_HTML };
  };

  // Initial load
  await client.loadPortal();

  // Switch to Elena
  requestedUrls.length = 0;
  const elenaData = await client.switchStudent("8F7E6D5C4B");
  assert.equal(elenaData.student.fullName, "Elena Ciobanu");
  assert.ok(requestedUrls.length > 0);
  for (const u of requestedUrls) {
    assert.ok(u.includes("&cs="), `URL must include checksum: ${u}`);
    assert.ok(!u.match(/P1_ELEVE_ID:[^&]+$/), `URL must not end with bare unchecksummed ID: ${u}`);
  }

  // Switch back to Ion
  requestedUrls.length = 0;
  const ionData = await client.switchStudent("5B1E59D6A1");
  assert.equal(ionData.student.fullName, "Ion Ciobanu");
  assert.ok(requestedUrls.length > 0);
  for (const u of requestedUrls) {
    assert.ok(u.includes("&cs="), `URL must include checksum: ${u}`);
  }

  // Switch again to Elena
  requestedUrls.length = 0;
  const elenaData2 = await client.switchStudent("8F7E6D5C4B");
  assert.equal(elenaData2.student.fullName, "Elena Ciobanu");
  for (const u of requestedUrls) {
    assert.ok(u.includes("&cs="), `URL must include checksum: ${u}`);
  }
});

test("W2: failing switch throws clear student error, never session expired", async () => {
  const client = new SimeClient("testparent", "pass", "parent");
  client.auth.sessionId = "777888";
  client.auth.portalUrl = "https://agenda.sime.md/ords/r/agenda/main/dashboard?session=777888";

  // When switching, upstream returns page still showing Ion
  client.http.request = async (method, url) => {
    return { status: 200, url, text: STUDENT_A_HTML };
  };

  await client.loadPortal();

  // Switch to nonexistent student throws SimeStudentNotFoundError
  await assert.rejects(
    () => client.switchStudent("NONEXISTENT_ID"),
    (err) => {
      assert.ok(err instanceof SimeStudentNotFoundError);
      assert.ok(!(err instanceof SimeSessionExpiredError));
      return true;
    }
  );

  // If page never changes to requested student, fails with student error
  await assert.rejects(
    () => client.switchStudent("8F7E6D5C4B"),
    (err) => {
      assert.ok(err instanceof SimeStudentNotFoundError);
      assert.ok(!(err instanceof SimeSessionExpiredError));
      return true;
    }
  );
});
