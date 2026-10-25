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

export function readProjectVersions(desktopRoot = DESKTOP_ROOT) {
  const packageJsonPath = path.join(desktopRoot, "package.json");
  const tauriConfPath = path.join(desktopRoot, "src-tauri", "tauri.conf.json");
  const cargoTomlPath = path.join(desktopRoot, "src-tauri", "Cargo.toml");

  if (!fs.existsSync(packageJsonPath)) {
    throw new Error(`Missing ${packageJsonPath}`);
  }
  if (!fs.existsSync(tauriConfPath)) {
    throw new Error(`Missing ${tauriConfPath}`);
  }
  if (!fs.existsSync(cargoTomlPath)) {
    throw new Error(`Missing ${cargoTomlPath}`);
  }

  const packageJson = JSON.parse(fs.readFileSync(packageJsonPath, "utf8"));
  const tauriConf = JSON.parse(fs.readFileSync(tauriConfPath, "utf8"));
  const cargoToml = fs.readFileSync(cargoTomlPath, "utf8");

  const cargoMatch = /^version\s*=\s*"([^"]+)"/m.exec(cargoToml);
  if (!cargoMatch) {
    throw new Error(`Could not find 'version = "..."' in ${cargoTomlPath}`);
  }

  return {
    packageJson: packageJson.version?.trim(),
    tauriConf: tauriConf.version?.trim(),
    cargoToml: cargoMatch[1]?.trim(),
  };
}

export function validateVersions(options = {}) {
  const desktopRoot = options.desktopRoot || DESKTOP_ROOT;
  const versions = readProjectVersions(desktopRoot);
  const { packageJson, tauriConf, cargoToml } = versions;

  if (!packageJson || !tauriConf || !cargoToml) {
    throw new Error(`Incomplete versions found: ${JSON.stringify(versions)}`);
  }

  if (packageJson !== tauriConf || packageJson !== cargoToml) {
    throw new Error(
      `Version mismatch between project files:\n` +
        `  package.json:        ${packageJson}\n` +
        `  src-tauri/tauri.conf.json: ${tauriConf}\n` +
        `  src-tauri/Cargo.toml:      ${cargoToml}`
    );
  }

  let expected = options.expectedVersion;
  if (!expected && options.tag) {
    const cleanTag = options.tag.replace(/^refs\/tags\//, "");
    expected = cleanTag.startsWith("v") ? cleanTag.slice(1) : cleanTag;
  }

  if (expected && packageJson !== expected) {
    throw new Error(
      `Project version (${packageJson}) does not match expected version (${expected}).`
    );
  }

  return {
    version: packageJson,
    versions,
  };
}

export function parseCliArgs(argv = process.argv) {
  const options = {};
  for (let i = 2; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--tag" && argv[i + 1]) {
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
    console.log(`✓ Versions consistent: ${result.version} (package.json, tauri.conf.json, Cargo.toml)`);
    process.exit(0);
  } catch (err) {
    console.error(`✗ Version check failed: ${err.message}`);
    process.exit(1);
  }
}
