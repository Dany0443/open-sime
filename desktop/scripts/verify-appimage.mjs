#!/usr/bin/env node
// Verifies the integrity and functionality of the Linux AppImage package and bundled sidecar.
//
// Usage:
//   node scripts/verify-appimage.mjs [path-to-appimage]

import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DESKTOP_ROOT = path.resolve(__dirname, "..");
const REPO_ROOT = path.resolve(DESKTOP_ROOT, "..");

function findInBundle(bundleDir) {
  if (!fs.existsSync(bundleDir)) return [];
  return fs
    .readdirSync(bundleDir)
    .filter((f) => f.endsWith(".AppImage"))
    .map((f) => path.join(bundleDir, f));
}

export function findAppImage(customPath) {
  if (customPath) {
    const resolved = path.resolve(customPath);
    if (!fs.existsSync(resolved)) {
      throw new Error(`Specified AppImage does not exist: ${resolved}`);
    }
    return resolved;
  }

  const searchLocations = [
    path.join(DESKTOP_ROOT, "Open-SIME-linux-x64.AppImage"),
    path.join(REPO_ROOT, "Open-SIME-linux-x64.AppImage"),
    ...findInBundle(
      path.join(
        DESKTOP_ROOT,
        "src-tauri",
        "target",
        "x86_64-unknown-linux-gnu",
        "release",
        "bundle",
        "appimage"
      )
    ),
    ...findInBundle(path.join(DESKTOP_ROOT, "src-tauri", "target", "release", "bundle", "appimage")),
  ];

  for (const loc of searchLocations) {
    if (fs.existsSync(loc)) {
      return loc;
    }
  }

  throw new Error("Could not find any AppImage artifact to verify.");
}

export function computeSha256(filePath) {
  const data = fs.readFileSync(filePath);
  return crypto.createHash("sha256").update(data).digest("hex");
}

export function verifyAppImage(appImagePath = findAppImage()) {
  console.log(`Verifying Linux AppImage: ${appImagePath}`);
  fs.chmodSync(appImagePath, 0o755);

  const referenceBinary = path.join(
    DESKTOP_ROOT,
    "src-tauri",
    "binaries",
    "open-sime-server-real-x86_64-unknown-linux-gnu"
  );
  if (!fs.existsSync(referenceBinary)) {
    throw new Error(`Reference sidecar binary not found at ${referenceBinary}`);
  }
  const refHash = computeSha256(referenceBinary);

  const tempExtractDir = fs.mkdtempSync(path.join(os.tmpdir(), "sime-verify-appimage-"));

  try {
    // Extract AppImage without mounting/FUSE
    execFileSync(appImagePath, ["--appimage-extract"], {
      cwd: tempExtractDir,
      stdio: ["ignore", "pipe", "pipe"],
      env: { ...process.env },
    });

    const squashfsRoot = path.join(tempExtractDir, "squashfs-root");
    assert.ok(fs.existsSync(squashfsRoot), "Extraction must create squashfs-root");

    // 1. Verify byte-for-byte integrity of the sidecar in usr/share
    const packagedRealBinary = path.join(
      squashfsRoot,
      "usr",
      "share",
      "open-sime",
      "open-sime-server"
    );
    assert.ok(
      fs.existsSync(packagedRealBinary),
      `Expected packaged sidecar at ${packagedRealBinary}`
    );

    const packagedHash = computeSha256(packagedRealBinary);
    assert.equal(
      packagedHash,
      refHash,
      `SHA-256 mismatch! Packaged: ${packagedHash}, Reference: ${refHash}`
    );
    console.log(`✓ Byte-for-byte sidecar integrity verified (SHA-256: ${packagedHash})`);

    // 2. Test launcher script in usr/bin
    const launcherBinary = path.join(squashfsRoot, "usr", "bin", "open-sime-server");
    assert.ok(fs.existsSync(launcherBinary), `Expected launcher at ${launcherBinary}`);

    // Verify launcher execution with --port invalid (must exit non-zero and output structured error)
    let launcherOutput = "";
    try {
      execFileSync(launcherBinary, ["--port", "invalid"], {
        cwd: squashfsRoot,
        env: {
          ...process.env,
          APPDIR: squashfsRoot,
        },
        encoding: "utf8",
        stdio: ["ignore", "pipe", "pipe"],
      });
      assert.fail("Launcher should have exited with non-zero exit code on invalid port");
    } catch (err) {
      launcherOutput = (err.stdout || "") + (err.stderr || "");
      assert.notEqual(err.status, 0, "Launcher must exit with non-zero code on invalid port");
      assert.ok(
        launcherOutput.includes("INVALID_PORT"),
        `Expected INVALID_PORT in launcher output, got: ${launcherOutput}`
      );
      assert.ok(
        !launcherOutput.includes("readPrelude") && !launcherOutput.includes("SyntaxError"),
        "Launcher must not fail with prelude syntax error"
      );
    }
    console.log("✓ Launcher script successfully executed sidecar and validated arguments");

    // 3. Test packaged real binary directly with --port invalid
    try {
      execFileSync(packagedRealBinary, ["--port", "invalid"], {
        cwd: squashfsRoot,
        env: {
          ...process.env,
        },
        encoding: "utf8",
        stdio: ["ignore", "pipe", "pipe"],
      });
      assert.fail("Packaged real binary should have exited with non-zero exit code on invalid port");
    } catch (err) {
      const out = (err.stdout || "") + (err.stderr || "");
      assert.notEqual(err.status, 0, "Packaged binary must exit with non-zero code on invalid port");
      assert.ok(
        out.includes("INVALID_PORT"),
        `Expected INVALID_PORT in binary output, got: ${out}`
      );
      assert.ok(
        !out.includes("readPrelude") && !out.includes("SyntaxError"),
        "Packaged binary must not fail with prelude syntax error"
      );
    }
    console.log("✓ Packaged binary successfully validated arguments without prelude errors");

    console.log("✓ Linux AppImage sidecar verification passed successfully.");
    return {
      success: true,
      sha256: packagedHash,
      appImagePath,
    };
  } finally {
    fs.rmSync(tempExtractDir, { recursive: true, force: true });
  }
}

if (path.resolve(process.argv[1] || "") === path.resolve(fileURLToPath(import.meta.url))) {
  try {
    const customTarget = process.argv[2];
    verifyAppImage(findAppImage(customTarget));
  } catch (err) {
    console.error(`Verification failed: ${err.message}`);
    process.exit(1);
  }
}
