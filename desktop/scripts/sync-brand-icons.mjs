#!/usr/bin/env node
/**
 * Reproducible brand icon generator for Open SIME.
 *
 * Canonical source of truth:
 *   web/public/assets/favicon.svg
 *
 * Generates/updates:
 *   1. Desktop Tauri icon assets in `desktop/src-tauri/icons/`
 *      (32x32.png, 64x64.png, 128x128.png, 128x128@2x.png, icon.png, icon.ico, icon.icns,
 *       Square*Logo.png, StoreLogo.png)
 *   2. Android adaptive icon vector drawables in `android/app/src/main/res/drawable/`
 *      (ic_launcher_background.xml, ic_launcher_foreground.xml, ic_launcher_monochrome.xml)
 *      and `android/app/src/main/res/mipmap-anydpi-v26/{ic_launcher,ic_launcher_round}.xml`
 *   3. Android density-specific raster launcher icons in
 *      `android/app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/{ic_launcher,ic_launcher_round}.png`
 */

import { execFileSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DESKTOP_ROOT = path.resolve(__dirname, "..");
const REPO_ROOT = path.resolve(DESKTOP_ROOT, "..");
const CANONICAL_SVG_PATH = path.join(REPO_ROOT, "web", "public", "assets", "favicon.svg");
const DESKTOP_ICONS_DIR = path.join(DESKTOP_ROOT, "src-tauri", "icons");
const ANDROID_RES_DIR = path.join(REPO_ROOT, "android", "app", "src", "main", "res");

const DESKTOP_ICON_FILES = [
  "32x32.png",
  "64x64.png",
  "128x128.png",
  "128x128@2x.png",
  "icon.png",
  "icon.ico",
  "icon.icns",
  "StoreLogo.png",
  "Square30x30Logo.png",
  "Square44x44Logo.png",
  "Square71x71Logo.png",
  "Square89x89Logo.png",
  "Square107x107Logo.png",
  "Square142x142Logo.png",
  "Square150x150Logo.png",
  "Square284x284Logo.png",
  "Square310x310Logo.png",
];

const ANDROID_MIPMAP_DIRS = [
  "mipmap-mdpi",
  "mipmap-hdpi",
  "mipmap-xhdpi",
  "mipmap-xxhdpi",
  "mipmap-xxxhdpi",
];

export function parseCanonicalFaviconSvg(svgContent) {
  const viewBoxMatch = svgContent.match(/viewBox=["']0\s+0\s+(\d+(?:\.\d+)?)\s+(\d+(?:\.\d+)?)["']/);
  const viewWidth = viewBoxMatch ? Number(viewBoxMatch[1]) : 64;
  const viewHeight = viewBoxMatch ? Number(viewBoxMatch[2]) : 64;

  const rectFillMatch = svgContent.match(/<rect\b[^>]*\bfill=["']([^"']+)["'][^>]*\/?>/);
  if (!rectFillMatch) {
    throw new Error("Could not find background <rect fill=\"...\"> in canonical favicon.svg");
  }
  const backgroundColor = rectFillMatch[1].toUpperCase();

  const paths = [];
  const pathRegex = /<path\b([^>]*)\/?>/g;
  let match;
  while ((match = pathRegex.exec(svgContent)) !== null) {
    const attrs = match[1];
    const dMatch = attrs.match(/\bd=["']([^"']+)["']/);
    const fillMatch = attrs.match(/\bfill=["']([^"']+)["']/);
    if (dMatch && fillMatch) {
      paths.push({
        d: dMatch[1],
        fill: fillMatch[1].toUpperCase(),
      });
    }
  }

  if (paths.length === 0) {
    throw new Error("Could not find any foreground <path> elements in canonical favicon.svg");
  }

  return {
    viewWidth,
    viewHeight,
    backgroundColor,
    paths,
  };
}

function buildAndroidBackgroundXml(backgroundColor) {
  return `<?xml version="1.0" encoding="utf-8"?>
<!-- Generated from web/public/assets/favicon.svg by desktop/scripts/sync-brand-icons.mjs -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="${backgroundColor}"
        android:pathData="M0,0h108v108h-108z" />
</vector>
`;
}

function buildAndroidForegroundXml({ viewWidth, viewHeight, paths }, monochrome = false) {
  // Map the 64x64 favicon.svg coordinate space into the 72x72 visible mask area (offset 18,18)
  // of the 108x108 Android adaptive icon viewport so proportions match favicon.svg 1:1.
  const scaleX = Number((72 / viewWidth).toFixed(4));
  const scaleY = Number((72 / viewHeight).toFixed(4));
  const pathNodes = paths
    .map(
      (p) => `        <path
            android:fillColor="${monochrome ? "#FFFFFF" : p.fill}"
            android:pathData="${p.d}" />`
    )
    .join("\n");

  return `<?xml version="1.0" encoding="utf-8"?>
<!-- Generated from web/public/assets/favicon.svg by desktop/scripts/sync-brand-icons.mjs -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <group
        android:scaleX="${scaleX}"
        android:scaleY="${scaleY}"
        android:translateX="18"
        android:translateY="18">
${pathNodes}
    </group>
</vector>
`;
}

function buildAndroidAdaptiveIconXml() {
  return `<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
`;
}

export function syncBrandIcons() {
  if (!fs.existsSync(CANONICAL_SVG_PATH)) {
    throw new Error(`Canonical icon source not found at ${CANONICAL_SVG_PATH}`);
  }

  const svgContent = fs.readFileSync(CANONICAL_SVG_PATH, "utf8");
  const parsed = parseCanonicalFaviconSvg(svgContent);

  // 1. Generate Android adaptive vector drawables from canonical SVG paths & colors
  const drawableDir = path.join(ANDROID_RES_DIR, "drawable");
  const anydpiDir = path.join(ANDROID_RES_DIR, "mipmap-anydpi-v26");
  fs.mkdirSync(drawableDir, { recursive: true });
  fs.mkdirSync(anydpiDir, { recursive: true });

  fs.writeFileSync(
    path.join(drawableDir, "ic_launcher_background.xml"),
    buildAndroidBackgroundXml(parsed.backgroundColor),
    "utf8"
  );
  fs.writeFileSync(
    path.join(drawableDir, "ic_launcher_foreground.xml"),
    buildAndroidForegroundXml(parsed, false),
    "utf8"
  );
  fs.writeFileSync(
    path.join(drawableDir, "ic_launcher_monochrome.xml"),
    buildAndroidForegroundXml(parsed, true),
    "utf8"
  );
  fs.writeFileSync(
    path.join(anydpiDir, "ic_launcher.xml"),
    buildAndroidAdaptiveIconXml(),
    "utf8"
  );
  fs.writeFileSync(
    path.join(anydpiDir, "ic_launcher_round.xml"),
    buildAndroidAdaptiveIconXml(),
    "utf8"
  );

  // 2. Run `tauri icon` in a temporary staging directory to generate desktop & Android raster assets
  const stagingDir = fs.mkdtempSync(path.join(os.tmpdir(), "open-sime-icons-"));
  try {
    const tauriBin = path.join(
      DESKTOP_ROOT,
      "node_modules",
      ".bin",
      process.platform === "win32" ? "tauri.cmd" : "tauri"
    );
    execFileSync(tauriBin, ["icon", CANONICAL_SVG_PATH, "-o", stagingDir], {
      cwd: DESKTOP_ROOT,
      stdio: "inherit",
    });

    // Copy desktop icon assets (preserving existing icon.icns when icon.png is byte-identical
    // so Rust HashMap chunk ordering inside .icns does not create non-deterministic diffs)
    fs.mkdirSync(DESKTOP_ICONS_DIR, { recursive: true });
    const existingIconPng = path.join(DESKTOP_ICONS_DIR, "icon.png");
    const generatedIconPng = path.join(stagingDir, "icon.png");
    const iconPngUnchanged =
      fs.existsSync(existingIconPng) &&
      fs.existsSync(generatedIconPng) &&
      fs.readFileSync(existingIconPng).equals(fs.readFileSync(generatedIconPng));

    for (const fileName of DESKTOP_ICON_FILES) {
      const src = path.join(stagingDir, fileName);
      const dest = path.join(DESKTOP_ICONS_DIR, fileName);
      if (fileName === "icon.icns" && iconPngUnchanged && fs.existsSync(dest)) {
        continue;
      }
      if (fs.existsSync(src)) {
        fs.copyFileSync(src, dest);
      }
    }

    // Copy Android density-specific raster launcher icons (ic_launcher.png & ic_launcher_round.png)
    for (const mipmapDir of ANDROID_MIPMAP_DIRS) {
      const srcDir = path.join(stagingDir, "android", mipmapDir);
      const destDir = path.join(ANDROID_RES_DIR, mipmapDir);
      if (fs.existsSync(srcDir)) {
        fs.mkdirSync(destDir, { recursive: true });
        for (const pngName of ["ic_launcher.png", "ic_launcher_round.png"]) {
          const srcPng = path.join(srcDir, pngName);
          if (fs.existsSync(srcPng)) {
            fs.copyFileSync(srcPng, path.join(destDir, pngName));
          }
        }
      }
    }
  } finally {
    fs.rmSync(stagingDir, { recursive: true, force: true });
  }

  console.log("✓ Synchronized Open SIME brand icons across web, desktop, and Android from web/public/assets/favicon.svg");
}

if (path.resolve(process.argv[1] || "") === path.resolve(fileURLToPath(import.meta.url))) {
  syncBrandIcons();
}
