# Open SIME Desktop / Web (`sime-desktop`)

This directory contains the **Open SIME** web application (`server.js` + `public/`) and its **Tauri 2** desktop shell (`src-tauri/`).

## Commands

Run inside `sime-desktop/`:

```bash
npm install         # Install Node.js dependencies and @tauri-apps/cli v2
npm run check       # Syntax check server and client files
npm test            # Run automated test suite (node:test)
npm start           # Start local web server on http://127.0.0.1:3000
npm run tauri:dev   # Launch Tauri 2 desktop development window (auto-starts Express on 127.0.0.1:3000)
npm run tauri:info  # Inspect Tauri environment, Rust toolchain, and WebView2 status
```

## Tauri 2 Configuration (Phase 1 — Development Mode)

- **Configuration**: [`src-tauri/tauri.conf.json`](./src-tauri/tauri.conf.json)
  - `productName`: `"Open SIME"`
  - `identifier`: `"md.sime.opensime"`
  - `build.beforeDevCommand`: `"npm start"` (automatically launches the local Express server)
  - `build.devUrl`: `"http://127.0.0.1:3000"`
  - `build.frontendDist`: `"../public"` (points strictly to the static frontend assets directory so neither the project root nor `node_modules/` is bundled)
  - `bundle.active`: `false` (Phase 1 is development-mode integration; production Node.js sidecar and installer bundling are deferred to a later phase)
- **Prerequisites for `npm run tauri:dev`**:
  - Node.js `>= 20`
  - Rust toolchain (`rustup`, `cargo`, `rustc >= 1.77.2`) and platform WebView (WebView2 on Windows)

## Environment Variables

- `SIME_WEB_HOST`: Bind address (default `127.0.0.1`)
- `SIME_WEB_PORT`: Port number (default `3000`)
- `SIME_DEBUG=1`: Enables `/api/debug/parser-health` (never returns personal data)
- `SIME_REMEMBER_SECRET`: Optional secret for encrypting remember-me tokens
