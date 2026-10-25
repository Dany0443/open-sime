import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import {
  APP_IDENTIFIER,
  DEFAULT_DEV_PORT,
  DEFAULT_PROD_PORT,
  getRememberKeyPath,
  getRememberStorePath,
  isProductionSidecar,
  resolveDataDir,
} from "../server.js";
import { buildSidecar, getSidecarOutputPath, resolveTargetTriple } from "../scripts/build-sidecar.mjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DESKTOP_ROOT = path.resolve(__dirname, "..");
const SERVER_JS = path.join(DESKTOP_ROOT, "server.js");

test("Sidecar Build Script: target triple resolution and binary naming across all supported platforms", () => {
  assert.equal(
    resolveTargetTriple(["node", "build-sidecar.mjs", "--target", "x86_64-pc-windows-msvc"], {}),
    "x86_64-pc-windows-msvc"
  );
  assert.equal(
    resolveTargetTriple(["node", "build-sidecar.mjs", "--target=aarch64-apple-darwin"], {}),
    "aarch64-apple-darwin"
  );
  assert.equal(
    resolveTargetTriple(["node", "build-sidecar.mjs"], { TAURI_ENV_TARGET_TRIPLE: "x86_64-apple-darwin" }),
    "x86_64-apple-darwin"
  );
  assert.equal(
    resolveTargetTriple(["node", "build-sidecar.mjs"], { CARGO_BUILD_TARGET: "x86_64-unknown-linux-gnu" }),
    "x86_64-unknown-linux-gnu"
  );

  assert.ok(
    getSidecarOutputPath("x86_64-pc-windows-msvc").endsWith(
      path.join("src-tauri", "binaries", "open-sime-server-x86_64-pc-windows-msvc.exe")
    )
  );
  assert.ok(
    getSidecarOutputPath("x86_64-apple-darwin").endsWith(
      path.join("src-tauri", "binaries", "open-sime-server-x86_64-apple-darwin")
    )
  );
  assert.ok(
    getSidecarOutputPath("aarch64-apple-darwin").endsWith(
      path.join("src-tauri", "binaries", "open-sime-server-aarch64-apple-darwin")
    )
  );
  assert.ok(
    getSidecarOutputPath("x86_64-unknown-linux-gnu").endsWith(
      path.join("src-tauri", "binaries", "open-sime-server-x86_64-unknown-linux-gnu")
    )
  );
  assert.throws(() => getSidecarOutputPath("invalid-target-triple"), /Unsupported Rust target triple/);
});

test("Sidecar Storage: OS application-data directory resolution for remember-tokens and keys", () => {
  assert.equal(APP_IDENTIFIER, "md.sime.opensime");
  assert.equal(DEFAULT_DEV_PORT, 3000);
  assert.equal(DEFAULT_PROD_PORT, 17321);

  assert.equal(isProductionSidecar(["node", "server.js"], {}), false);
  assert.equal(isProductionSidecar(["node", "server.js", "--production"], {}), true);
  assert.equal(isProductionSidecar(["node", "server.js"], { SIME_DESKTOP_PRODUCTION: "1" }), true);

  // Dev mode uses local data/ directory
  const devDataDir = resolveDataDir({}, "win32", ["node", "server.js"]);
  assert.equal(devDataDir, path.join(DESKTOP_ROOT, "data"));

  // Explicit SIME_DATA_DIR override wins
  const customDir = path.join(os.tmpdir(), "custom-open-sime-data");
  assert.equal(resolveDataDir({ SIME_DATA_DIR: customDir }, "win32", ["node", "server.js", "--production"]), customDir);

  // Production mode uses OS app-data directories
  const winAppData = path.join("C:", "Users", "TestUser", "AppData", "Roaming");
  assert.equal(
    resolveDataDir({ APPDATA: winAppData }, "win32", ["node", "server.js", "--production"]),
    path.join(winAppData, "md.sime.opensime")
  );
  assert.equal(
    getRememberStorePath({ APPDATA: winAppData }, "win32", ["node", "server.js", "--production"]),
    path.join(winAppData, "md.sime.opensime", "remember-tokens.json")
  );
  assert.equal(
    getRememberKeyPath({ APPDATA: winAppData }, "win32", ["node", "server.js", "--production"]),
    path.join(winAppData, "md.sime.opensime", "remember-secret.key")
  );

  const macDataDir = resolveDataDir({}, "darwin", ["node", "server.js", "--production"]);
  assert.ok(macDataDir.endsWith(path.join("Library", "Application Support", "md.sime.opensime")));

  const linuxXdg = "/home/testuser/.local/share";
  assert.equal(
    resolveDataDir({ XDG_DATA_HOME: linuxXdg }, "linux", ["node", "server.js", "--production"]),
    path.join(linuxXdg, "md.sime.opensime")
  );
});

