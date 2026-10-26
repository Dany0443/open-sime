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
import os from "node:os";
import path from "node:path";
import { execFileSync } from "node:child_process";
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

export function optimizeLinuxAppImage(appImagePath, desktopRoot = DESKTOP_ROOT) {
  if (process.platform !== "linux" || !fs.existsSync(appImagePath)) {
    return false;
  }

  // Skip optimization if signing is active or explicitly disabled
  if (
    process.env.SIME_NO_APPIMAGE_OPTIMIZE === "1" ||
    process.env.APPIMAGETOOL_FORCE_SIGN === "1" ||
    process.env.SIGN === "1"
  ) {
    return false;
  }

  const homedir = os.homedir();
  const tauriCache = path.join(homedir, ".cache", "tauri");
  const pluginAppImage = path.join(tauriCache, "linuxdeploy-plugin-appimage.AppImage");
  let runtimeFile = process.env.LDAI_RUNTIME_FILE || path.join(tauriCache, "runtime-x86_64");
  if (!fs.existsSync(runtimeFile)) {
    const altRuntime = path.join(tauriCache, "AppRun-x86_64");
    if (fs.existsSync(altRuntime)) runtimeFile = altRuntime;
  }

  let toolAppRun = "";
  let toolTempDir = null;

  try {
    const whichOut = execFileSync("which", ["appimagetool"], { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }).trim();
    if (whichOut && fs.existsSync(whichOut)) {
      toolAppRun = whichOut;
    }
  } catch {
    // appimagetool not in PATH
  }

  if (!toolAppRun && fs.existsSync(pluginAppImage)) {
    toolTempDir = fs.mkdtempSync(path.join(os.tmpdir(), "sime-tool-"));
    try {
      execFileSync(pluginAppImage, ["--appimage-extract"], { cwd: toolTempDir, stdio: "ignore" });
      const extractedAppRun = path.join(toolTempDir, "squashfs-root", "appimagetool-prefix", "AppRun");
      if (fs.existsSync(extractedAppRun)) {
        toolAppRun = extractedAppRun;
      }
    } catch {
      // extraction failed
    }
  }

  if (!toolAppRun) {
    return false;
  }

  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "sime-appimage-opt-"));
  try {
    execFileSync(appImagePath, ["--appimage-extract"], { cwd: tmpDir, stdio: "ignore" });
    const appDir = path.join(tmpDir, "squashfs-root");
    if (!fs.existsSync(appDir)) return false;

    // 1. Ensure genuine high-resolution PNG icons at AppDir root
    const iconSource = path.join(desktopRoot, "src-tauri", "icons", "icon.png");
    const fallbackIcon = path.join(desktopRoot, "src-tauri", "icons", "128x128@2x.png");
    const bestIcon = fs.existsSync(iconSource) ? iconSource : fallbackIcon;

    if (fs.existsSync(bestIcon)) {
      const iconData = fs.readFileSync(bestIcon);
      for (const iconName of [".DirIcon", "open-sime-desktop.png", "Open SIME.png"]) {
        const iconPath = path.join(appDir, iconName);
        try {
          fs.rmSync(iconPath, { force: true });
        } catch {
          // ignore rm error
        }
        fs.writeFileSync(iconPath, iconData);
      }
    }

    // 2. Ensure openSUSE/Fedora WebKit helper binaries are present if host requires them
    const hostWebKitHelpers = "/usr/libexec/libwebkit2gtk-4_1-0";
    const appDirWebKitHelpers = path.join(appDir, "usr", "libexec", "libwebkit2gtk-4_1-0");
    if (fs.existsSync(hostWebKitHelpers) && !fs.existsSync(appDirWebKitHelpers)) {
      fs.mkdirSync(path.dirname(appDirWebKitHelpers), { recursive: true });
      fs.cpSync(hostWebKitHelpers, appDirWebKitHelpers, { recursive: true });
    }

    // 3. Ensure frontend binary is stripped if strip command is available
    const appBinary = path.join(appDir, "usr", "bin", "open-sime-desktop");
    if (fs.existsSync(appBinary)) {
      try {
        execFileSync("strip", ["--strip-unneeded", appBinary], { stdio: "ignore" });
      } catch {
        // ignore strip failure
      }
    }

    // 4. Repackage with optimal zstd compression
    const optimizedAppImage = path.join(tmpDir, "optimized.AppImage");
    const appImageArgs = [
      "--mksquashfs-opt", "-Xcompression-level",
      "--mksquashfs-opt", "19",
      "--mksquashfs-opt", "-b",
      "--mksquashfs-opt", "1M",
    ];
    if (fs.existsSync(runtimeFile)) {
      appImageArgs.push("--runtime-file", runtimeFile);
    }
    appImageArgs.push(appDir, optimizedAppImage);

    execFileSync(toolAppRun, appImageArgs, {
      env: { ...process.env, ARCH: "x86_64" },
      stdio: "ignore",
    });

    if (fs.existsSync(optimizedAppImage)) {
      const origSize = fs.statSync(appImagePath).size;
      const newSize = fs.statSync(optimizedAppImage).size;
      fs.copyFileSync(optimizedAppImage, appImagePath);
      fs.chmodSync(appImagePath, 0o755);
      console.log(
        `✓ Optimized and minified AppImage with zstd-19 and embedded icon: ${(origSize / (1024 * 1024)).toFixed(1)} MB -> ${(newSize / (1024 * 1024)).toFixed(1)} MB`
      );
      return true;
    }
  } catch (err) {
    console.warn(`Note: AppImage post-minification skipped: ${err.message}`);
  } finally {
    fs.rmSync(tmpDir, { recursive: true, force: true });
    if (toolTempDir) {
      fs.rmSync(toolTempDir, { recursive: true, force: true });
    }
  }
  return false;
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
  const repoRoot = path.resolve(desktopRoot, "..");
  if (repoRoot !== destDir && fs.existsSync(repoRoot)) {
    const cleanedRepo = cleanStaleArtifacts(repoRoot, "");
    cleaned.push(...cleanedRepo);
  }
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
    optimizeLinuxAppImage(destPath, desktopRoot);
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
