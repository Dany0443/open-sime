import assert from "node:assert/strict";
import fs from "node:fs";
import http from "node:http";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { app, SimeClient } from "../server.js";
import { createPortal, PORTAL_URL, SESSION, STUDENTS } from "./helpers/mock-portal.js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

function makeMockPortal() {
  const portal = createPortal();
  const s = STUDENTS.A;
  const studentPage =
    `<input id="pFlowId" value="104"><input id="pFlowStepId" value="1"><input id="pSalt" value="SALT"><input id="pPageItemsProtected" value="PROT">` +
    `<input id="P1_ELEVE_ID" value="${s.pid}"><input type="hidden" data-for="P1_ELEVE_ID" value="CK_${s.pid}">` +
    `<input id="P1_ELEVE" value="${s.name}"><input id="P1_CLASS_NAME" value="${s.cls}">` +
    `<input id="P1_INSTITUTION_NAME" value="${s.school}"><input id="P1_DIRIGINTE" value="${s.master}">` +
    `<input id="P1_MOTIVATED" value="0"><input id="P1_UNMOTIVATED" value="0"><input id="P1_HOMEWORK" value="[]">` +
    `<script>apex.widget.fullCalendar({"x":1,"ajaxIdentifier":"CALAJAX"});apex.widget.report.init("sem1","SEM1AJAX",{});apex.widget.report.init("sem2","SEM2AJAX",{});</script>`;

  portal.request = async (method, url, opts = {}) => {
    if (method === "POST") {
      const ajax = String(opts.data?.p_request || "");
      if (ajax.includes("CALAJAX")) {
        return { status: 200, url, text: "[]" };
      }
      if (ajax.includes("SEM1AJAX") || ajax.includes("SEM2AJAX")) {
        return { status: 200, url, text: "<table></table>" };
      }
      return { status: 200, url, text: "" };
    }
    return { status: 200, url, text: studentPage };
  };

  return portal;
}

async function withServer(fn) {
  const portal = makeMockPortal();
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
    return await fn({ base });
  } finally {
    SimeClient.prototype.loginAndParsePortal = orig;
    await new Promise((r) => server.close(r));
  }
}

test("Item 4: Browser storage audit verifies no credentials stored in browser storage", () => {
  const appJs = fs.readFileSync(path.join(__dirname, "../public/app.js"), "utf8");
  const html = fs.readFileSync(path.join(__dirname, "../index.html"), "utf8");

  // 1. Password must NEVER be referenced in localStorage or sessionStorage calls
  assert.ok(!appJs.match(/localStorage\.setItem\([^)]*password/i), "localStorage must not store password");
  assert.ok(!appJs.match(/sessionStorage\.setItem\([^)]*password/i), "sessionStorage must not store password");
  assert.ok(!appJs.match(/store\.set\([^)]*password/i), "store helper must not store password");
  assert.ok(!appJs.match(/document\.cookie\s*=\s*[^;]*password/i), "document.cookie must not store password");

  // 2. Remember token must NEVER be accessible or saved to client-side storage
  assert.ok(!appJs.match(/store\.set\([^)]*sime_remember/i), "Client must not store sime_remember");
  assert.ok(!appJs.match(/localStorage\.setItem\([^)]*sime_remember/i), "Client must not store sime_remember");

  // 3. Checkbox in index.html must be unchecked by default
  const checkboxMatch = html.match(/<input[^>]*id="auth-remember"[^>]*>/i);
  assert.ok(checkboxMatch, "Remember-me checkbox must exist in index.html");
  assert.ok(!checkboxMatch[0].includes("checked"), "Remember-me checkbox must NOT be checked by default");

  // 4. Forget device button must exist in index.html settings dialog
  assert.ok(html.includes('id="forget-device-btn"'), "forget-device-btn must exist in settings dialog");
});

test("Item 4: Token security - cookie is HttpOnly, SameSite=Lax, Path=/api/auth and value is opaque token", async () => {
  await withServer(async ({ base }) => {
    const res = await fetch(`${base}/api/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: "student1", password: "pw", role: "student", remember: true }),
    });

    assert.equal(res.status, 200);
    const cookie = res.headers.get("set-cookie");
    assert.ok(cookie, "Login with remember=true must emit Set-Cookie");
    assert.ok(cookie.includes("sime_remember="), "Cookie name must be sime_remember");
    assert.ok(cookie.includes("HttpOnly"), "Cookie must be HttpOnly");
    assert.ok(/SameSite=Lax/i.test(cookie), "Cookie must have SameSite=Lax");
    assert.ok(cookie.includes("Path=/api/auth"), "Cookie path must be /api/auth");

    // Token must be an opaque random string (e.g. 32-44 base64url characters), NOT a 3-part AES ciphertext or plaintext
    const match = cookie.match(/sime_remember=([^;]+)/);
    const tokenVal = decodeURIComponent(match[1]);
    assert.ok(tokenVal.length >= 32, "Token must be at least 32 characters");
    assert.ok(!tokenVal.includes("student1"), "Token must not contain username");
    assert.ok(!tokenVal.includes("pw"), "Token must not contain password");
    assert.ok(tokenVal.split(".").length < 3, "Token must be opaque, not client ciphertext");
  });
});

test("Item 4: Resume success - returns session and rotates cookie with single-use semantics", async () => {
  await withServer(async ({ base }) => {
    // 1. Initial login with remember
    const loginRes = await fetch(`${base}/api/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: "student1", password: "pw", role: "student", remember: true }),
    });
    assert.equal(loginRes.status, 200);
    const cookie1 = loginRes.headers.get("set-cookie");
    const token1 = decodeURIComponent(cookie1.match(/sime_remember=([^;]+)/)[1]);

    // 2. Resume call with cookie1
    const resumeRes1 = await fetch(`${base}/api/auth/resume`, {
      method: "POST",
      headers: {
        Cookie: `sime_remember=${encodeURIComponent(token1)}`,
        Origin: base,
      },
    });

    assert.equal(resumeRes1.status, 200);
    const resumePayload1 = await resumeRes1.json();
    assert.ok(resumePayload1.token, "Resume must return session token");
    assert.equal(resumePayload1.role, "student");
    assert.ok(resumePayload1.data?.student?.fullName);

    // 3. Token rotation: resume must issue a new cookie
    const cookie2 = resumeRes1.headers.get("set-cookie");
    assert.ok(cookie2, "Resume must rotate cookie");
    const token2 = decodeURIComponent(cookie2.match(/sime_remember=([^;]+)/)[1]);
    assert.notEqual(token1, token2, "New cookie token must differ from old cookie token");

    // 4. Old token (token1) is now invalidated
    const resumeReplay = await fetch(`${base}/api/auth/resume`, {
      method: "POST",
      headers: {
        Cookie: `sime_remember=${encodeURIComponent(token1)}`,
        Origin: base,
      },
    });
    assert.equal(resumeReplay.status, 401, "Replaying rotated token must fail with 401");

    // 5. New token (token2) succeeds
    const resumeRes2 = await fetch(`${base}/api/auth/resume`, {
      method: "POST",
      headers: {
        Cookie: `sime_remember=${encodeURIComponent(token2)}`,
        Origin: base,
      },
    });
    assert.equal(resumeRes2.status, 200, "New rotated token must succeed");
  });
});

