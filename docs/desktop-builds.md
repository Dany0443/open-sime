# Open SIME Desktop Builds & Distribution Guide

This document explains how **Open SIME** packages its Node.js backend as a self-contained **Tauri 2** sidecar for production desktop builds, maintains version consistency, and automates cross-platform building, code signing, signature verification, and releases via GitHub Actions.

---

## 1. Architecture Overview

Open SIME uses an Express + Cheerio backend (`web/server.js`) to authenticate with `agenda.sime.md`, enforce student data isolation, and serve the web interface (`web/index.html` and `web/public/`).

| Mode | Command | How the Backend Runs | Default Origin |
| --- | --- | --- | --- |
| **Standalone Web** | `npm start` | System Node.js (`node server.js`) | `http://127.0.0.1:3000` |
| **Tauri Development** | `npm run tauri:dev` | Tauri `beforeDevCommand` runs `npm start` | `http://127.0.0.1:3000` |
| **Tauri Production** | `npm run tauri:build` | Bundled self-contained sidecar (`binaries/open-sime-server`) | `http://127.0.0.1:17321` |

### Why `esbuild` + `@yao-pkg/pkg`?

- `server.js` is written in modern ESM (`"type": "module"`) and depends on `express` and `cheerio` (which uses conditional `exports` maps).
- Raw `pkg` cannot natively trace ESM entrypoints with `import.meta.url` and conditional package exports without pre-bundling.
- `scripts/build-sidecar.mjs` runs a two-stage pipeline:
  1. **`esbuild`** bundles `server.js`, `express`, `cheerio`, and an embedded base64 snapshot of `index.html` + `public/**/*` (`globalThis.__SIME_EMBEDDED_ASSETS__`) into a single self-contained CommonJS file.
  2. **`@yao-pkg/pkg`** packages that bundle with a platform-native Node 22 runtime into a standalone executable (`src-tauri/binaries/open-sime-server-<target-triple>[.exe]`).
- **Trade-offs**:
  - **Pros**: Single self-contained executable per target (~59 MB on Windows x64); requires zero global Node.js installation on end-user machines; no raw `node_modules/` directory shipped in app resources; static assets (`index.html`, `public/*`) are embedded directly inside the binary so source-relative path assumptions cannot break in installed builds.
  - **Cons**: Larger binary footprint (~59 MB) compared to sharing a system Node install, and `@yao-pkg/pkg` downloads the base Node runtime binary on the first build if not cached in `PKG_CACHE_PATH`.

---

## 2. Target-Triple Naming Convention

Tauri 2 resolves `bundle.externalBin` (`["binaries/open-sime-server"]`) by appending the Rust target triple to the binary name inside `desktop/src-tauri/binaries/`:

| Platform / Architecture | Rust Target Triple | Sidecar Binary Path (`src-tauri/binaries/`) |
| --- | --- | --- |
| Windows x64 | `x86_64-pc-windows-msvc` | `open-sime-server-x86_64-pc-windows-msvc.exe` |
| macOS Intel (x64) | `x86_64-apple-darwin` | `open-sime-server-x86_64-apple-darwin` |
| macOS Apple Silicon (ARM64) | `aarch64-apple-darwin` | `open-sime-server-aarch64-apple-darwin` |
| Linux x64 | `x86_64-unknown-linux-gnu` | `open-sime-server-x86_64-unknown-linux-gnu` |

To build the sidecar for the current host or a specific cross-compilation target:

```bash
cd desktop

# Auto-detects target from TAURI_ENV_TARGET_TRIPLE, CARGO_BUILD_TARGET, rustc -vV, or host OS/arch
npm run sidecar:build

# Explicit target triple override (for cross-compiling)
node scripts/build-sidecar.mjs --target x86_64-pc-windows-msvc
node scripts/build-sidecar.mjs --target aarch64-apple-darwin
```

Compiled binaries under `src-tauri/binaries/` are gitignored and must not be committed to Git.

---

## 3. Startup, Readiness Handshake, and Shutdown Lifecycle

In production builds (`#[cfg(not(debug_assertions))]` in `src-tauri/src/lib.rs`):

