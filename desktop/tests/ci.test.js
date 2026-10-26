import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DESKTOP_ROOT = path.resolve(__dirname, "..");
const REPO_ROOT = path.resolve(__dirname, "..", "..");
const CI_WORKFLOW = path.join(REPO_ROOT, ".github", "workflows", "ci.yml");
const RELEASE_WORKFLOW = path.join(REPO_ROOT, ".github", "workflows", "release.yml");

test("CI Workflow: .github/workflows/ci.yml structure and security", () => {
  assert.ok(fs.existsSync(CI_WORKFLOW), "ci.yml must exist under .github/workflows/");
  const content = fs.readFileSync(CI_WORKFLOW, "utf8").replace(/\r\n/g, "\n");

  // Must not contain tabs (invalid YAML)
  assert.ok(!content.includes("\t"), "ci.yml must use spaces, not tabs");

  // Read-only permissions for PRs and pushes
  assert.ok(content.includes("permissions:\n  contents: read"), "CI must have contents: read permission");

  // Triggers
  assert.ok(content.includes("pull_request:"), "CI must trigger on pull_request");
  assert.ok(content.includes("branches:\n      - main"), "CI must target main branch");
  assert.ok(content.includes("workflow_dispatch:"), "CI must support workflow_dispatch");

  // Concurrency cancellation
  assert.ok(content.includes("cancel-in-progress: true"), "CI must cancel superseded runs on same branch or pull request");

  // Must run tests and checks
  assert.ok(content.includes("run: npm run check"), "CI must run npm run check");
  assert.ok(content.includes("run: npm test"), "CI must run npm test");
  assert.ok(content.includes("cargo check"), "CI must run cargo check for Rust syntax");
  assert.ok(content.includes("swatinem/rust-cache@v2"), "CI must use swatinem/rust-cache@v2");
  assert.ok(content.includes("desktop/src-tauri/"), "CI must filter Rust checks based on relevant file changes");

  // Lightweight CI: must not compile or package desktop installers during normal CI
  assert.ok(!content.includes("tauri build"), "CI must not compile or package desktop installers during normal runs");
  assert.ok(!content.includes(".deb"), "CI must not build Debian packages");
  assert.ok(!content.includes("android"), "CI must not build Android during normal runs");

  // Working directory
  assert.ok(content.includes("working-directory: desktop"), "CI must use desktop working directory");
  assert.ok(content.includes("working-directory: web"), "CI must use web working directory");
  assert.ok(content.includes("Install web dependencies"), "Desktop CI must install web dependencies before running tests");

  // Required checks reporting completed status
  assert.ok(content.includes("ci-status:"), "CI must have a completion status gate job");

  // Must NOT expose or reference signing secrets in CI
  assert.ok(!content.includes("secrets.WINDOWS_CERTIFICATE"), "CI must not access WINDOWS_CERTIFICATE");
  assert.ok(!content.includes("secrets.APPLE_CERTIFICATE"), "CI must not access APPLE_CERTIFICATE");
  assert.ok(!content.includes("secrets.LINUX_GPG_PRIVATE_KEY"), "CI must not access LINUX_GPG_PRIVATE_KEY");
});

