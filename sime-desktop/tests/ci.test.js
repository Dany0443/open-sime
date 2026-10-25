import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(__dirname, "..", "..");
const CI_WORKFLOW = path.join(REPO_ROOT, ".github", "workflows", "ci.yml");
const RELEASE_WORKFLOW = path.join(REPO_ROOT, ".github", "workflows", "release.yml");

test("CI Workflow: .github/workflows/ci.yml structure and security", () => {
  assert.ok(fs.existsSync(CI_WORKFLOW), "ci.yml must exist under .github/workflows/");
  const content = fs.readFileSync(CI_WORKFLOW, "utf8");

  // Must not contain tabs (invalid YAML)
  assert.ok(!content.includes("\t"), "ci.yml must use spaces, not tabs");

  // Read-only permissions for PRs and pushes
  assert.ok(content.includes("permissions:\n  contents: read"), "CI must have contents: read permission");

  // Triggers
  assert.ok(content.includes("pull_request:"), "CI must trigger on pull_request");
  assert.ok(content.includes("branches:\n      - main"), "CI must target main branch");

  // Must run tests and checks first
  assert.ok(content.includes("run: npm run check"), "CI must run npm run check");
  assert.ok(content.includes("run: npm test"), "CI must run npm test");

  // Build matrix covers all 4 targets
  assert.ok(content.includes("x86_64-pc-windows-msvc"), "CI matrix must include Windows x64");
  assert.ok(content.includes("x86_64-apple-darwin"), "CI matrix must include macOS Intel");
  assert.ok(content.includes("aarch64-apple-darwin"), "CI matrix must include macOS Apple Silicon");
  assert.ok(content.includes("x86_64-unknown-linux-gnu"), "CI matrix must include Linux x64");

  // Working directory
  assert.ok(content.includes("working-directory: sime-desktop"), "CI must use sime-desktop working directory");

  // Must NOT expose or reference signing secrets in CI
  assert.ok(!content.includes("secrets.WINDOWS_CERTIFICATE"), "CI must not access WINDOWS_CERTIFICATE");
  assert.ok(!content.includes("secrets.APPLE_CERTIFICATE"), "CI must not access APPLE_CERTIFICATE");
  assert.ok(!content.includes("secrets.LINUX_GPG_PRIVATE_KEY"), "CI must not access LINUX_GPG_PRIVATE_KEY");
});

test("Release Workflow: .github/workflows/release.yml validation and publishing", () => {
  assert.ok(fs.existsSync(RELEASE_WORKFLOW), "release.yml must exist under .github/workflows/");
  const content = fs.readFileSync(RELEASE_WORKFLOW, "utf8");

  // Must not contain tabs (invalid YAML)
  assert.ok(!content.includes("\t"), "release.yml must use spaces, not tabs");

  // Write permissions for release publishing
  assert.ok(content.includes("permissions:\n  contents: write"), "Release must have contents: write permission");

  // Tag trigger
  assert.ok(content.includes('tags:\n      - "v*"'), "Release must trigger on v* tags");

  // Validation job
  assert.ok(content.includes("validate-release:"), "Release must have validate-release job");
  assert.ok(content.includes("check-versions.mjs --tag"), "Release must validate git tag against project versions");
  assert.ok(content.includes("check-release-secrets.mjs"), "Release must validate required signing secrets");

  // Downstream jobs depend on validation
  assert.ok(content.includes("needs: validate-release"), "Build jobs must require validate-release");

  // Final publish job requires all platform builds
  assert.ok(
    content.includes("needs: [validate-release, build-windows, build-macos, build-linux]"),
    "Publish release job must require all matrix builds"
  );

  // Exact installer filenames
  const expectedArtifacts = [
    "Open-SIME-windows-x64-setup.exe",
    "Open-SIME-macos-intel.dmg",
    "Open-SIME-macos-apple-silicon.dmg",
    "Open-SIME-linux-x64.AppImage",
    "Open-SIME-linux-x64.deb",
  ];
  for (const artifact of expectedArtifacts) {
    assert.ok(content.includes(artifact), `release.yml must produce and stage ${artifact}`);
  }

  // Checksum manifest and detached signature
  assert.ok(content.includes("sha256sum Open-SIME-* > checksums.txt"), "Release must generate checksums.txt");
  assert.ok(content.includes("checksums.txt.sig"), "Release must create checksums.txt.sig detached signature");

  // Codesigning verification commands
  assert.ok(content.includes("Get-AuthenticodeSignature"), "Release must verify Windows Authenticode signature");
  assert.ok(content.includes("codesign --verify"), "Release must verify macOS codesign");
  assert.ok(content.includes("spctl --assess"), "Release must verify macOS Gatekeeper assessment");
  assert.ok(content.includes("xcrun stapler validate"), "Release must verify macOS notarization ticket");
  assert.ok(content.includes("--appimage-signature"), "Release must verify Linux AppImage signature");
});