1. **Hidden Window Initial State**: The main window starts with `"visible": false` (`src-tauri/tauri.conf.json`) and `frontendDist: "../dist-tauri"`.
2. **Single-Instance Guard & Nonce**: Rust stores the child process in `Mutex<SidecarState>` to prevent duplicate sidecar processes and generates a per-launch `SIME_INSTANCE_ID` nonce.
3. **Loopback-Only Binding & Dedicated Port**:
   - The sidecar is spawned with `--production --watch-stdin --host 127.0.0.1 --port 17321`.
   - In production mode, `server.js` refuses any non-loopback host (`0.0.0.0` is rejected with `INVALID_HOST`).
   - Port `17321` is used consistently across restarts so `localStorage` (theme, language, text scale, simple mode) persists under the stable `http://127.0.0.1:17321` origin.
4. **Port Conflict Detection & Readiness Verification**:
   - The sidecar binds with `exclusive: true`. If port `17321` is already occupied (`EADDRINUSE`), the sidecar emits `SIME_SIDECAR_ERROR {"code":"EADDRINUSE",...}` on `stderr` and exits with code `1`.
   - Once listening, the sidecar prints:
     ```text
     SIME_SIDECAR_READY {"url":"http://127.0.0.1:17321","host":"127.0.0.1","port":17321,"pid":1234,"instanceId":"..."}
     ```
   - Rust verifies that `host == "127.0.0.1"`, `port == 17321`, and `instanceId` matches the freshly spawned child before navigating the window to `http://127.0.0.1:17321` and calling `window.show()`. Tauri never connects to an unknown process already running on the port.
5. **Startup Error Display**:
   - If the sidecar times out (15s), crashes, reports `EADDRINUSE`, or outputs malformed readiness data, Rust navigates the main window to the bundled `dist-tauri/index.html?error=1&code=...&detail=...` error screen and shows the window.
6. **Clean Shutdown**:
   - When the main window closes or the app exits (`WindowEvent::Destroyed`, `RunEvent::Exit`, `RunEvent::ExitRequested`), Rust kills the `CommandChild` handle.
   - In addition, `--watch-stdin` in `server.js` monitors `process.stdin` for `end`/`close` so if the parent Tauri process terminates unexpectedly, the Node sidecar shuts down immediately without leaving an orphaned background process.

---

## 4. Runtime Data Locations & Security

In production mode (`--production`, `SIME_DESKTOP_PRODUCTION=1`, or packaged binary), persistent backend files (`remember-tokens.json` and `remember-secret.key`) are stored in the OS application-data directory rather than the installation directory:

- **Windows**: `%APPDATA%\md.sime.opensime\` (e.g. `C:\Users\<User>\AppData\Roaming\md.sime.opensime\`)
- **macOS**: `~/Library/Application Support/md.sime.opensime/`
- **Linux**: `${XDG_DATA_HOME:-~/.local/share}/md.sime.opensime/`

When launched by Tauri, Rust also passes `app.path().app_data_dir()` via `SIME_DATA_DIR`.

In local web development (`npm start`), data defaults to `web/data/` unless `SIME_DATA_DIR`, `SIME_REMEMBER_STORE`, or `SIME_REMEMBER_KEY_FILE` is set.

### Security Protections Preserved

- Outbound requests are strictly restricted to `https://agenda.sime.md` (`requireSimeUrl`).
- Content Security Policy (`default-src 'none'; script-src 'self'; style-src 'self'; ...`), `isCrossOrigin` checks on `/api/auth/resume` and `/api/auth/forget-device`, and `scrubLog` log redaction remain active.
- `public/sw.js` (`open-sime-v2`) uses network-first with cache fallback for static shell assets so desktop updates always load the newest bundled UI files immediately.

---

## 5. Version Synchronization

Version consistency across all components is strictly enforced by `desktop/scripts/check-versions.mjs`.

Four files must keep identical semver versions:
1. `web/package.json` (`"version": "X.Y.Z"`)
2. `desktop/package.json` (`"version": "X.Y.Z"`)
3. `desktop/src-tauri/tauri.conf.json` (`"version": "X.Y.Z"`)
4. `desktop/src-tauri/Cargo.toml` (`version = "X.Y.Z"`)

