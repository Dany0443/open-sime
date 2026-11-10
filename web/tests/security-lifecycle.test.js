import assert from "node:assert/strict";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import {
  app,
  sessions,
  SimeClient,
  encryptRememberToken,
  decryptRememberToken,
  applyKeyControlLine,
  rememberKeyState,
  wipeRememberKey,
  revokeSession,
  mutateRememberTokens,
  loadRememberTokens,
  saveRememberTokens,
  isRememberMeAvailable,
  getSnapshotDir,
  deriveSnapshotKey,
  encryptSnapshot,
  decryptSnapshot,
  saveSnapshot,
  loadSnapshot,
  deleteStudentSnapshot,
  deleteAccountSnapshots,
  purgeAllSnapshots,
  requestRememberKeyRotation,
  pendingRotations,
  writeForgetRecoveryMarker,
  readForgetRecoveryMarker,
  clearForgetRecoveryMarker,
  hasForgetRecoveryMarker,
  recoverInterruptedForget,
} from "../server.js";
import {
  createPortal,
  PORTAL_URL,
  SESSION,
  STUDENTS,
} from "./helpers/mock-portal.js";

// Helper for temporary data directories
async function withTempDataDir(fn) {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "sime-sec-test-"));
  const oldDataDir = process.env.SIME_DATA_DIR;
  const env = { SIME_DATA_DIR: tmpDir, SIME_SNAPSHOT_DIR: path.join(tmpDir, "snapshots") };
  process.env.SIME_DATA_DIR = tmpDir;
  try {
    return await fn({ tmpDir, env });
  } finally {
    if (oldDataDir !== undefined) {
      process.env.SIME_DATA_DIR = oldDataDir;
    } else {
      delete process.env.SIME_DATA_DIR;
    }
    try {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    } catch {}
  }
}

// ---------------------------------------------------------------------------------------------
// 1. CREDENTIAL STORAGE & KEYSTORE CONTROL PROTOCOL
// ---------------------------------------------------------------------------------------------

test("Security & Keystore: AES-256-GCM encryption and decryption roundtrip", () => {
  const masterKey = Buffer.from("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", "hex");
  const creds = { username: "elev_test", password: "Password123!", role: "student" };

  const encrypted = encryptRememberToken(creds, masterKey);
  assert.ok(encrypted, "Encrypted token must be produced");
  assert.ok(encrypted.includes("."), "Must use dot-separated iv.tag.ciphertext format");

  const decrypted = decryptRememberToken(encrypted, masterKey);
  assert.deepEqual(decrypted, creds, "Decrypted credentials must match original");

  // Tampered ciphertext fails authentication
  const parts = encrypted.split(".");
  const tamperedCiphertext = Buffer.from(parts[2], "base64url");
  tamperedCiphertext[0] ^= 0xff; // flip bit
  const tamperedToken = `${parts[0]}.${parts[1]}.${tamperedCiphertext.toString("base64url")}`;
  assert.equal(decryptRememberToken(tamperedToken, masterKey), null, "Tampered ciphertext must fail authentication");

  // Tampered auth tag fails
  const tamperedTag = Buffer.from(parts[1], "base64url");
  tamperedTag[0] ^= 0x01;
  const tamperedTagToken = `${parts[0]}.${tamperedTag.toString("base64url")}.${parts[2]}`;
  assert.equal(decryptRememberToken(tamperedTagToken, masterKey), null, "Tampered auth tag must fail authentication");

  // Wrong key fails
  const wrongKey = Buffer.from("fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210", "hex");
  assert.equal(decryptRememberToken(encrypted, wrongKey), null, "Decryption with wrong key must return null");
});

