#!/usr/bin/env node
// Builds the self-contained Open SIME Node.js sidecar executable for Tauri 2.
//
// Usage:
//   node scripts/build-sidecar.mjs [--target <rust-target-triple>]
//
// Supported Rust target triples:
//   - x86_64-pc-windows-msvc
//   - x86_64-apple-darwin
//   - aarch64-apple-darwin
//   - x86_64-unknown-linux-gnu

import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { build as esbuild } from "esbuild";
import { exec as pkgExec } from "@yao-pkg/pkg";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DESKTOP_ROOT = path.resolve(__dirname, "..");
const BINARIES_DIR = path.join(DESKTOP_ROOT, "src-tauri", "binaries");
const STAGING_DIR = path.join(DESKTOP_ROOT, ".sidecar-staging");
const SIDECAR_BASE_NAME = "open-sime-server";

const TARGET_MAP = {
  "x86_64-pc-windows-msvc": {
    pkgTarget: "node22-win-x64",
    ext: ".exe",
  },
  "x86_64-apple-darwin": {
    pkgTarget: "node22-macos-x64",
    ext: "",
  },
  "aarch64-apple-darwin": {
    pkgTarget: "node22-macos-arm64",
    ext: "",
  },
  "x86_64-unknown-linux-gnu": {
    pkgTarget: "node22-linux-x64",
    ext: "",
  },
};

const HOST_TRIPLE_MAP = {
  "win32:x64": "x86_64-pc-windows-msvc",
  "darwin:x64": "x86_64-apple-darwin",
  "darwin:arm64": "aarch64-apple-darwin",
  "linux:x64": "x86_64-unknown-linux-gnu",
};

export function resolveTargetTriple(argv = process.argv, env = process.env) {
  for (let i = 0; i < argv.length; i += 1) {
    if (argv[i] === "--target" && argv[i + 1]) {
      return argv[i + 1].trim();
    }
    if (argv[i]?.startsWith("--target=")) {
      return argv[i].slice("--target=".length).trim();
    }
  }

  if (env.SIME_SIDECAR_TARGET) return env.SIME_SIDECAR_TARGET.trim();
  if (env.TAURI_ENV_TARGET_TRIPLE) return env.TAURI_ENV_TARGET_TRIPLE.trim();
  if (env.CARGO_BUILD_TARGET) return env.CARGO_BUILD_TARGET.trim();

  try {
    const rustcOut = execFileSync("rustc", ["-vV"], {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "ignore"],
    });
    const hostMatch = /^host:\s*(\S+)/m.exec(rustcOut);
    if (hostMatch?.[1] && TARGET_MAP[hostMatch[1]]) {
      return hostMatch[1];
    }
  } catch {
    // rustc is optional when building the Node sidecar standalone
  }

  const key = `${process.platform}:${process.arch}`;
  const mapped = HOST_TRIPLE_MAP[key];
  if (!mapped) {
    throw new Error(`Unsupported host platform/arch (${key}). Pass --target <triple> explicitly.`);
  }
  return mapped;
}

export function getSidecarOutputPath(targetTriple) {
  const targetInfo = TARGET_MAP[targetTriple];
  if (!targetInfo) {
    throw new Error(
      `Unsupported Rust target triple "${targetTriple}". Expected one of: ${Object.keys(TARGET_MAP).join(", ")}`
    );
  }
  return path.join(BINARIES_DIR, `${SIDECAR_BASE_NAME}-${targetTriple}${targetInfo.ext}`);
}

function collectEmbeddedAssets(desktopRoot) {
  const assets = {};
  const indexHtmlPath = path.join(desktopRoot, "index.html");
  if (!fs.existsSync(indexHtmlPath)) {
    throw new Error(`Missing required asset: ${indexHtmlPath}`);
  }
  assets["/index.html"] = fs.readFileSync(indexHtmlPath).toString("base64");

  const publicRoot = path.join(desktopRoot, "public");
  if (!fs.existsSync(publicRoot)) {
    throw new Error(`Missing required public directory: ${publicRoot}`);
  }

  const walk = (dir, prefix) => {
    const entries = fs.readdirSync(dir, { withFileTypes: true });
    for (const entry of entries) {
      if (entry.name.startsWith(".")) continue;
      const fullPath = path.join(dir, entry.name);
      const urlPath = `${prefix}/${entry.name}`;
      if (entry.isDirectory()) {
        walk(fullPath, urlPath);
      } else if (entry.isFile()) {
        assets[urlPath] = fs.readFileSync(fullPath).toString("base64");
      }
    }
  };
  walk(publicRoot, "/public");
  return assets;
}

