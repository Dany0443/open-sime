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
const ANDROID_RELEASE_WORKFLOW = path.join(REPO_ROOT, ".github", "workflows", "android-release.yml");

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
  assert.ok(!content.includes("--draft=false"), "Release must never publish automatically");
  assert.ok(content.includes("gh release view"), "Release must check existing release status");
  assert.ok(content.includes("gh release upload"), "Release must upload individual assets to release");
  assert.ok(content.includes("--clobber"), "Release must clobber only matching assets on retry");
  assert.ok(content.includes("--target \"$GITHUB_SHA\""), "Release must target the specific commit SHA");

  // Safety checks against overwriting published releases or tag mismatches
  assert.ok(content.includes("versionName"), "Release must verify Android app versionName matches authoritative version");
  assert.ok(content.includes("isDraft"), "Release must verify existing release is a draft before updating");
  assert.ok(content.includes("already published"), "Release must fail if release is already published");
  assert.ok(content.includes("TAG_COMMIT"), "Release must check existing tag commit");

  // Workflow summary with draft distinction
  assert.ok(content.includes("GITHUB_STEP_SUMMARY"), "Release must write to GITHUB_STEP_SUMMARY");
  assert.ok(content.includes("Draft Release Prepared (NOT published)"), "Summary must clearly state draft status");
  assert.ok(content.includes("Draft Release URL"), "Summary must include draft release URL");
});

test("Android Release Workflow: .github/workflows/android-release.yml validation and draft release", () => {
  assert.ok(fs.existsSync(ANDROID_RELEASE_WORKFLOW), "android-release.yml must exist under .github/workflows/");
  const content = fs.readFileSync(ANDROID_RELEASE_WORKFLOW, "utf8").replace(/\r\n/g, "\n");

  // Must not contain tabs (invalid YAML)
  assert.ok(!content.includes("\t"), "android-release.yml must use spaces, not tabs");

  // Workflow permissions: write permission for creating/updating draft release
  assert.ok(content.includes("permissions:\n  contents: write"), "Android release must have contents: write permission");

  // Triggered ONLY manually via workflow_dispatch
  assert.ok(content.includes("workflow_dispatch:"), "Android release must support workflow_dispatch");
  assert.ok(!content.includes("push:"), "Android release must not trigger on push");

  // Full Git history and tag fetching
  assert.ok(content.includes("fetch-depth: 0"), "Android release must fetch full history");
  assert.ok(content.includes("git fetch --tags --force"), "Android release must fetch remote tags");

  // Version validation and consistency
  assert.ok(content.includes("check-versions.mjs"), "Android release must validate version against check-versions.mjs");
  assert.ok(content.includes("versionName"), "Android release must verify Android app versionName matches authoritative version");

  // Signing secrets validation
  assert.ok(content.includes("Validate release secrets"), "Android release must validate release secrets");
  assert.ok(content.includes("ANDROID_KEYSTORE_BASE64"), "Android release must require ANDROID_KEYSTORE_BASE64");

  // Unit tests and lint before release packaging
  assert.ok(content.includes("./gradlew testDebugUnitTest"), "Android release must run JVM unit tests");
  assert.ok(content.includes("./gradlew lintDebug"), "Android release must run lintDebug");

  // Secure keystore handling and signed build
  assert.ok(content.includes("mktemp -d"), "Android release must use temporary directory for keystore");
  assert.ok(content.includes("umask 077"), "Android release must restrict keystore file permissions");
  assert.ok(content.includes("shred -u"), "Android release must shred keystore after build");
  assert.ok(content.includes("assembleRelease bundleRelease"), "Android release must build assembleRelease and bundleRelease");

  // Staged release artifacts and checksum manifest
  assert.ok(content.includes("Open-SIME-android.apk"), "Android release must stage Open-SIME-android.apk");
  assert.ok(content.includes("Open-SIME-android.aab"), "Android release must stage Open-SIME-android.aab");
  assert.ok(content.includes("checksums-android.txt"), "Android release must generate checksums-android.txt");

  // Actions artifact upload
  assert.ok(content.includes("actions/upload-artifact@v4"), "Android release must upload release artifact");
  assert.ok(content.includes("open-sime-android-release"), "Android release artifact must be named open-sime-android-release");

  // Draft release creation & safe updates
  assert.ok(content.includes("--draft=true"), "Android release must create a draft release");
  assert.ok(!content.includes("--draft=false"), "Android release must never publish automatically");
  assert.ok(content.includes("gh release view"), "Android release must check existing release status");
  assert.ok(content.includes("gh release upload"), "Android release must upload individual assets to release");
  assert.ok(content.includes("--clobber"), "Android release must clobber only matching assets on retry");

  // Safety checks against overwriting published releases or tag mismatches
  assert.ok(content.includes("isDraft"), "Android release must verify existing release is a draft before updating");
  assert.ok(content.includes("already published"), "Android release must fail if release is already published");
  assert.ok(content.includes("TAG_COMMIT"), "Android release must check existing tag commit");

  // Workflow summary with draft distinction
  assert.ok(content.includes("GITHUB_STEP_SUMMARY"), "Android release must write to GITHUB_STEP_SUMMARY");
  assert.ok(content.includes("Draft Release Prepared (NOT published)"), "Summary must clearly state draft status");
  assert.ok(content.includes("Draft Release URL"), "Summary must include draft release URL");
});