test("Sidecar Runtime: readiness handshake, loopback-only enforcement, port conflict detection, and clean stdin shutdown", async () => {
  const tmpDataDir = fs.mkdtempSync(path.join(os.tmpdir(), "sime-sidecar-runtime-"));

  // Allocate an available ephemeral port first
  const probe = http.createServer();
  await new Promise((r) => probe.listen(0, "127.0.0.1", r));
  const testPort = probe.address().port;
  await new Promise((r) => probe.close(r));

  try {
    // 1. Rejects non-loopback 0.0.0.0 in production mode
    const badHostProc = spawn(
      process.execPath,
      [SERVER_JS, "--production", "--host", "0.0.0.0", "--port", String(testPort)],
      {
        cwd: tmpDataDir,
        env: { ...process.env, SIME_DATA_DIR: tmpDataDir },
        stdio: ["pipe", "pipe", "pipe"],
      }
    );
    let badStderr = "";
    badHostProc.stderr.on("data", (d) => {
      badStderr += d.toString("utf8");
    });
    const badExitCode = await new Promise((r) => badHostProc.on("exit", r));
    assert.notEqual(badExitCode, 0, "Binding to 0.0.0.0 in production mode must fail");
    assert.ok(badStderr.includes("INVALID_HOST"), "Must emit INVALID_HOST error on non-loopback bind");

    // 2. Starts cleanly on 127.0.0.1, emits SIME_SIDECAR_READY, serves UI & API from isolated cwd
    const instanceId = "test-instance-nonce-42";
    const child = spawn(
      process.execPath,
      [SERVER_JS, "--production", "--watch-stdin", "--host", "127.0.0.1", "--port", String(testPort)],
      {
        cwd: tmpDataDir,
        env: {
          ...process.env,
          SIME_DATA_DIR: tmpDataDir,
          SIME_INSTANCE_ID: instanceId,
        },
        stdio: ["pipe", "pipe", "pipe"],
      }
    );

    const readyPayload = await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("Timed out waiting for SIME_SIDECAR_READY")), 8000);
      let stdoutBuf = "";
      child.stdout.on("data", (chunk) => {
        stdoutBuf += chunk.toString("utf8");
        for (const line of stdoutBuf.split(/\r?\n/)) {
          if (line.startsWith("SIME_SIDECAR_READY ")) {
            clearTimeout(timer);
            resolve(JSON.parse(line.slice("SIME_SIDECAR_READY ".length)));
          }
        }
      });
      child.on("exit", (code) => {
        clearTimeout(timer);
        reject(new Error(`Sidecar exited prematurely with code ${code}`));
      });
    });

    assert.equal(readyPayload.host, "127.0.0.1");
    assert.equal(readyPayload.port, testPort);
    assert.equal(readyPayload.url, `http://127.0.0.1:${testPort}`);
    assert.equal(readyPayload.instanceId, instanceId);

    const healthRes = await fetch(`${readyPayload.url}/api/health`);
    assert.equal(healthRes.status, 200);
    const health = await healthRes.json();
    assert.equal(health.ok, true);
    assert.equal(health.instanceId, instanceId);

    const htmlRes = await fetch(`${readyPayload.url}/`);
    assert.equal(htmlRes.status, 200);
    const html = await htmlRes.text();
    assert.ok(html.includes("<title>Open SIME</title>"));

    // 3. Port conflict detection: a second sidecar on the same port must fail with EADDRINUSE and exit non-zero
    const conflictProc = spawn(
      process.execPath,
      [SERVER_JS, "--production", "--host", "127.0.0.1", "--port", String(testPort)],
      {
        cwd: tmpDataDir,
        env: { ...process.env, SIME_DATA_DIR: tmpDataDir },
        stdio: ["pipe", "pipe", "pipe"],
      }
    );
    let conflictStderr = "";
    conflictProc.stderr.on("data", (d) => {
      conflictStderr += d.toString("utf8");
    });
    const conflictExitCode = await new Promise((r) => conflictProc.on("exit", r));
    assert.notEqual(conflictExitCode, 0, "Port conflict must exit with non-zero code");
    assert.ok(conflictStderr.includes("EADDRINUSE"), `Expected EADDRINUSE in stderr, got: ${conflictStderr}`);

    // 4. Clean shutdown when stdin closes
    const exitPromise = new Promise((r) => child.on("exit", r));
    child.stdin.end();
    const exitCode = await exitPromise;
    assert.equal(exitCode, 0, "Sidecar must exit cleanly with code 0 when stdin closes");
  } finally {
    fs.rmSync(tmpDataDir, { recursive: true, force: true });
  }
});