To check version consistency locally:
```bash
cd desktop
npm run check:versions
```

To validate that the project version matches a target git release tag:
```bash
node scripts/check-versions.mjs --tag v1.0.0
# Or with explicit expected version:
node scripts/check-versions.mjs --expected 1.0.0
```

`npm run check` runs this validation automatically on every syntax check.

---

## 6. GitHub Actions Workflows

Two independent, purpose-built workflows live under `.github/workflows/`:

```
.github/
└── workflows/
    ├── ci.yml       # Preview CI (pull requests & main branch pushes)
    └── release.yml  # Signed Production Releases (triggered on v* tags)
```

### 6.1. Continuous Integration (`ci.yml`)

- **Triggers**:
  - Pull requests targeting `main`
  - Direct pushes to `main`
  - Manual triggers (`workflow_dispatch`)
- **Permissions**: `contents: read` (strictly read-only; no release creation permissions).
- **Secrets Policy**: Does **not** require, expose, or access any signing credentials. Safe for untrusted pull requests and forks.
- **Jobs**:
  1. `checks`: Runs on `ubuntu-22.04`. Executes `npm run check` (file syntax and version validation) and `npm test` (full 112+ test suite).
  2. `build-preview`: Matrix job building all 4 platform targets (`windows-latest`, `macos-latest` Intel, `macos-latest` Apple Silicon, `ubuntu-22.04`). Packages unsigned preview installers and uploads them as GitHub Actions workflow artifacts (retained for 7 days).

### 6.2. Production Release Workflow (`release.yml`)

- **Triggers**:
  - Pushed version tags matching `v*` (e.g. `v1.0.0`, `v1.2.3`)
  - Manual dispatch (`workflow_dispatch`)
- **Permissions**: `contents: write` (required to publish the GitHub Release).
- **Execution Flow**:
  - **Fail-Fast Validation**: The `validate-release` job runs before any build runner starts. It validates tag-version equality, verifies that all unit tests pass, and checks that all required signing secrets exist in the repository. If any secret is missing, it aborts immediately with a clear error report.
  - **No Race Conditions**: All platform matrix jobs upload their signed installers as GitHub Actions artifacts. The final `publish-release` job executes only after all 4 builds succeed, downloads all 5 files, computes SHA-256 checksums, signs the manifest with GPG, and publishes everything to a single GitHub Release in one atomic operation.
  - **Zero Unsigned Production Artifacts**: If signing, notarization, or signature verification fails on any platform, the pipeline terminates and no release is published.

---

## 7. Supported Platforms and Release Artifacts

Every official release produces exactly 3 platform installers plus the checksum manifest and its detached signature:

| Platform / Target | Rust Triple | Distribution Format | Final Release Filename |
| --- | --- | --- | --- |
| **Windows x64** | `x86_64-pc-windows-msvc` | NSIS Installer (`.exe`) | `Open-SIME-installer-v<version>.exe` |
| **macOS (Apple Silicon)** | `aarch64-apple-darwin` | Drag-to-Applications (`.dmg`) | `Open-SIME-v<version>.dmg` |
| **Linux x64** | `x86_64-unknown-linux-gnu` | Portable AppImage (`.AppImage`) | `Open-SIME-v<version>.AppImage` |
| **Checksum Manifest** | N/A | SHA-256 Text File | `checksums.txt` |
| **Manifest Signature** | N/A | Detached ASCII-Armored GPG Signature | `checksums.txt.sig` |

---

## 8. Code Signing and GitHub Secrets Configuration

Official production releases require real signing credentials configured in **Settings > Secrets and variables > Actions > Repository secrets**.

### 8.1. Windows (Authenticode)

- **`WINDOWS_CERTIFICATE`**: Base64-encoded Authenticode PFX certificate file.
  To encode your certificate file:
  ```powershell
  [Convert]::ToBase64String([IO.File]::ReadAllBytes("MyCertificate.pfx")) | Out-File -FilePath "cert-base64.txt" -Encoding ascii
  ```