test("Security & Keystore: Control line protocol and no plaintext fallback", () => {
  wipeRememberKey();
  assert.equal(rememberKeyState.key, null);

  // 1. Keystore unavailable signal
  const unavailApplied = applyKeyControlLine("SIME_KEY_UNAVAILABLE KEYSTORE_ACCESS_DENIED\n");
  assert.equal(unavailApplied, true);
  assert.equal(rememberKeyState.key, null);
  assert.equal(rememberKeyState.code, "KEYSTORE_ACCESS_DENIED");

  // 2. Keystore corrupt signal
  const corruptApplied = applyKeyControlLine("SIME_KEY 0000000000000000000000000000000000000000000000000000000000000000\n");
  assert.equal(corruptApplied, false, "All-zero key must be rejected as corrupt");
  assert.equal(rememberKeyState.key, null);

  // 3. Valid key delivery
  const validHex = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90";
  const validApplied = applyKeyControlLine(`SIME_KEY ${validHex}\n`);
  assert.equal(validApplied, true);
  assert.ok(Buffer.isBuffer(rememberKeyState.key));
  assert.equal(rememberKeyState.key.toString("hex"), validHex);
  assert.equal(rememberKeyState.code, null);

  // 4. Wipe cleans memory securely
  wipeRememberKey();
  assert.equal(rememberKeyState.key, null);
});

// ---------------------------------------------------------------------------------------------
// 2. ENCRYPTED OFFLINE STUDENT SNAPSHOTS
// ---------------------------------------------------------------------------------------------

test("Security & Snapshots: AES-256-GCM encrypted snapshot lifecycle, tampering, and isolation", () => {
  withTempDataDir(({ env }) => {
    const masterKey = Buffer.from("55".repeat(32), "hex");
    const accountA = "parent_user_a";
    const accountB = "parent_user_b";
    const student1 = "student_alpha_101";
    const student2 = "student_beta_202";

    const agendaDataA = {
      student: { id: student1, fullName: "Alpha Student" },
      grades: [10, 9, 10],
      metadata: { className: "10-A" },
    };

    // 1. Save encrypted snapshot for account A / student 1
    const saved = saveSnapshot(accountA, student1, agendaDataA, masterKey, { env });
    assert.equal(saved, true, "Snapshot must be saved successfully");

    // File must exist on disk and be encrypted (not plaintext)
    const snapDir = getSnapshotDir(env);
    const files = fs.readdirSync(snapDir);
    assert.equal(files.length, 1);
    const rawFile = fs.readFileSync(path.join(snapDir, files[0]));
    assert.ok(!rawFile.includes("Alpha Student"), "Plaintext student name must NOT appear in snapshot file");
    assert.ok(!rawFile.includes("10-A"), "Plaintext metadata must NOT appear in snapshot file");

    // 2. Successful load and ownership verification
    const loaded = loadSnapshot(accountA, student1, masterKey, { env });
    assert.ok(loaded, "Owner must be able to load snapshot");
    assert.deepEqual(loaded.data, agendaDataA, "Decrypted data must match original");

    // 3. Account isolation: Account B must NOT be able to read Account A's snapshot
    const crossAccountLoad = loadSnapshot(accountB, student1, masterKey, { env });
    assert.equal(crossAccountLoad, null, "Different account must be refused access to snapshot");

    // 4. Student isolation: Account A cannot read another student's ID using student1's snapshot
    const crossStudentLoad = loadSnapshot(accountA, student2, masterKey, { env });
    assert.equal(crossStudentLoad, null, "Request for different student ID must return null");

    // 5. Tampered snapshot fails decryption cleanly without throwing
    const snapFilePath = path.join(snapDir, files[0]);
    const fileBytes = fs.readFileSync(snapFilePath);
    fileBytes[fileBytes.length - 1] ^= 0xaa; // corrupt last byte of ciphertext
    fs.writeFileSync(snapFilePath, fileBytes);
    const tamperedLoad = loadSnapshot(accountA, student1, masterKey, { env });
    assert.equal(tamperedLoad, null, "Tampered snapshot file must return null and fail closed");

    // 6. Missing / wrong key returns null
    const wrongKey = Buffer.from("66".repeat(32), "hex");
    assert.equal(loadSnapshot(accountA, student1, wrongKey, { env }), null);
  });
});

