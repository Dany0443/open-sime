# Open SIME Desktop Builds & Sidecar Architecture

This document explains how **Open SIME** packages its Node.js backend as a self-contained **Tauri 2** sidecar for production desktop builds while keeping local web development (`npm start`) and Tauri development mode (`npm run tauri:dev`) unchanged.

---

## 1. Architecture Overview

Open SIME uses an Express + Cheerio backend (`sime-desktop/server.js`) to authenticate with `agenda.sime.md`, enforce student data isolation, and serve the web interface (`index.html` and `public/`).

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

Tauri 2 resolves `bundle.externalBin` (`["binaries/open-sime-server"]`) by appending the Rust target triple to the binary name inside `sime-desktop/src-tauri/binaries/`:

| Platform / Architecture | Rust Target Triple | Sidecar Binary Path (`src-tauri/binaries/`) |
| --- | --- | --- |
| Windows x64 | `x86_64-pc-windows-msvc` | `open-sime-server-x86_64-pc-windows-msvc.exe` |
| macOS Intel (x64) | `x86_64-apple-darwin` | `open-sime-server-x86_64-apple-darwin` |
| macOS Apple Silicon (ARM64) | `aarch64-apple-darwin` | `open-sime-server-aarch64-apple-darwin` |
| Linux x64 | `x86_64-unknown-linux-gnu` | `open-sime-server-x86_64-unknown-linux-gnu` |

To build the sidecar for the current host or a specific cross-compilation target:

```bash
cd sime-desktop

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

In local web development (`npm start`), data defaults to `sime-desktop/data/` unless `SIME_DATA_DIR`, `SIME_REMEMBER_STORE`, or `SIME_REMEMBER_KEY_FILE` is set.

### Security Protections Preserved

- Outbound requests are strictly restricted to `https://agenda.sime.md` (`requireSimeUrl`).
- Content Security Policy (`default-src 'none'; script-src 'self'; style-src 'self'; ...`), `isCrossOrigin` checks on `/api/auth/resume` and `/api/auth/forget-device`, and `scrubLog` log redaction remain active.
- `public/sw.js` (`open-sime-v2`) uses network-first with cache fallback for static shell assets so desktop updates always load the newest bundled UI files immediately.

---

## 5. Building and Troubleshooting

### Local Development

```bash
cd sime-desktop
npm install
npm run check
npm test
npm start           # Web app at http://127.0.0.1:3000
npm run tauri:dev   # Desktop window in development mode
```

### Production Build

Prerequisites:
- Node.js `>= 20`
- Rust toolchain (`rustup`, `cargo`, `rustc >= 1.77.2`)
- Platform C/C++ build tools (Visual Studio C++ Build Tools + WebView2 on Windows; Xcode Command Line Tools on macOS; `webkit2gtk` development packages on Linux)

```bash
cd sime-desktop
npm run sidecar:build   # Builds src-tauri/binaries/open-sime-server-<target-triple>[.exe]
npm run tauri:build     # Runs sidecar:build via beforeBuildCommand, then compiles the Tauri app
```

### Troubleshooting

- **`EADDRINUSE` on port `17321`**: Another instance of Open SIME (or another local process) is already bound to `127.0.0.1:17321`. Close the existing process and relaunch Open SIME.
- **`cargo metadata` / `rustc` not found during `npm run tauri:build`**: Install Rust via [rustup.rs](https://rustup.rs/) and ensure `cargo` and `rustc` are in `PATH`. Note that `npm run sidecar:build` and `npm test` work with Node.js alone.