test("Cross-Workflow Release Coordination: Desktop and Android merge safely into one draft release", () => {
  const desktopYaml = fs.readFileSync(RELEASE_WORKFLOW, "utf8").replace(/\r\n/g, "\n");
  const androidYaml = fs.readFileSync(ANDROID_RELEASE_WORKFLOW, "utf8").replace(/\r\n/g, "\n");

  // Both workflows must enforce tag format tag=v$VER
  assert.ok(desktopYaml.includes('tag=v$VER'), "Desktop release must use v$VER tag format");
  assert.ok(androidYaml.includes('tag=v$VER'), "Android release must use v$VER tag format");

  // Both workflows must verify Android versionName matches authoritative version
  assert.ok(desktopYaml.includes('versionName = \\"$VER\\"'), "Desktop release must verify android versionName");
  assert.ok(androidYaml.includes('versionName = \\"$VER\\"'), "Android release must verify android versionName");

  // Both workflows must use --draft=true and NEVER --draft=false
  assert.ok(desktopYaml.includes("--draft=true") && !desktopYaml.includes("--draft=false"), "Desktop must only draft");
  assert.ok(androidYaml.includes("--draft=true") && !androidYaml.includes("--draft=false"), "Android must only draft");

  // Both workflows must guard against overwriting published releases
  assert.ok(desktopYaml.includes('Release ${TARGET_TAG} is already published'), "Desktop must guard published release");
  assert.ok(androidYaml.includes('Release ${TARGET_TAG} is already published'), "Android must guard published release");

  // Both workflows must guard against Git tag commit mismatch
  assert.ok(desktopYaml.includes('Git tag ${TARGET_TAG} already exists and points to commit'), "Desktop must guard tag commit mismatch");
  assert.ok(androidYaml.includes('Git tag ${TARGET_TAG} already exists and points to commit'), "Android must guard tag commit mismatch");

  // Both workflows must guard against draft targetCommitish mismatch
  assert.ok(desktopYaml.includes('targets commit ${EXISTING_TARGET}'), "Desktop must guard targetCommitish mismatch");
  assert.ok(androidYaml.includes('targets commit ${EXISTING_TARGET}'), "Android must guard targetCommitish mismatch");

  // Both workflows must specify --target "$GITHUB_SHA" when creating new releases
  assert.ok(desktopYaml.includes('--target "$GITHUB_SHA"'), "Desktop must create release with target commit");
  assert.ok(androidYaml.includes('--target "$GITHUB_SHA"'), "Android must create release with target commit");

  // Both workflows must upload with --clobber for safe idempotent asset uploads
  assert.ok(desktopYaml.includes('gh release upload "$TARGET_TAG" "${RELEASE_ASSETS[@]}" --clobber'), "Desktop upload must clobber");
  assert.ok(androidYaml.includes('gh release upload "$TARGET_TAG" "${RELEASE_ASSETS[@]}" --clobber'), "Android upload must clobber");

  // Verify asset filename disjointness:
  // Android assets: Open-SIME-android.apk, Open-SIME-android.aab, checksums-android.txt
  // Desktop assets: Open-SIME-installer-*-windows-x64.exe, Open-SIME-*-macos-apple-silicon.dmg, Open-SIME-*-linux-x64.AppImage, checksums.txt, checksums.txt.sig
  assert.ok(androidYaml.includes("checksums-android.txt"), "Android must use checksums-android.txt to prevent collision with checksums.txt");
  assert.ok(desktopYaml.includes("checksums.txt"), "Desktop must use checksums.txt");
  assert.ok(!desktopYaml.includes("checksums-android.txt"), "Desktop must not touch checksums-android.txt");
  assert.ok(!androidYaml.includes("Open-SIME-installer-"), "Android must not produce installer exe");
  assert.ok(!androidYaml.includes(".AppImage"), "Android must not produce AppImage");
  assert.ok(!androidYaml.includes(".dmg"), "Android must not produce dmg");

  // On draft update, Android release must NOT pass --notes or --notes-file, preserving desktop's release-notes.md
  const androidEditCall = androidYaml.match(/gh release edit[^\n]+/g);
  assert.ok(androidEditCall && androidEditCall.length > 0, "Android must have gh release edit call");
  for (const call of androidEditCall) {
    assert.ok(!call.includes("--notes"), "Android release edit must not overwrite existing release notes");
  }
});

test("Workflow YAML Validation: .github/workflows/*.yml must be valid YAML syntax", () => {
  try {
    execFileSync(
      "python3",
      [
        "-c",
        "import glob, yaml; [yaml.safe_load(open(f)) for f in glob.glob('.github/workflows/*.yml')]",
      ],
      { cwd: REPO_ROOT, encoding: "utf8" }
    );
  } catch (err) {
    assert.fail(`GitHub Actions YAML syntax error: ${err.message}`);
  }
});