- **`WINDOWS_CERTIFICATE_PASSWORD`**: Password protecting the PFX certificate.
- **Signing Process**:
  - The workflow imports the PFX certificate into the runner's `Cert:\CurrentUser\My` store.
  - The sidecar executable `src-tauri/binaries/open-sime-server-x86_64-pc-windows-msvc.exe` is signed with `Set-AuthenticodeSignature` and timestamped via DigiCert (`http://timestamp.digicert.com`).
  - Tauri packages the NSIS installer and signs both the inner binary and setup executable with the certificate thumbprint.
  - The signature is verified with `Get-AuthenticodeSignature`. The certificate is removed from the store upon completion.

### 8.2. macOS (Developer ID & Notarization)

- **`APPLE_CERTIFICATE`**: Base64-encoded Developer ID Application `.p12` certificate.
  To export and encode:
  ```bash
  # In Keychain Access, export "Developer ID Application: Your Name (TEAMID)" as certificate.p12
  openssl base64 -A -in certificate.p12 -out certificate-base64.txt
  ```
- **`APPLE_CERTIFICATE_PASSWORD`**: Password defined during `.p12` export.
- **`APPLE_SIGNING_IDENTITY`**: Full identity string, e.g.:
  `Developer ID Application: Open SIME Contributors (ABC1234XYZ)`
- **`APPLE_TEAM_ID`**: 10-character Apple Developer Team ID (e.g. `ABC1234XYZ`).
- **Notarization Secrets (App Store Connect API Key - Recommended)**:
  - **`APPLE_API_KEY`**: 10-character Key ID from App Store Connect (e.g. `9X8Y7Z6W5V`).
  - **`APPLE_API_ISSUER`**: Issuer UUID from App Store Connect (e.g. `12345678-1234-1234-1234-1234567890ab`).
  - **`APPLE_API_KEY_BASE64`**: Base64-encoded contents of the `.p8` API key file (`AuthKey_XXXXXXXXXX.p8`):
    ```bash
    openssl base64 -A -in AuthKey_9X8Y7Z6W5V.p8 -out p8-base64.txt
    ```
- **Notarization Secrets (Apple ID Fallback)**:
  - **`APPLE_ID`**: Apple ID email.
  - **`APPLE_PASSWORD`**: App-specific password generated on appleid.apple.com.
- **Signing Process**:
  - The workflow creates an ephemeral keychain, imports the `.p12` certificate, and registers the `.p8` key under `~/.appstoreconnect/private_keys/`.
  - The sidecar binary is signed with hardened runtime (`--options runtime --timestamp`).
  - Tauri builds and signs the `.app` bundle and DMG, submits for notarization via `notarytool`, and staples the ticket with `stapler`.
  - Signatures are verified with `codesign --verify`, `spctl --assess`, and `xcrun stapler validate`. Keychains and keys are securely deleted in the cleanup phase.

### 8.3. Linux (GPG AppImage Signing)

- **`LINUX_GPG_PRIVATE_KEY`** (or `RELEASE_GPG_PRIVATE_KEY`): ASCII-armored GPG private key.
  To export:
  ```bash
  gpg --armor --export-secret-keys <KEY-ID> > private-key.asc
  ```
- **`LINUX_GPG_PASSPHRASE`** (or `RELEASE_GPG_PASSPHRASE`): Passphrase for the GPG private key.
- **Signing Process**:
  - The workflow imports the GPG private key into the runner's keyring.
  - The Node.js sidecar binary is bundled under `/usr/share/open-sime/open-sime-server` via Tauri's AppImage file mappings, which guarantees `linuxdeploy` will not run `patchelf` or `strip` on it, and is launched directly or via the `/usr/bin/open-sime-server` launcher script.
  - Tauri's AppImage bundler runs with `SIGN=1`, `APPIMAGETOOL_FORCE_SIGN=1`, and `APPIMAGETOOL_SIGN_PASSPHRASE`. If signing encounters any issue, `APPIMAGETOOL_FORCE_SIGN=1` immediately fails the build.
  - The sidecar binary integrity and functionality inside the resulting AppImage is verified via `desktop/scripts/verify-appimage.mjs` (checking SHA-256 hash match against the reference binary and validating CLI argument handling).
  - The signature is verified using `Open-SIME-v<version>.AppImage --appimage-signature`.