test("Release Workflow: .github/workflows/release.yml validation and publishing", () => {
  assert.ok(fs.existsSync(RELEASE_WORKFLOW), "release.yml must exist under .github/workflows/");
  const content = fs.readFileSync(RELEASE_WORKFLOW, "utf8").replace(/\r\n/g, "\n");

  // Must not contain tabs (invalid YAML)
  assert.ok(!content.includes("\t"), "release.yml must use spaces, not tabs");

  // Workflow permissions: read-only at workflow level, write only for publish job
  assert.ok(content.includes("permissions:\n  contents: read"), "Release must have contents: read at workflow level");
  assert.ok(content.includes("permissions:\n      contents: write"), "Publish job must have contents: write permission");

  // Triggered ONLY manually via workflow_dispatch
  assert.ok(content.includes("workflow_dispatch:"), "Release workflow must be triggered by workflow_dispatch");
  assert.ok(!content.includes("push:"), "Release workflow must not be triggered on push");

  // Git history depth and tag fetching
  assert.ok(content.includes("fetch-depth: 0"), "Release workflow checkout must fetch full Git history depth");
  assert.ok(content.includes("git fetch --tags --force"), "Release workflow must fetch all git tags");

  // Validation job & signing outputs
  assert.ok(content.includes("validate-release:"), "Release must have validate-release job");
  assert.ok(content.includes("check-versions.mjs"), "Release must validate git tag against project versions");
  assert.ok(content.includes("check-release-secrets.mjs"), "Release must validate required signing secrets");
  assert.ok(content.includes("sign_windows:"), "Release outputs must include sign_windows");
  assert.ok(content.includes("sign_macos:"), "Release outputs must include sign_macos");
  assert.ok(content.includes("sign_linux:"), "Release outputs must include sign_linux");
  assert.ok(content.includes("sign_checksums:"), "Release outputs must include sign_checksums");

  // Downstream jobs depend on validation
  assert.ok(content.includes("needs: validate-release"), "Build jobs must require validate-release");

  // Final publish job requires all platform builds
  assert.ok(
    content.includes("needs: [validate-release, build-windows, build-macos, build-linux]"),
    "Publish release job must require all matrix builds"
  );

  // Target matrix in release builds
  assert.ok(content.includes("x86_64-pc-windows-msvc"), "Release must build Windows x64");
  assert.ok(content.includes("aarch64-apple-darwin"), "Release must build macOS");
  assert.ok(content.includes("x86_64-unknown-linux-gnu"), "Release must build Linux x64");

  // Standardized dynamic artifact staging
  assert.ok(content.includes("scripts/stage-release-artifact.mjs"), "Release must stage artifacts via stage-release-artifact.mjs");
  assert.ok(!content.includes("Open-SIME-windows-x64-setup.exe"), "Release must not hardcode legacy Windows setup name");
  assert.ok(!content.includes("Open-SIME-macos-intel.dmg"), "Release must not hardcode legacy macOS intel DMG name");
  assert.ok(!content.includes("Open-SIME-macos-apple-silicon.dmg"), "Release must not hardcode legacy macOS arm64 DMG name");
  assert.ok(!content.includes("Open-SIME-linux-x64.AppImage"), "Release must not hardcode legacy Linux AppImage name");
  assert.ok(!content.includes(".deb"), "Release workflow must not reference or stage deb package");

  // Checksum manifest and detached signature
  assert.ok(content.includes("sha256sum Open-SIME-* > checksums.txt"), "Release must generate checksums.txt");
  assert.ok(content.includes("checksums.txt.sig"), "Release must create checksums.txt.sig detached signature");

  // Codesigning verification commands
  assert.ok(content.includes("Get-AuthenticodeSignature"), "Release must verify Windows Authenticode signature");
  assert.ok(content.includes("codesign --verify"), "Release must verify macOS codesign");
  assert.ok(content.includes("spctl --assess"), "Release must verify macOS Gatekeeper assessment");
  assert.ok(content.includes("xcrun stapler validate"), "Release must verify macOS notarization ticket");
  assert.ok(content.includes("--appimage-signature"), "Release must verify Linux AppImage signature");

  // Linux sidecar integrity verification (no patchelf or strip shims)
  assert.ok(!content.includes("patchelf-shim.sh"), "Release must not use patchelf-shim.sh");
  assert.ok(!content.includes("strip-shim.sh"), "Release must not use strip-shim.sh");
  assert.ok(content.includes("node scripts/verify-appimage.mjs"), "Release must verify sidecar integrity using verify-appimage.mjs");

  // Release notes generation
  assert.ok(content.includes("generate-release-notes.mjs"), "Release must generate release notes with generate-release-notes.mjs");

  // Draft release creation
  assert.ok(content.includes("--draft=true"), "Release must create a draft release");
});

test("Workflow YAML Validation: .github/workflows/*.yml must be valid YAML syntax", () => {
  try {
    execFileSync(
      "python3",
      [
        "-c",
        "import yaml; yaml.safe_load(open('.github/workflows/release.yml')); yaml.safe_load(open('.github/workflows/ci.yml'))",
      ],
      { cwd: REPO_ROOT, encoding: "utf8" }
    );
  } catch (err) {
    assert.fail(`GitHub Actions YAML syntax error: ${err.message}`);
  }
});