async function stampWindowsPeMetadata(exePath, desktopRoot) {
  try {
    const ResEdit = await import("resedit");
    const buf = fs.readFileSync(exePath);
    const exe = ResEdit.NtExecutable.from(buf);
    const res = ResEdit.NtExecutableResource.from(exe);
    const viList = ResEdit.Resource.VersionInfo.fromEntries(res.entries);
    if (viList && viList.length > 0) {
      const vi = viList[0];
      const langs = vi.getAllLanguagesForStringValues();
      for (const lang of langs) {
        vi.setStringValue(lang, "ProductName", "Open SIME");
        vi.setStringValue(lang, "FileDescription", "Open SIME Backend");
        vi.setStringValue(lang, "CompanyName", "Open SIME Contributors");
        vi.setStringValue(lang, "InternalName", "open-sime-server");
        vi.setStringValue(lang, "OriginalFilename", "open-sime-server.exe");
        vi.setStringValue(lang, "LegalCopyright", "Open SIME Contributors");
      }
      vi.outputToResourceEntries(res.entries);
    }

    const icoPath = path.join(desktopRoot, "src-tauri", "icons", "icon.ico");
    if (fs.existsSync(icoPath)) {
      const icoBuf = fs.readFileSync(icoPath);
      const iconFile = ResEdit.Data.IconFile.from(icoBuf);
      ResEdit.Resource.IconGroupEntry.replaceIconsForResource(
        res.entries,
        1,
        1033,
        iconFile.icons.map((item) => item.data)
      );
    }

    res.outputResource(exe);
    const newBuf = Buffer.from(exe.generate());
    const peOffset = newBuf.readInt32LE(0x3c);
    const subsystemOffset = peOffset + 24 + 68;
    if (newBuf.readUInt16LE(subsystemOffset) === 3) {
      newBuf.writeUInt16LE(2, subsystemOffset);
    }
    fs.writeFileSync(exePath, newBuf);
  } catch (err) {
    console.warn(`Warning: failed to stamp Windows PE metadata on ${exePath}: ${err.message}`);
  }
}

export async function buildSidecar(options = {}) {
  const targetTriple = options.targetTriple || resolveTargetTriple();
  const targetInfo = TARGET_MAP[targetTriple];
  if (!targetInfo) {
    throw new Error(
      `Unsupported Rust target triple "${targetTriple}". Supported triples: ${Object.keys(TARGET_MAP).join(", ")}`
    );
  }

  const outputPath = getSidecarOutputPath(targetTriple);
  const embeddedAssets = collectEmbeddedAssets(DESKTOP_ROOT);

  fs.rmSync(STAGING_DIR, { recursive: true, force: true });
  fs.mkdirSync(STAGING_DIR, { recursive: true });
  fs.mkdirSync(BINARIES_DIR, { recursive: true });

  const bundledCjsPath = path.join(STAGING_DIR, "open-sime-server.cjs");

  try {
    const banner = [
      "const __SIME_IMPORT_META_URL__ = require('node:url').pathToFileURL(__filename).href;",
      "globalThis.__SIME_SIDECAR_MAIN__ = true;",
      `globalThis.__SIME_EMBEDDED_ASSETS__ = ${JSON.stringify(embeddedAssets)};`,
    ].join("\n");

    await esbuild({
      entryPoints: [path.join(DESKTOP_ROOT, "server.js")],
      outfile: bundledCjsPath,
      bundle: true,
      platform: "node",
      target: "node22",
      format: "cjs",
      define: {
        "import.meta.url": "__SIME_IMPORT_META_URL__",
      },
      banner: { js: banner },
      legalComments: "none",
      logLevel: "warning",
    });

    await pkgExec([
      bundledCjsPath,
      "--target",
      targetInfo.pkgTarget,
      "--output",
      outputPath,
      "--public",
      "--no-bytecode",
      "--public-packages",
      "*",
    ]);

    if (!fs.existsSync(outputPath)) {
      throw new Error(`Expected sidecar binary was not created at ${outputPath}`);
    }

    if (targetInfo.ext === ".exe") {
      await stampWindowsPeMetadata(outputPath, DESKTOP_ROOT);
    }

    const stat = fs.statSync(outputPath);
    console.log(
      `Built Open SIME sidecar for ${targetTriple}: ${path.relative(DESKTOP_ROOT, outputPath)} (${(stat.size / (1024 * 1024)).toFixed(1)} MB)`
    );
    return { targetTriple, outputPath, sizeBytes: stat.size };
  } finally {
    fs.rmSync(STAGING_DIR, { recursive: true, force: true });
  }
}

if (path.resolve(process.argv[1] || "") === path.resolve(fileURLToPath(import.meta.url))) {
  buildSidecar().catch((err) => {
    console.error(`Failed to build sidecar: ${err.message}`);
    process.exit(1);
  });
}