### 8.4. Release Checksum Manifest Signing

- **`RELEASE_GPG_PRIVATE_KEY`** & **`RELEASE_GPG_PASSPHRASE`**: Used by the `publish-release` job to sign `checksums.txt` and generate `checksums.txt.sig`. Falls back to `LINUX_GPG_PRIVATE_KEY` if not configured separately.

---

## 9. How to Verify Signatures and Checksums

Users and system administrators can verify the integrity and authenticity of downloaded release artifacts.

### 9.1. Checksum Verification

Download `checksums.txt` and the release artifact, then run:

```bash
# Linux
sha256sum --check --ignore-missing checksums.txt

# macOS
shasum -a 256 -c checksums.txt

# Windows (PowerShell)
Get-FileHash -Algorithm SHA256 .\Open-SIME-installer-v*.exe
# Compare with the hash in checksums.txt
```

### 9.2. GPG Manifest Signature Verification

Download `checksums.txt`, `checksums.txt.sig`, and import the release signing public key:

```bash
# Import the release signing public key
gpg --import release-key.asc
# Or fetch from a keyserver
gpg --keyserver keyserver.ubuntu.com --recv-keys <RELEASE_KEY_FINGERPRINT>

# Verify the manifest signature
gpg --verify checksums.txt.sig checksums.txt
```

### 9.3. Windows Authenticode Verification

```powershell
Get-AuthenticodeSignature -FilePath .\Open-SIME-installer-v*.exe | Format-List
```
Verify that `Status` is `Valid`, `StatusMessage` is `Signature verified.`, and `SignerCertificate` displays the expected subject.

### 9.4. macOS Gatekeeper and Notarization Verification

```bash
# 1. Verify deep code signature
codesign --verify --deep --strict --verbose=2 Open-SIME-v*.dmg

# 2. Verify Gatekeeper assessment
spctl --assess --type open --context context:primary-signature --verbose=2 Open-SIME-v*.dmg

# 3. Verify notarization ticket is stapled
xcrun stapler validate Open-SIME-v*.dmg
```

### 9.5. Linux AppImage Signature Verification

```bash
chmod +x Open-SIME-v*.AppImage
./Open-SIME-v*.AppImage --appimage-signature
```
This extracts and validates the embedded GPG signature against your trusted GPG keyring.

---

## 10. Platform-Specific Notes and Troubleshooting

### Windows SmartScreen

A valid Authenticode signature with a standard Code Signing (OV) certificate does not immediately grant SmartScreen reputation. SmartScreen calculates reputation dynamically based on download volume and certificate history:
- Users downloading early builds may see "Windows protected your PC". Clicking **More info > Run anyway** allows installation.
- To prevent SmartScreen warnings on Day 1, an Extended Validation (EV) certificate or established developer identity with Microsoft Partner Center is required.

### macOS Gatekeeper and First Launch

- Release DMGs are signed with a Developer ID Application certificate and notarized by Apple. Gatekeeper verifies the stapled notarization ticket even when offline.
- Unsigned preview builds (from CI pull requests) are **not** notarized: macOS Gatekeeper will block them by default with a "damaged app" or unidentified developer alert. To run unsigned CI preview builds on macOS:
  ```bash
  xattr -d com.apple.quarantine /Applications/"Open SIME.app"
  ```

### Linux FUSE Requirements for AppImage

AppImages require `FUSE` (Filesystem in Userspace) to mount and execute:
- **Ubuntu 22.04 and older**: `libfuse2` is installed or can be installed via `sudo apt install libfuse2`.
- **Ubuntu 24.04 and newer**: Ubuntu 24.04 ships with `fuse3` by default. To run older AppImages on Ubuntu 24.04, install `libfuse2t64`:
  ```bash
  sudo apt install libfuse2t64
  ```
- Alternatively, run without FUSE:
  ```bash
  ./Open-SIME-v*.AppImage --appimage-extract-and-run
  ```
