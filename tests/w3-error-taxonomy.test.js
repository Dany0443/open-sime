import assert from "node:assert/strict";
import { test } from "node:test";
import {
  app,
  CircuitBreaker,
  RateGate,
  sessions,
  SimeClient,
  SimeError,
  SimeForbiddenError,
  SimeHttp,
  SimeMaintenanceError,
  SimeNetworkError,
  SimeNotFoundError,
  SimeRateLimitError,
  SimeSessionExpiredError,
  SimeStudentNotFoundError,
  SimeUnexpectedPageError,
} from "../server.js";

test("W3: HTTP status codes and error taxonomy mapping in raiseForStatus", () => {
  const http = new SimeHttp({ gate: new RateGate({ sleep: async () => {} }), breaker: new CircuitBreaker() });

  // 403 maps to SimeForbiddenError, not SimeSessionExpiredError
  assert.throws(() => http.raiseForStatus(new Response(null, { status: 403 })), (err) => {
    assert.ok(err instanceof SimeForbiddenError);
    assert.ok(!(err instanceof SimeSessionExpiredError));
    return true;
  });

  // 404 maps to SimeNotFoundError, not SimeSessionExpiredError
  assert.throws(() => http.raiseForStatus(new Response(null, { status: 404 })), (err) => {
    assert.ok(err instanceof SimeNotFoundError);
    assert.ok(!(err instanceof SimeSessionExpiredError));
    return true;
  });

  // 429 maps to SimeRateLimitError
  assert.throws(() => http.raiseForStatus(new Response(null, { status: 429, headers: { "retry-after": "45" } })), (err) => {
    assert.ok(err instanceof SimeRateLimitError);
    assert.equal(err.waitSeconds, 45);
    return true;
  });

  // 503 maps to SimeMaintenanceError or SimeRateLimitError
  assert.throws(() => http.raiseForStatus(new Response(null, { status: 503, headers: { "retry-after": "60" } })), (err) => {
    assert.ok(err instanceof SimeRateLimitError || err instanceof SimeMaintenanceError);
    return true;
  });

  // 401 and 440 map to SimeSessionExpiredError
  assert.throws(() => http.raiseForStatus(new Response(null, { status: 401 })), (err) => err instanceof SimeSessionExpiredError);
  assert.throws(() => http.raiseForStatus(new Response(null, { status: 440 })), (err) => err instanceof SimeSessionExpiredError);
});

test("W3: simulated upstream 404 does not trigger re-login", async () => {
  const client = new SimeClient("testparent", "pass", "parent");
  let reloginCount = 0;
  client.relogin = async () => {
    reloginCount += 1;
  };

  await assert.rejects(
    () =>
      client.withRetry(async () => {
        client.http.raiseForStatus(new Response(null, { status: 404 }));
      }),
    (err) => err instanceof SimeNotFoundError
  );

  assert.equal(reloginCount, 0, "404 must not trigger re-login");
});

test("W3: simulated expiry triggers exactly one re-login for N concurrent requests", async () => {
  const client = new SimeClient("testparent", "pass", "parent");
  let reloginCalls = 0;
  let attempt = 0;

  client.executeHandshake = async () => {
    reloginCalls += 1;
    // Simulate re-login network delay
    await new Promise((r) => setTimeout(r, 20));
  };

  const operation = async () => {
    if (attempt < 5) {
      attempt += 1;
      throw new SimeSessionExpiredError("Portal session expired");
    }
    return "success";
  };

  // Launch 5 concurrent calls
  const results = await Promise.all([
    client.withRetry(operation),
    client.withRetry(operation),
    client.withRetry(operation),
    client.withRetry(operation),
    client.withRetry(operation),
  ]);

  assert.deepEqual(results, ["success", "success", "success", "success", "success"]);
  assert.equal(reloginCalls, 1, `Expected exactly 1 re-login for 5 concurrent requests, got ${reloginCalls}`);
});

test("W3: per-account circuit breaker does not block other users", () => {
  const brA = new CircuitBreaker();
  const brB = new CircuitBreaker();

  // Trip account A
  brA.trip(300);
  assert.ok(brA.remainingSeconds() > 0);
  assert.throws(() => brA.ensureClosed(), /suspended/);

  // Account B remains unaffected
  assert.equal(brB.remainingSeconds(), 0);
  assert.doesNotThrow(() => brB.ensureClosed());
});

test("W3: server returns standard error shape { error: { code, message, retryAfter } }", async () => {
  const server = app.listen(0, "127.0.0.1");
  await new Promise((resolve) => server.once("listening", resolve));
  const { port } = server.address();
  const baseUrl = `http://127.0.0.1:${port}`;

  try {
    // 1. APP_SESSION_INVALID (401 on data route without token)
    const noAuth = await fetch(`${baseUrl}/api/overview`);
    assert.equal(noAuth.status, 401);
    const noAuthBody = await noAuth.json();
    assert.ok(noAuthBody.error);
    assert.equal(noAuthBody.error.code, "APP_SESSION_INVALID");
    assert.ok(noAuthBody.error.message.includes("Sesiunea a expirat"));

    // 2. PORTAL_LOGIN_REJECTED (401 on bad credentials)
    const badLogin = await fetch(`${baseUrl}/api/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: "wrong", password: "bad", role: "parent" }),
    });
    // In test environment without upstream mock this might be network/parse or login rejected
    assert.ok(badLogin.status === 401 || badLogin.status === 502);
    const badLoginBody = await badLogin.json();
    assert.ok(badLoginBody.error);
    assert.ok(badLoginBody.error.code);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});