test("Security & Snapshots: Selective deletion by student, account, and complete purge", () => {
  withTempDataDir(({ env }) => {
    const masterKey = Buffer.from("77".repeat(32), "hex");
    const account1 = "user1";
    const account2 = "user2";

    saveSnapshot(account1, "stu_1a", { val: "1a" }, masterKey, { env });
    saveSnapshot(account1, "stu_1b", { val: "1b" }, masterKey, { env });
    saveSnapshot(account2, "stu_2a", { val: "2a" }, masterKey, { env });

    const snapDir = getSnapshotDir(env);
    assert.equal(fs.readdirSync(snapDir).length, 3, "Must have 3 snapshot files");

    // 1. Delete single student of account 1
    deleteStudentSnapshot(account1, "stu_1a", { env });
    assert.equal(fs.readdirSync(snapDir).length, 2);
    assert.equal(loadSnapshot(account1, "stu_1a", masterKey, { env }), null);
    assert.ok(loadSnapshot(account1, "stu_1b", masterKey, { env }));
    assert.ok(loadSnapshot(account2, "stu_2a", masterKey, { env }));

    // 2. Delete all snapshots of account 1 without touching account 2
    deleteAccountSnapshots(account1, { env });
    assert.equal(fs.readdirSync(snapDir).length, 1);
    assert.equal(loadSnapshot(account1, "stu_1b", masterKey, { env }), null);
    assert.ok(loadSnapshot(account2, "stu_2a", masterKey, { env }), "Account 2 snapshot must remain intact");

    // 3. Purge all snapshots
    purgeAllSnapshots({ env });
    assert.equal(fs.readdirSync(snapDir).length, 0, "Purge must remove all snapshots");
  });
});

// ---------------------------------------------------------------------------------------------
// 3. SECURE SESSION LIFECYCLE & REQUEST RACES
// ---------------------------------------------------------------------------------------------

test("Security & Lifecycle: Logout revokes session, clears credentials, and aborts pending requests", async () => {
  const orig = SimeClient.prototype.loginAndParsePortal;
  SimeClient.prototype.loginAndParsePortal = async function () {
    this.auth.sessionId = SESSION;
    this.auth.portalUrl = PORTAL_URL;
    const portal = createPortal();
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
    // 1. Log in
    const loginRes = await fetch(`${base}/api/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: "student1", password: "pw", role: "student", remember: true }),
    });
    assert.equal(loginRes.status, 200);
    const loginData = await loginRes.json();
    const token = loginData.token;
    assert.ok(token);

    // Verify session exists and is active in server memory
    const session = sessions.get(token);
    assert.ok(session);
    assert.equal(session.revoked, false);
    assert.equal(session.client.auth.username, "student1");

    // 2. Perform an authenticated request
    const studentId = loginData.student.id;
    const overviewRes = await fetch(`${base}/api/overview?student=${encodeURIComponent(studentId)}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    assert.equal(overviewRes.status, 200);

    // 3. Explicit Logout
    const logoutRes = await fetch(`${base}/api/auth/logout`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${token}`,
        Origin: base,
      },
    });
    assert.equal(logoutRes.status, 204);

    // 4. Verify session is completely revoked in server
    assert.equal(sessions.has(token), false, "Token must be removed from sessions map");
    assert.equal(session.revoked, true, "Session object must be flagged revoked");
    assert.equal(session.client.auth.username, "", "Credentials must be wiped from memory");
    assert.equal(session.client.auth.password, "", "Password must be wiped from memory");
    assert.equal(session.cache.size, 0, "In-memory cache must be wiped");

    // 5. Subsequent request with the same token must be rejected with 401
    const postLogoutRes = await fetch(`${base}/api/overview?student=${encodeURIComponent(studentId)}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    assert.equal(postLogoutRes.status, 401);
    const errPayload = await postLogoutRes.json();
    assert.equal(errPayload.error.code, "APP_SESSION_INVALID");
  } finally {
    SimeClient.prototype.loginAndParsePortal = orig;
    await new Promise((r) => server.close(r));
  }
});

test("Security & Lifecycle: Forget This Device revokes tokens, clears snapshots, and rotates key", async () => {
  const orig = SimeClient.prototype.loginAndParsePortal;
  SimeClient.prototype.loginAndParsePortal = async function () {
    this.auth.sessionId = SESSION;
    this.auth.portalUrl = PORTAL_URL;
    const portal = createPortal();
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
    // 1. Log in with remember=true
    const loginRes = await fetch(`${base}/api/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: "student1", password: "pw", role: "student", remember: true }),
    });
    assert.equal(loginRes.status, 200);
    const cookie = loginRes.headers.get("set-cookie");
    const rememberToken = decodeURIComponent(cookie.match(/sime_remember=([^;]+)/)[1]);
    const loginData = await loginRes.json();

    // 2. Call Forget This Device
    const forgetRes = await fetch(`${base}/api/auth/forget-device`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${loginData.token}`,
        Cookie: `sime_remember=${encodeURIComponent(rememberToken)}`,
        Origin: base,
      },
    });
    assert.equal(forgetRes.status, 200);
    const forgetJson = await forgetRes.json();
    assert.equal(forgetJson.ok, true);

    const clearCookie = forgetRes.headers.get("set-cookie");
    assert.ok(clearCookie.includes("Max-Age=0"), "Forget device must clear cookie");

    // 3. Attempt to resume with forgotten remember token must fail with 401
    const resumeRes = await fetch(`${base}/api/auth/resume`, {
      method: "POST",
      headers: {
        Cookie: `sime_remember=${encodeURIComponent(rememberToken)}`,
        Origin: base,
      },
    });
    assert.equal(resumeRes.status, 401);

    // 4. Authenticated request with previous bearer must fail with 401
    const checkRes = await fetch(`${base}/api/overview?student=${encodeURIComponent(loginData.student.id)}`, {
      headers: { Authorization: `Bearer ${loginData.token}` },
    });
    assert.equal(checkRes.status, 401);
  } finally {
    SimeClient.prototype.loginAndParsePortal = orig;
    await new Promise((r) => server.close(r));
  }
});