test("Item 4: Inactivity expiration rejects tokens older than 30 days", async () => {
  await withServer(async ({ base }) => {
    const loginRes = await fetch(`${base}/api/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: "student1", password: "pw", role: "student", remember: true }),
    });
    assert.equal(loginRes.status, 200);
    const cookie = loginRes.headers.get("set-cookie");
    const token = decodeURIComponent(cookie.match(/sime_remember=([^;]+)/)[1]);

    // Simulate token store age by tampering with lastUsedAt in data/remember-tokens.json
    const tokensFile = path.resolve("data/remember-tokens.json");
    if (fs.existsSync(tokensFile)) {
      const data = JSON.parse(fs.readFileSync(tokensFile, "utf8"));
      for (const k of Object.keys(data)) {
        data[k].lastUsedAt = Date.now() - (31 * 24 * 3600 * 1000); // 31 days old
      }
      fs.writeFileSync(tokensFile, JSON.stringify(data), "utf8");
    }

    const resumeRes = await fetch(`${base}/api/auth/resume`, {
      method: "POST",
      headers: {
        Cookie: `sime_remember=${encodeURIComponent(token)}`,
        Origin: base,
      },
    });

    assert.equal(resumeRes.status, 401, "Expired token must be rejected with 401");
    const clearCookie = resumeRes.headers.get("set-cookie");
    assert.ok(clearCookie && clearCookie.includes("Max-Age=0"), "Expired token response must clear cookie with Max-Age=0");
  });
});

test("Item 4: Revocation via 'forget device' removes server record and clears cookie", async () => {
  await withServer(async ({ base }) => {
    const loginRes = await fetch(`${base}/api/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: "student1", password: "pw", role: "student", remember: true }),
    });
    assert.equal(loginRes.status, 200);
    const cookie = loginRes.headers.get("set-cookie");
    const token = decodeURIComponent(cookie.match(/sime_remember=([^;]+)/)[1]);

    // Call forget-device
    const forgetRes = await fetch(`${base}/api/auth/forget-device`, {
      method: "POST",
      headers: {
        Cookie: `sime_remember=${encodeURIComponent(token)}`,
        Origin: base,
      },
    });
    assert.equal(forgetRes.status, 200);
    const clearCookie = forgetRes.headers.get("set-cookie");
    assert.ok(clearCookie && clearCookie.includes("Max-Age=0"), "forget-device must clear cookie with Max-Age=0");

    // Subsequent resume fails
    const resumeRes = await fetch(`${base}/api/auth/resume`, {
      method: "POST",
      headers: {
        Cookie: `sime_remember=${encodeURIComponent(token)}`,
        Origin: base,
      },
    });
    assert.equal(resumeRes.status, 401, "Resume with revoked token must fail with 401");
  });
});

test("Item 4: Origin enforcement blocks cross-site resume requests", async () => {
  await withServer(async ({ base }) => {
    const loginRes = await fetch(`${base}/api/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: "student1", password: "pw", role: "student", remember: true }),
    });
    assert.equal(loginRes.status, 200);
    const cookie = loginRes.headers.get("set-cookie");
    const token = decodeURIComponent(cookie.match(/sime_remember=([^;]+)/)[1]);

    // Cross-origin request
    const crossOriginRes = await fetch(`${base}/api/auth/resume`, {
      method: "POST",
      headers: {
        Cookie: `sime_remember=${encodeURIComponent(token)}`,
        Origin: "https://evil-site.com",
      },
    });
    assert.equal(crossOriginRes.status, 403, "Cross-origin request must be blocked with 403");

    // Sec-Fetch-Site cross-site
    const crossSiteRes = await fetch(`${base}/api/auth/resume`, {
      method: "POST",
      headers: {
        Cookie: `sime_remember=${encodeURIComponent(token)}`,
        Origin: base,
        "Sec-Fetch-Site": "cross-site",
      },
    });
    assert.equal(crossSiteRes.status, 403, "Sec-Fetch-Site cross-site must be blocked with 403");
  });
});

