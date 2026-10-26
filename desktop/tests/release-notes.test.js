import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { generateReleaseNotes } from "../scripts/generate-release-notes.mjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DESKTOP_ROOT = path.resolve(__dirname, "..");
const SCRIPT_PATH = path.join(DESKTOP_ROOT, "scripts", "generate-release-notes.mjs");

test("Release Notes: first-release notes fallback when no previous release exists", () => {
  const notes = generateReleaseNotes({
    version: "1.0.0",
    prevTag: null,
    commits: ["Initial commit", "Add core UI"],
    signStatus: { windows: false, macos: false, linux: false, checksums: false },
  });

  assert.ok(notes.startsWith("## First release of Open SIME"), "Must start with 'First release of Open SIME' heading");
  assert.ok(
    notes.includes("Open SIME este un client neoficial, gratuit și privat pentru agenda electronică școlară agenda.sime.md"),
    "Must include Romanian introductory description"
  );
  assert.ok(notes.includes("### Changes"), "Must contain Changes section");
  assert.ok(notes.includes("* Initial commit"), "Must list provided commits");
  assert.ok(notes.includes("* Add core UI"), "Must list provided commits");
  assert.ok(notes.includes("### Signing Status"), "Must contain Signing Status section");
  assert.ok(notes.includes("### Platform Notes for Unsigned Builds"), "Must contain Unsigned notes");
  assert.ok(notes.includes("### Checksum Verification"), "Must contain Checksum verification instructions");
  assert.ok(!notes.includes("Changes since"), "First release must not contain 'Changes since'");
});

test("Release Notes: subsequent-release notes logic with previous release tag", () => {
  const notes = generateReleaseNotes({
    version: "1.1.0",
    prevTag: "v1.0.0",
    commits: ["fix(auth): improve session recovery", "feat(ui): refine dark theme"],
    signStatus: { windows: true, macos: true, linux: false, checksums: true },
  });

  assert.ok(notes.startsWith("## Open SIME v1.1.0"), "Must start with 'Open SIME v1.1.0' heading");
  assert.ok(notes.includes("Changes since v1.0.0:"), "Must indicate changes since previous tag");
  assert.ok(notes.includes("* fix(auth): improve session recovery"), "Must list commits");
  assert.ok(!notes.includes("## First release of Open SIME"), "Subsequent release must not use first release heading");
  assert.ok(notes.includes("Signed with Authenticode certificate"), "Windows must reflect signed status");
  assert.ok(notes.includes("Signed with Developer ID"), "macOS must reflect signed status");
  assert.ok(notes.includes("Unsigned build"), "Linux must reflect unsigned status");
  assert.ok(notes.includes("gpg --verify checksums.txt.sig checksums.txt"), "Signed checksums must include GPG verify block");
});

test("Release Notes CLI: generates and writes release notes to file", () => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "sime-notes-"));
  const outFile = path.join(tmpDir, "test-notes.md");

  try {
    // 1. First release invocation
    execFileSync(
      "node",
      [SCRIPT_PATH, "--version", "1.0.0", "--output", outFile],
      { cwd: DESKTOP_ROOT, encoding: "utf8" }
    );
    assert.ok(fs.existsSync(outFile), "Output file must be created");
    const content1 = fs.readFileSync(outFile, "utf8");
    assert.ok(content1.includes("## First release of Open SIME"));
    assert.ok(content1.includes("### Checksum Verification"));

    // 2. Subsequent release invocation
    execFileSync(
      "node",
      [SCRIPT_PATH, "--version", "1.2.0", "--prev-tag", "v1.1.0", "--output", outFile, "--sign-windows", "true"],
      { cwd: DESKTOP_ROOT, encoding: "utf8" }
    );
    const content2 = fs.readFileSync(outFile, "utf8");
    assert.ok(content2.includes("## Open SIME v1.2.0"));
    assert.ok(content2.includes("Changes since v1.1.0:"));
    assert.ok(content2.includes("Signed with Authenticode certificate"));
  } finally {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }
});