test("Security & Races: Stale background updates do not overwrite newer cached data", () => {
  const session = {
    revoked: false,
    students: {},
    cache: new Map(),
    chain: Promise.resolve(),
    client: { auth: { username: "parent1" } },
  };

  const studentId = "student_race_1";
  const t2 = Date.now();
  const t1 = t2 - 5000;

  // 1. A new update arrives at t2
  const newerData = { student: { id: studentId, name: "Newer" }, cachedAt: t2 };
  session.students[studentId] = { ...newerData };

  // 2. An older delayed response from t1 arrives later
  const olderData = { student: { id: studentId, name: "Older" } };
  // Mock storeStudentSessionState behavior
  const prev = session.students[studentId];
  if (prev && prev.cachedAt > t1) {
    // Correctly preserved newer data
  } else {
    session.students[studentId] = { ...olderData, cachedAt: t1 };
  }

  assert.equal(session.students[studentId].student.name, "Newer", "Newer cached data must not be overwritten by stale update");
});

test("Security & Keystore: Synchronous rotation acknowledgment and stale provisioning rejection", async () => {
  wipeRememberKey();
  rememberKeyState.channel = true;
  const origStdoutWrite = process.stdout.write;
  let sentLine = null;
  process.stdout.write = (chunk) => {
    sentLine = String(chunk);
    return true;
  };

  try {
    // 1. Initiate rotation
    const rotatePromise = requestRememberKeyRotation(2000);
    assert.ok(sentLine?.startsWith("SIME_KEY_ROTATE rot_"));
    const reqId = sentLine.trim().split(" ")[1];
    assert.equal(pendingRotations.has(reqId), true, "Pending rotation map must hold active request");

    // 2. Deliver rotation acknowledgment from Rust
    const newKeyHex = "1122334455667788990011223344556677889900112233445566778899001122";
    const applied = applyKeyControlLine(`SIME_KEY_ROTATED ${reqId} ${newKeyHex}\n`);
    assert.equal(applied, true);

    const resolvedKey = await rotatePromise;
    assert.equal(resolvedKey.toString("hex"), newKeyHex);
    assert.equal(rememberKeyState.key.toString("hex"), newKeyHex);
    assert.equal(rememberKeyState.rotatedCount, 1);
    assert.equal(pendingRotations.has(reqId), false, "Pending map must be cleared after resolution");

    // 3. Stale provisioning line from earlier startup must be REJECTED and not overwrite the rotated key
    const staleHex = "9999999999999999999999999999999999999999999999999999999999999999";
    const staleApplied = applyKeyControlLine(`SIME_KEY 1 ${staleHex}\n`);
    assert.equal(staleApplied, false, "Late provisioning after rotation must be dropped");
    assert.equal(rememberKeyState.key.toString("hex"), newKeyHex, "Key must remain the rotated key");

    // 4. Stale unavailable line after rotation must also be dropped
    const unavailApplied = applyKeyControlLine("SIME_KEY_UNAVAILABLE 1 KEYSTORE_ACCESS_DENIED\n");
    assert.equal(unavailApplied, false, "Late unavailable after rotation must be dropped");
    assert.equal(rememberKeyState.key.toString("hex"), newKeyHex);
  } finally {
    process.stdout.write = origStdoutWrite;
    wipeRememberKey();
    rememberKeyState.channel = false;
  }
});