test("Packaged Sidecar Binary: runs standalone without Node.js in PATH and serves embedded assets", async (t) => {
  if (process.platform !== "win32" || process.arch !== "x64") {
    t.skip("Standalone Windows x64 binary execution test only runs on win32-x64 host");
    return;
  }

  const exePath = getSidecarOutputPath("x86_64-pc-windows-msvc");
  if (!fs.existsSync(exePath)) {
    await buildSidecar({ targetTriple: "x86_64-pc-windows-msvc" });
  }
  assert.ok(fs.existsSync(exePath), "Compiled Windows x64 sidecar binary must exist");

  // Verify stamped PE VersionInfo metadata for Task Manager grouping
  const ResEdit = await import("resedit");
  const exeBuf = fs.readFileSync(exePath);
  const pe = ResEdit.NtExecutable.from(exeBuf);
  const peRes = ResEdit.NtExecutableResource.from(pe);
  const viList = ResEdit.Resource.VersionInfo.fromEntries(peRes.entries);
  assert.ok(viList.length > 0, "Windows executable must contain VersionInfo resource");
  const vi = viList[0];
  const lang = vi.getAllLanguagesForStringValues()[0];
  const strings = vi.getStringValues(lang);
  assert.equal(strings.ProductName, "Open SIME", "ProductName must match Open SIME for Task Manager grouping");
  assert.equal(strings.FileDescription, "Open SIME Backend", "FileDescription must be Open SIME Backend");
  assert.equal(strings.CompanyName, "Open SIME Contributors", "CompanyName must be Open SIME Contributors");

  const emptyWorkDir = fs.mkdtempSync(path.join(os.tmpdir(), "sime-exe-workdir-"));
  const fakeAppData = fs.mkdtempSync(path.join(os.tmpdir(), "sime-exe-appdata-"));

  const probe = http.createServer();
  await new Promise((r) => probe.listen(0, "127.0.0.1", r));
  const testPort = probe.address().port;
  await new Promise((r) => probe.close(r));

  try {
    const child = spawn(
      exePath,
      ["--production", "--watch-stdin", "--host", "127.0.0.1", "--port", String(testPort)],
      {
        cwd: emptyWorkDir,
        env: {
          SystemRoot: process.env.SystemRoot || "C:\\Windows",
          PATH: "",
          APPDATA: fakeAppData,
          SIME_INSTANCE_ID: "exe-standalone-nonce",
        },
        stdio: ["pipe", "pipe", "pipe"],
      }
    );

    const ready = await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("Standalone binary startup timeout")), 8000);
      let buf = "";
      child.stdout.on("data", (chunk) => {
        buf += chunk.toString("utf8");
        for (const line of buf.split(/\r?\n/)) {
          if (line.startsWith("SIME_SIDECAR_READY ")) {
            clearTimeout(timer);
            resolve(JSON.parse(line.slice("SIME_SIDECAR_READY ".length)));
          }
        }
      });
      child.on("exit", (code) => {
        clearTimeout(timer);
        reject(new Error(`Standalone binary exited early with code ${code}`));
      });
    });

    assert.equal(ready.port, testPort);
    assert.equal(ready.instanceId, "exe-standalone-nonce");

    // Verify embedded assets are served even from an empty working directory with PATH=""
    for (const assetPath of [
      "/",
      "/sw.js",
      "/public/app.js",
      "/public/strings.js",
      "/public/style.css",
      "/public/manifest.json",
      "/public/assets/favicon.svg",
      "/api/auth/config",
      "/api/health",
    ]) {
      const res = await fetch(`${ready.url}${assetPath}`);
      assert.equal(res.status, 200, `Packaged sidecar must serve ${assetPath} with status 200`);
    }

    const exitPromise = new Promise((r) => child.on("exit", r));
    child.stdin.end();
    const exitCode = await exitPromise;
    assert.equal(exitCode, 0);
  } finally {
    fs.rmSync(emptyWorkDir, { recursive: true, force: true });
    fs.rmSync(fakeAppData, { recursive: true, force: true });
  }
});
