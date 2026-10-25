import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import {
  BROWSER_PROFILE,
  extractPageFlowInfo,
  scrubLog,
  encryptRememberToken,
  decryptRememberToken,
  app,
} from "../server.js";

test("W11: public/app.js does not store plaintext passwords", () => {
  const appJs = fs.readFileSync(new URL("../public/app.js", import.meta.url), "utf-8");
  assert.ok(!appJs.includes('creds: "open_sime_creds"'), "Must not declare creds storage key");
  assert.ok(!appJs.includes("silentLogin"), "Must not have silentLogin function");
  assert.ok(!appJs.match(/store\.set\([^)]*password/i), "Must not store password in localStorage");
});

test("W11: scrubLog strips passwords, tokens, cookies, and checksums", () => {
  const sampleJson = JSON.stringify({ password: "mySuperSecret123", token: "tok-abc", user: "elev1" });
  const scrubbedJson = scrubLog(sampleJson);
  assert.ok(!scrubbedJson.includes("mySuperSecret123"), "Password must be redacted");
  assert.ok(scrubbedJson.includes("[REDACTED]"));

  const sampleUrl = "https://agenda.sime.md/ords/f?p=104:1:123::NO::&cs=3A9F5E12B7C80&password=secret";
  const scrubbedUrl = scrubLog(sampleUrl);
  assert.ok(!scrubbedUrl.includes("3A9F5E12B7C80"), "Checksum must be redacted");
  assert.ok(!scrubbedUrl.includes("password=secret"), "Password in query must be redacted");

  const sampleObj = {
    username: "parent1",
    password: "secretPassword",
    token: "session-xyz",
    cookie: "ora_wwv_apex=abc123",
    safeData: "valid data",
  };
  const scrubbedObj = scrubLog(sampleObj);
  assert.equal(scrubbedObj.password, "[REDACTED]");
  assert.equal(scrubbedObj.token, "[REDACTED]");
  assert.equal(scrubbedObj.cookie, "[REDACTED]");
  assert.equal(scrubbedObj.safeData, "valid data");
  assert.equal(scrubbedObj.username, "parent1");
});

test("W11: remember-me encryption and decryption roundtrip with AES-256-GCM", () => {
  const secret = "a-very-strong-secret-key-32-chars-long";
  const creds = { username: "user_test", password: "password_test", role: "parent" };

  const encrypted = encryptRememberToken(creds, secret);
  assert.ok(encrypted && typeof encrypted === "string");
  const parts = encrypted.split(".");
  assert.equal(parts.length, 3, "AES-256-GCM token must have iv.tag.ciphertext");

  const decrypted = decryptRememberToken(encrypted, secret);
  assert.deepEqual(decrypted, creds);

  const decryptedWrongSecret = decryptRememberToken(encrypted, "wrong-secret-key");
  assert.equal(decryptedWrongSecret, null, "Must fail with wrong secret");

  const tampered = parts[0] + "." + parts[1] + ".tampered" + parts[2].slice(8);
  const decryptedTampered = decryptRememberToken(tampered, secret);
  assert.equal(decryptedTampered, null, "Must fail with tampered token");
});

test("W11: extractPageFlowInfo reads pFlowId and pFlowStepId from page or falls back", () => {
  const customHtml = '<div><input id="pFlowId" value="108"><input id="pFlowStepId" value="4"></div>';
  const parsed = extractPageFlowInfo(customHtml);
  assert.equal(parsed.flowId, "108");
  assert.equal(parsed.flowStepId, "4");

  const emptyParsed = extractPageFlowInfo("<div>No inputs here</div>");
  assert.equal(emptyParsed.flowId, "104");
  assert.equal(emptyParsed.flowStepId, "1");
});

test("W11: single fixed BROWSER_PROFILE replaces rotation", async () => {
  const serverJs = fs.readFileSync(new URL("../server.js", import.meta.url), "utf-8");
  assert.ok(!serverJs.includes("FINGERPRINTS ="), "FINGERPRINTS array must be removed");
  assert.ok(!serverJs.includes("function pickFingerprint"), "pickFingerprint must be removed");
  assert.ok(BROWSER_PROFILE && typeof BROWSER_PROFILE === "object");
  assert.ok(BROWSER_PROFILE.userAgent.includes("Chrome/"));
});

test("W11: security headers include X-Frame-Options and HSTS on HTTPS", async () => {
  const server = await new Promise((resolve) => {
    const s = app.listen(0, "127.0.0.1", () => resolve(s));
  });
  const port = server.address().port;
  try {
    const res = await fetch(`http://127.0.0.1:${port}/`, {
      headers: { "X-Forwarded-Proto": "https" },
    });
    assert.equal(res.headers.get("x-frame-options"), "DENY");
    assert.equal(res.headers.get("x-content-type-options"), "nosniff");
    assert.ok(res.headers.get("strict-transport-security")?.includes("max-age="));
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});

test("W11: THEME_LABELS and THEME_ICONS have no duplicate keys and support system", () => {
  const appJs = fs.readFileSync(new URL("../public/app.js", import.meta.url), "utf-8");
  assert.ok(!appJs.includes('light: "automată", light: "luminoasă"'), "Must remove duplicate light key");
  assert.ok(!appJs.includes('system: "automată"'), "Binary theme has no system option in settings");
});