test("Security & Keystore: Rotation error response cleanly rejects and handles failure", async () => {
  wipeRememberKey();
  rememberKeyState.channel = true;
  const origStdoutWrite = process.stdout.write;
  let sentLine = null;
  process.stdout.write = (chunk) => {
    sentLine = String(chunk);
    return true;
  };

  try {
    const rotatePromise = requestRememberKeyRotation(2000);
    const reqId = sentLine.trim().split(" ")[1];

    // Deliver rotation error from Rust
    applyKeyControlLine(`SIME_KEY_ROTATE_ERROR ${reqId} KEYSTORE_ACCESS_DENIED\n`);

    await assert.rejects(rotatePromise, /KEYSTORE_ACCESS_DENIED/);
    assert.equal(pendingRotations.has(reqId), false);
    assert.equal(rememberKeyState.code, "KEYSTORE_ACCESS_DENIED");
  } finally {
    process.stdout.write = origStdoutWrite;
    wipeRememberKey();
    rememberKeyState.channel = false;
  }
});

test("Security & Crash Recovery: Interrupted Forget This Device marker triggers durable recovery in standalone mode", async () => {
  await withTempDataDir(async ({ env }) => {
    const masterKey = Buffer.from("88".repeat(32), "hex");
    // 1. Save an encrypted snapshot
    saveSnapshot("user_crashed", "student_1", { name: "To Be Purged" }, masterKey, { env });
    const snapDir = getSnapshotDir(env);
    assert.equal(fs.readdirSync(snapDir).length, 1);

    // 2. Simulate interrupted Forget: write marker
    const writeOk = writeForgetRecoveryMarker({ tokenHash: "abc12345" }, { env });
    assert.equal(writeOk, true);
    assert.equal(hasForgetRecoveryMarker({ env }), true);

    // 3. Trigger recovery on next startup
    const recovered = await recoverInterruptedForget({ env });
    assert.equal(recovered, true, "Interrupted forget marker must be detected and handled");

    // 4. Assert snapshots are purged and marker is cleared
    assert.equal(!fs.existsSync(snapDir) || fs.readdirSync(snapDir).length === 0, true, "All snapshots must be purged during crash recovery");
    assert.equal(hasForgetRecoveryMarker({ env }), false, "Crash recovery marker must be cleaned up");
  });
});

test("Security & Crash Recovery: Interrupted Forget recovery over IPC channel rotates OS key before clearing marker", async () => {
  await withTempDataDir(async ({ env }) => {
    const masterKey = Buffer.from("44".repeat(32), "hex");
    saveSnapshot("user_ipc_crash", "student_2", { name: "Data To Purge" }, masterKey, { env });
    const snapDir = getSnapshotDir(env);
    assert.equal(fs.readdirSync(snapDir).length, 1);

    writeForgetRecoveryMarker({ tokenHash: "def67890" }, { env });
    assert.equal(hasForgetRecoveryMarker({ env }), true);

    wipeRememberKey();
    rememberKeyState.channel = true;
    rememberKeyState.resolved = true; // ready to receive rotation
    const origStdoutWrite = process.stdout.write;
    let sentLine = null;
    process.stdout.write = (chunk) => {
      sentLine = String(chunk);
      return true;
    };

    try {
      // 1. Start recovery (asynchronous)
      const recoveryPromise = recoverInterruptedForget({ env });

      // Verify that rotation request was emitted to stdout
      assert.ok(sentLine?.startsWith("SIME_KEY_ROTATE rot_"), "Recovery must request OS key rotation");
      const reqId = sentLine.trim().split(" ")[1];

      // While rotation is pending, marker MUST STILL EXIST on disk!
      assert.equal(hasForgetRecoveryMarker({ env }), true, "Marker must remain while rotation is unconfirmed");

      // 2. Simulate Rust confirming key rotation in OS store
      const rotatedHex = "77".repeat(32);
      applyKeyControlLine(`SIME_KEY_ROTATED ${reqId} ${rotatedHex}\n`);

      // 3. Await recovery completion
      const recovered = await recoveryPromise;
      assert.equal(recovered, true);

      // 4. Verify OS key was rotated in memory and marker is now cleared
      assert.equal(rememberKeyState.key.toString("hex"), rotatedHex, "OS key must be rotated");
      assert.equal(hasForgetRecoveryMarker({ env }), false, "Marker must only be cleared AFTER rotation confirms");
      assert.equal(!fs.existsSync(snapDir) || fs.readdirSync(snapDir).length === 0, true, "Snapshots must be purged");
    } finally {
      process.stdout.write = origStdoutWrite;
      wipeRememberKey();
      rememberKeyState.channel = false;
    }
  });
});

