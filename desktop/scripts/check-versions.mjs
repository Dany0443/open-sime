#!/usr/bin/env node
// Validates that version strings match across package.json, src-tauri/tauri.conf.json,
// and src-tauri/Cargo.toml, and optionally checks against a target release tag.
//
// Usage:
//   node scripts/check-versions.mjs
//   node scripts/check-versions.mjs --tag v1.0.0
//   node scripts/check-versions.mjs --expected 1.0.0

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DESKTOP_ROOT = path.resolve(__dirname, "..");

export function readProjectVersions(desktopRoot = DESKTOP_ROOT, webRoot = path.resolve(desktopRoot, "..", "web")) {
  const webPackageJsonPath = path.join(webRoot, "package.json");
  const desktopPackageJsonPath = path.join(desktopRoot, "package.json");
  const tauriConfPath = path.join(desktopRoot, "src-tauri", "tauri.conf.json");
  const cargoTomlPath = path.join(desktopRoot, "src-tauri", "Cargo.toml");

  if (!fs.existsSync(webPackageJsonPath)) {
    throw new Error(`Missing ${webPackageJsonPath}`);
  }
  if (!fs.existsSync(desktopPackageJsonPath)) {
    throw new Error(`Missing ${desktopPackageJsonPath}`);
  }
  if (!fs.existsSync(tauriConfPath)) {
    throw new Error(`Missing ${tauriConfPath}`);
  }
  if (!fs.existsSync(cargoTomlPath)) {
    throw new Error(`Missing ${cargoTomlPath}`);
  }

  const webPackageJson = JSON.parse(fs.readFileSync(webPackageJsonPath, "utf8"));
  const desktopPackageJson = JSON.parse(fs.readFileSync(desktopPackageJsonPath, "utf8"));
  const tauriConf = JSON.parse(fs.readFileSync(tauriConfPath, "utf8"));
  const cargoToml = fs.readFileSync(cargoTomlPath, "utf8");

  const cargoMatch = /^version\s*=\s*"([^"]+)"/m.exec(cargoToml);
  if (!cargoMatch) {
    throw new Error(`Could not find 'version = "..."' in ${cargoTomlPath}`);
  }

  return {
    webPackageJson: webPackageJson.version?.trim(),
    desktopPackageJson: desktopPackageJson.version?.trim(),
    packageJson: desktopPackageJson.version?.trim(),
    tauriConf: tauriConf.version?.trim(),
    cargoToml: cargoMatch[1]?.trim(),
  };
}

export function validateVersions(options = {}) {
  const desktopRoot = options.desktopRoot || DESKTOP_ROOT;
  const webRoot = options.webRoot || path.resolve(desktopRoot, "..", "web");
  const versions = readProjectVersions(desktopRoot, webRoot);
  const { webPackageJson, desktopPackageJson, tauriConf, cargoToml } = versions;

  if (!webPackageJson || !desktopPackageJson || !tauriConf || !cargoToml) {
    throw new Error(`Incomplete versions found: ${JSON.stringify(versions)}`);
  }

  if (
    webPackageJson !== desktopPackageJson ||
    desktopPackageJson !== tauriConf ||
    desktopPackageJson !== cargoToml
  ) {
    throw new Error(
      `Version mismatch between project files:\n` +
        `  web/package.json:          ${webPackageJson}\n` +
        `  desktop/package.json:      ${desktopPackageJson}\n` +
        `  src-tauri/tauri.conf.json: ${tauriConf}\n` +
        `  src-tauri/Cargo.toml:      ${cargoToml}`
    );
  }

  let expected = options.expectedVersion;
  if (!expected && options.tag) {
    const cleanTag = options.tag.replace(/^refs\/tags\//, "");
    expected = cleanTag.startsWith("v") ? cleanTag.slice(1) : cleanTag;
  }

  if (expected && desktopPackageJson !== expected) {
    throw new Error(
      `Project version (${desktopPackageJson}) does not match expected version (${expected}).`
    );
  }

  return {
    version: desktopPackageJson,
    versions,
  };
}

export function parseCliArgs(argv = process.argv) {
  const options = {};
  for (let i = 2; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--print" || arg === "--version-only") {
      options.printOnly = true;
    } else if (arg === "--tag" && argv[i + 1]) {
      options.tag = argv[i + 1];
      i += 1;
    } else if (arg.startsWith("--tag=")) {
      options.tag = arg.slice("--tag=".length);
    } else if (arg === "--expected" && argv[i + 1]) {
      options.expectedVersion = argv[i + 1];
      i += 1;
    } else if (arg.startsWith("--expected=")) {
      options.expectedVersion = arg.slice("--expected=".length);
    }
  }
  return options;
}

if (path.resolve(process.argv[1] || "") === path.resolve(fileURLToPath(import.meta.url))) {
  try {
    const options = parseCliArgs(process.argv);
    const result = validateVersions(options);
    if (options.printOnly) {
      console.log(result.version);
    } else {
      console.log(`✓ Versions consistent: ${result.version} (web/package.json, desktop/package.json, tauri.conf.json, Cargo.toml)`);
    }
    process.exit(0);
  } catch (err) {
    console.error(`✗ Version check failed: ${err.message}`);
    process.exit(1);
  }
}
