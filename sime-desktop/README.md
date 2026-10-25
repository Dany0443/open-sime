# Open SIME Desktop / Web (`sime-desktop`)

This directory contains the **Open SIME** web application (`server.js` + `public/`) and its **Tauri 2** desktop application (`src-tauri/`).

## Commands

Run inside `sime-desktop/`:

```bash
npm install           # Install Node.js dependencies and Tauri CLI / sidecar tooling
npm run check         # Syntax check server, client, loader, and build scripts
npm test              # Run automated test suite (node:test)
npm start             # Start local web server on http://127.0.0.1:3000
npm run sidecar:build # Build self-contained Node.js sidecar executable into src-tauri/binaries/
npm run tauri:dev     # Launch Tauri 2 desktop development window (auto-starts Express on 127.0.0.1:3000)
npm run tauri:build   # Build production Tauri 2 desktop application with bundled Node.js sidecar
npm run tauri:info    # Inspect Tauri environment, Rust toolchain, and WebView2 status
```

## Documentation

See [`../docs/desktop-builds.md`](../docs/desktop-builds.md) for full details on:
- Sidecar packaging (`esbuild` + `@yao-pkg/pkg`) and target-triple naming (`src-tauri/binaries/open-sime-server-<target-triple>`)
- Startup readiness verification, port `17321` loopback binding, port conflict handling, and clean process shutdown
- OS application-data directory storage (`%APPDATA%\md.sime.opensime`, `~/Library/Application Support/md.sime.opensime`, `~/.local/share/md.sime.opensime`)

## Environment Variables

- `SIME_WEB_HOST`: Bind address (default `127.0.0.1`; forced to `127.0.0.1` in production mode)
- `SIME_WEB_PORT`: Port number (default `3000` in dev mode, `17321` in production sidecar mode)
- `SIME_DATA_DIR`: Override directory for persistent remember-tokens and encryption key
- `SIME_DEBUG=1`: Enables `/api/debug/parser-health` (never returns personal data)
- `SIME_REMEMBER_SECRET`: Optional secret for encrypting remember-me tokens