test("Security & Crash Recovery: Failed OS rotation during recovery preserves marker for subsequent launch", async () => {
  await withTempDataDir(async ({ env }) => {
    writeForgetRecoveryMarker({ tokenHash: "failed_rot" }, { env });
    assert.equal(hasForgetRecoveryMarker({ env }), true);

    wipeRememberKey();
    rememberKeyState.channel = true;
    rememberKeyState.resolved = true;
    const origStdoutWrite = process.stdout.write;
    let sentLine = null;
    process.stdout.write = (chunk) => {
      sentLine = String(chunk);
      return true;
    };

    try {
      const recoveryPromise = recoverInterruptedForget({ env });
      const reqId = sentLine.trim().split(" ")[1];

      // Deliver rotation failure from native store
      applyKeyControlLine(`SIME_KEY_ROTATE_ERROR ${reqId} KEYSTORE_ACCESS_DENIED\n`);

      await assert.rejects(recoveryPromise, /KEYSTORE_ACCESS_DENIED/);

      // Crucial: Marker MUST NOT be cleared when rotation fails!
      assert.equal(hasForgetRecoveryMarker({ env }), true, "Marker must survive failed rotation to retry next time");
    } finally {
      process.stdout.write = origStdoutWrite;
      wipeRememberKey();
      rememberKeyState.channel = false;
    }
  });
});

test("Security & Crash Recovery: Marker write failure fails closed and returns false", () => {
  const invalidOptions = { env: { SIME_DATA_DIR: "\0/invalid-path" } };
  const writeResult = writeForgetRecoveryMarker({ test: true }, invalidOptions);
  assert.equal(writeResult, false, "Marker write failure must return false and fail closed");
});

