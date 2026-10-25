# Open SIME Desktop Builds & Sidecar Architecture

See [`../../docs/desktop-builds.md`](../../docs/desktop-builds.md) for the complete guide covering:

1. Sidecar packaging architecture (`esbuild` + `@yao-pkg/pkg`) and trade-offs
2. Target-triple naming conventions (`x86_64-pc-windows-msvc`, `x86_64-apple-darwin`, `aarch64-apple-darwin`, `x86_64-unknown-linux-gnu`)
3. Startup readiness handshake (`SIME_SIDECAR_READY`), port conflict detection (`EADDRINUSE`), and clean shutdown (`--watch-stdin` + Tauri lifecycle hooks)
4. OS application-data storage locations (`md.sime.opensime`) and security guarantees
5. Local development, production builds, and troubleshooting

