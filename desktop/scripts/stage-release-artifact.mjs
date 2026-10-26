#!/usr/bin/env node
// Locates the Tauri bundle output for the specified platform/target,
// cleans any stale release artifacts, and stages the artifact with the
// standardized distributable naming convention:
//   - Windows: Open-SIME-installer-v<version>.exe
//   - macOS:   Open-SIME-v<version>.dmg
//   - Linux:   Open-SIME-v<version>.AppImage
//
// Usage:
//   node scripts/stage-release-artifact.mjs --target <target-triple> [--dest <dir>]
//   node scripts/stage-release-artifact.mjs --platform <windows|macos|linux>
//   node scripts/stage-release-artifact.mjs --name-only --platform <windows|macos|linux>

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { validateVersions } from "./check-versions.mjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DESKTOP_ROOT = path.resolve(__dirname, "..");

export function resolvePlatform(platformOrTarget) {
  if (!platformOrTarget) {
    if (process.platform === "win32") return "windows";
    if (process.platform === "darwin") return "macos";
    return "linux";
  }
  const str = String(platformOrTarget).toLowerCase().trim();
  if (str.includes("darwin") || str.includes("mac") || str.includes("apple")) return "macos";
  if (str.includes("win")) return "windows";
  if (str.includes("linux")) return "linux";
  throw new Error(`Unsupported platform or target triple: "${platformOrTarget}"`);
}

export function getStandardArtifactName(platformOrTarget, version) {
  const platform = resolvePlatform(platformOrTarget);
  if (!version) {
    throw new Error("Application version is required to compute artifact name.");
  }
  const cleanVersion = String(version).trim().replace(/^v/, "");
  const v = `v${cleanVersion}`;

  switch (platform) {
    case "windows":
      return `Open-SIME-installer-${v}.exe`;
    case "macos":
      return `Open-SIME-${v}.dmg`;
    case "linux":
      return `Open-SIME-${v}.AppImage`;
    default:
      throw new Error(`Unsupported platform: "${platform}"`);
  }
}

export function findBundleFile(platformOrTarget, targetTriple, desktopRoot = DESKTOP_ROOT) {
  const platform = resolvePlatform(platformOrTarget || targetTriple);
  const searchDirs = [];

  const formatDirs = {
    windows: "nsis",
    macos: "dmg",
    linux: "appimage",
  };
  const extensions = {
    windows: ".exe",
    macos: ".dmg",
    linux: ".AppImage",
  };

  const bundleSubdir = formatDirs[platform];
  const ext = extensions[platform];

  if (targetTriple) {
    searchDirs.push(
      path.join(desktopRoot, "src-tauri", "target", targetTriple, "release", "bundle", bundleSubdir)
    );
  }
  searchDirs.push(
    path.join(desktopRoot, "src-tauri", "target", "release", "bundle", bundleSubdir)
  );

  const targetBase = path.join(desktopRoot, "src-tauri", "target");
  if (fs.existsSync(targetBase)) {
    for (const entry of fs.readdirSync(targetBase, { withFileTypes: true })) {
      if (entry.isDirectory() && entry.name !== "release" && entry.name !== "debug") {
        searchDirs.push(path.join(targetBase, entry.name, "release", "bundle", bundleSubdir));
      }
    }
  }

  for (const dir of searchDirs) {
    if (!fs.existsSync(dir)) continue;
    const files = fs
      .readdirSync(dir)
      .filter((f) => f.endsWith(ext) && !f.startsWith("."))
      .map((f) => path.join(dir, f));
    if (files.length > 0) {
      return files[0];
    }
  }

  throw new Error(
    `Could not find ${platform} bundle (${bundleSubdir}/*${ext}) in search paths:\n  ${searchDirs.join("\n  ")}`
  );
}

export function cleanStaleArtifacts(destDir, keepArtifactName) {
  if (!fs.existsSync(destDir)) return [];
  const removed = [];
  for (const file of fs.readdirSync(destDir)) {
    if (
      (file.startsWith("Open-SIME-") || file.startsWith("Open-SIME_")) &&
      (file.endsWith(".AppImage") || file.endsWith(".exe") || file.endsWith(".dmg") || file.endsWith(".deb"))
    ) {
      if (file === keepArtifactName) continue;
      const p = path.join(destDir, file);
      try {
        fs.rmSync(p, { force: true });
        removed.push(file);
      } catch {
        // ignore cleanup errors
      }
    }
  }
  return removed;
}

export function stageArtifact(options = {}) {
  const desktopRoot = options.desktopRoot || DESKTOP_ROOT;
  const destDir = options.destDir || desktopRoot;
  const platform = resolvePlatform(options.platform || options.targetTriple);
  const targetTriple = options.targetTriple || null;

  const { version } = validateVersions({ desktopRoot });
  const standardName = getStandardArtifactName(platform, version);
  const destPath = path.join(destDir, standardName);

  const sourcePath = options.sourcePath || findBundleFile(platform, targetTriple, desktopRoot);
  if (!fs.existsSync(sourcePath)) {
    throw new Error(`Bundle source does not exist: ${sourcePath}`);
  }

  // Remove old or conflicting artifacts
  const cleaned = cleanStaleArtifacts(destDir, standardName);
  if (cleaned.length > 0) {
    console.log(`Cleaned stale artifact(s): ${cleaned.join(", ")}`);
  }

  // Copy bundle to final destination
  fs.copyFileSync(sourcePath, destPath);
  if (platform === "linux") {
    try {
      fs.chmodSync(destPath, 0o755);
    } catch {
      // ignore on non-posix systems
    }
  }

  const stat = fs.statSync(destPath);
  console.log(
    `✓ Staged ${platform} artifact as ${standardName} (${(stat.size / (1024 * 1024)).toFixed(1)} MB)`
  );

  return {
    platform,
    version,
    artifactName: standardName,
    artifactPath: destPath,
    sourcePath,
  };
}

export function parseCliArgs(argv = process.argv) {
  const options = {};
  for (let i = 2; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--name-only") {
      options.nameOnly = true;
    } else if (arg === "--platform" && argv[i + 1]) {
      options.platform = argv[i + 1];
      i += 1;
    } else if (arg.startsWith("--platform=")) {
      options.platform = arg.slice("--platform=".length);
    } else if (arg === "--target" && argv[i + 1]) {
      options.targetTriple = argv[i + 1];
      i += 1;
    } else if (arg.startsWith("--target=")) {
      options.targetTriple = arg.slice("--target=".length);
    } else if (arg === "--dest" && argv[i + 1]) {
      options.destDir = path.resolve(argv[i + 1]);
      i += 1;
    } else if (arg.startsWith("--dest=")) {
      options.destDir = path.resolve(arg.slice("--dest=".length));
    } else if (arg === "--source" && argv[i + 1]) {
      options.sourcePath = path.resolve(argv[i + 1]);
      i += 1;
    } else if (arg.startsWith("--source=")) {
      options.sourcePath = path.resolve(arg.slice("--source=".length));
    }
  }
  return options;
}

if (path.resolve(process.argv[1] || "") === path.resolve(fileURLToPath(import.meta.url))) {
  try {
    const options = parseCliArgs(process.argv);
    if (options.nameOnly) {
      const { version } = validateVersions({ desktopRoot: DESKTOP_ROOT });
      const standardName = getStandardArtifactName(
        options.platform || options.targetTriple || process.platform,
        version
      );
      process.stdout.write(standardName + "\n");
      process.exit(0);
    }

    stageArtifact(options);
  } catch (err) {
    console.error(`✗ Staging artifact failed: ${err.message}`);
    process.exit(1);
  }
}