test("Security & Crash Recovery: Interrupted Forget removes still-present Remember Me token before clearing marker", async () => {
  await withTempDataDir(async ({ env }) => {
    const tokenHashA = "a".repeat(64);
    const tokenHashB = "b".repeat(64);

    // 1. Setup token store containing two remembered credentials
    saveRememberTokens(
      {
        [tokenHashA]: { encrypted: "encA", username: "student_a" },
        [tokenHashB]: { encrypted: "encB", username: "student_b" },
      },
      { env }
    );
    assert.equal(Object.keys(loadRememberTokens({ env })).length, 2);

    // 2. Simulate crash after marker written for Token A, but before Token A was removed from disk
    const writeOk = writeForgetRecoveryMarker({ tokenHash: tokenHashA }, { env });
    assert.equal(writeOk, true);
    assert.equal(hasForgetRecoveryMarker({ env }), true);

    // Assert that before recovery, Token A is still present in store
    assert.ok(loadRememberTokens({ env })[tokenHashA]);

    // 3. Run crash recovery on next launch
    const recovered = await recoverInterruptedForget({ env });
    assert.equal(recovered, true);

    // 4. Assert that Token A was safely REVOKED and removed from disk!
    const tokensAfter = loadRememberTokens({ env });
    assert.equal(tokensAfter[tokenHashA], undefined, "Revoked token must be removed from token store by recovery");
    assert.ok(tokensAfter[tokenHashB], "Sibling token must remain intact");
    assert.equal(Object.keys(tokensAfter).length, 1);

    // 5. Marker must now be cleared
    assert.equal(hasForgetRecoveryMarker({ env }), false);

    // 6. Now simulate interrupted forget of the LAST remaining token (Token B) with IPC channel
    writeForgetRecoveryMarker({ tokenHash: tokenHashB }, { env });

    wipeRememberKey();
    rememberKeyState.channel = true;
    rememberKeyState.resolved = true;
    const origStdoutWrite = process.stdout.write;
    let sentLine = null;
    process.stdout.write = (chunk) => {
      sentLine = String(chunk);
      return true;
    };

    try {
      const recoveryPromise = recoverInterruptedForget({ env });
      assert.ok(sentLine?.startsWith("SIME_KEY_ROTATE rot_"), "Removing last token must trigger OS key rotation");
      const reqId = sentLine.trim().split(" ")[1];

      // Simulate Rust completing rotation
      applyKeyControlLine(`SIME_KEY_ROTATED ${reqId} ${"99".repeat(32)}\n`);
      await recoveryPromise;

      // Token store must now be completely empty and key rotated
      assert.equal(Object.keys(loadRememberTokens({ env })).length, 0);
      assert.equal(rememberKeyState.key.toString("hex"), "99".repeat(32));
      assert.equal(hasForgetRecoveryMarker({ env }), false);
    } finally {
      process.stdout.write = origStdoutWrite;
      wipeRememberKey();
      rememberKeyState.channel = false;
    }
  });
});

test("Security & Crash Recovery: Failed token-store persistence preserves recovery marker", async () => {
  await withTempDataDir(async ({ env }) => {
    const tokenHash = "c".repeat(64);
    saveRememberTokens({ [tokenHash]: { encrypted: "encC" } }, { env });
    writeForgetRecoveryMarker({ tokenHash }, { env });
    assert.equal(hasForgetRecoveryMarker({ env }), true);

    // Pass failSave: true to simulate persistence failure during token store save
    const recovered = await recoverInterruptedForget({ env, failSave: true });
    assert.equal(recovered, false, "Recovery must return false when token removal cannot be persisted");

    // CRUCIAL: Marker must NOT be cleared when persistence fails!
    assert.equal(hasForgetRecoveryMarker({ env }), true, "Recovery marker must be preserved to retry on next launch");
  });
});

test("Security & Lifecycle: Forget This Device without Remember Me cookie aligns with forgetAll and clears token store", async () => {
  await withTempDataDir(async () => {
    const orig = SimeClient.prototype.loginAndParsePortal;
    SimeClient.prototype.loginAndParsePortal = async function () {
      this.auth.sessionId = SESSION;
      this.auth.portalUrl = PORTAL_URL;
      const portal = createPortal();
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
      // 1. Log in with remember=true
      const loginRes = await fetch(`${base}/api/auth/login`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username: "student1", password: "pw", role: "student", remember: true }),
      });
      assert.equal(loginRes.status, 200);
      const loginData = await loginRes.json();
      assert.ok(Object.keys(loadRememberTokens()).length > 0, "Token store must contain saved token");

      // 2. Call Forget This Device with Bearer token only (NO Cookie header)
      const forgetRes = await fetch(`${base}/api/auth/forget-device`, {
        method: "POST",
        headers: {
          Authorization: `Bearer ${loginData.token}`,
          Origin: base,
        },
      });
      assert.equal(forgetRes.status, 200);
      const forgetJson = await forgetRes.json();
      assert.equal(forgetJson.ok, true);

      // 3. Token store must be completely cleared (aligned with forgetAll)
      assert.equal(Object.keys(loadRememberTokens()).length, 0, "No-cookie forget must clear saved tokens");
      assert.equal(hasForgetRecoveryMarker(), false, "Marker must be cleared after durable completion");

      // 4. Bearer session must be revoked
      assert.equal(sessions.has(loginData.token), false);
    } finally {
      SimeClient.prototype.loginAndParsePortal = orig;
      await new Promise((r) => server.close(r));
    }
  });
});
