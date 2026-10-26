#!/usr/bin/env node
// Generates release notes for Open SIME releases.
//
// Usage:
//   node scripts/generate-release-notes.mjs --version <version> [--prev-tag <tag>] [--output <file>]

import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(__dirname, "..", "..");

export function getGitCommitsSince(prevTag, repoRoot = REPO_ROOT) {
  try {
    const range = prevTag ? `${prevTag}..HEAD` : "HEAD";
    const args = ["log", range, "--pretty=format:* %s (%h)", "--no-merges"];
    const out = execFileSync("git", args, {
      cwd: repoRoot,
      encoding: "utf8",
      stdio: ["ignore", "pipe", "ignore"],
    });
    return out
      .split("\n")
      .map((l) => l.trim())
      .filter(Boolean);
  } catch {
    if (prevTag) {
      try {
        const out = execFileSync("git", ["log", "HEAD", "--pretty=format:* %s (%h)", "--no-merges"], {
          cwd: repoRoot,
          encoding: "utf8",
          stdio: ["ignore", "pipe", "ignore"],
        });
        return out
          .split("\n")
          .map((l) => l.trim())
          .filter(Boolean);
      } catch {
        return [];
      }
    }
    return [];
  }
}

export function generateReleaseNotes({
  version,
  prevTag = null,
  commits = null,
  customNotes = null,
  signStatus = {},
  repoRoot = REPO_ROOT,
} = {}) {
  const cleanVersion = String(version || "").trim().replace(/^v/, "") || "0.1.0";
  const tag = `v${cleanVersion}`;
  const isFirstRelease = !prevTag;

  const lines = [];

  if (isFirstRelease) {
    lines.push("## First official beta release of Open SIME");
    lines.push("");
    lines.push(
      "Open SIME este un client neoficial, gratuit și privat pentru agenda electronică școlară agenda.sime.md, destinat elevilor și părinților."
    );
    lines.push("");
    lines.push(
      "Initial official beta release for Windows (x64 NSIS), macOS (Apple Silicon DMG), and Linux (x64 AppImage)."
    );
  } else {
    lines.push(`## Open SIME ${tag}`);
    lines.push("");
    lines.push(`Release ${tag} of Open SIME for Windows, macOS, and Linux.`);
    lines.push(`Changes since ${prevTag}:`);
  }

  lines.push("");
  lines.push("### Changes");

  if (customNotes && String(customNotes).trim()) {
    lines.push(String(customNotes).trim());
  } else {
    const commitList = Array.isArray(commits)
      ? commits
      : getGitCommitsSince(prevTag, repoRoot);

    if (commitList.length > 0) {
      for (const c of commitList) {
        lines.push(c.startsWith("* ") ? c : `* ${c}`);
      }
    } else {
      lines.push("* Initial release improvements and bug fixes.");
    }
  }

  lines.push("");
  lines.push("### Signing Status");
  const winSigned = Boolean(signStatus.windows);
  const macSigned = Boolean(signStatus.macos);
  const linuxSigned = Boolean(signStatus.linux);
  const sigSigned = Boolean(signStatus.checksums);

  lines.push(
    winSigned
      ? "- **Windows (x64 NSIS)**: Signed with Authenticode certificate"
      : "- **Windows (x64 NSIS)**: Unsigned build"
  );
  lines.push(
    macSigned
      ? "- **macOS (DMG)**: Signed with Developer ID and notarized by Apple"
      : "- **macOS (DMG)**: Unsigned build"
  );
  lines.push(
    linuxSigned
      ? "- **Linux (x64 AppImage)**: AppImage signed with GPG key"
      : "- **Linux (x64 AppImage)**: Unsigned build"
  );
  lines.push(
    sigSigned
      ? "- **Checksums**: Signed with detached GPG signature (checksums.txt.sig)"
      : "- **Checksums**: Unsigned checksums manifest (checksums.txt)"
  );

  lines.push("");
  lines.push("### Platform Notes for Unsigned Builds");
  lines.push(
    '- **Windows**: Microsoft Defender SmartScreen may display *"Windows protected your PC"*. Click **More info**, then click **Run anyway** to launch.'
  );
  lines.push(
    "- **macOS**: Gatekeeper may state the app cannot be opened because Apple cannot check it for malicious software. In Finder, open the **Applications** folder, right-click (or Control-click) **Open SIME**, select **Open**, and click **Open** in the confirmation dialog."
  );
  lines.push(
    "- **Linux**: Make the AppImage executable (`chmod +x Open-SIME-v*.AppImage`) before launching."
  );

  lines.push("");
  lines.push("### Checksum Verification");
  lines.push("To verify download integrity against `checksums.txt`:");
  lines.push("```bash");
  lines.push("sha256sum -c checksums.txt");
  lines.push("```");

  if (sigSigned) {
    lines.push("");
    lines.push("To verify the GPG signature of the checksums manifest:");
    lines.push("```bash");
    lines.push("gpg --verify checksums.txt.sig checksums.txt");
    lines.push("```");
  }

  lines.push("");
  return lines.join("\n");
}

export function parseCliArgs(argv = process.argv) {
  const options = {
    signStatus: {},
  };
  for (let i = 2; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--version" && argv[i + 1]) {
      options.version = argv[i + 1];
      i += 1;
    } else if (arg.startsWith("--version=")) {
      options.version = arg.slice("--version=".length);
    } else if (arg === "--prev-tag" && argv[i + 1]) {
      options.prevTag = argv[i + 1];
      i += 1;
    } else if (arg.startsWith("--prev-tag=")) {
      options.prevTag = arg.slice("--prev-tag=".length);
    } else if (arg === "--output" && argv[i + 1]) {
      options.output = argv[i + 1];
      i += 1;
    } else if (arg.startsWith("--output=")) {
      options.output = arg.slice("--output=".length);
    } else if (arg === "--notes-file" && argv[i + 1]) {
      try {
        options.customNotes = fs.readFileSync(path.resolve(argv[i + 1]), "utf8");
      } catch {}
      i += 1;
    } else if (arg.startsWith("--notes-file=")) {
      try {
        options.customNotes = fs.readFileSync(path.resolve(arg.slice("--notes-file=".length)), "utf8");
      } catch {}
    } else if (arg === "--custom-notes" && argv[i + 1]) {
      options.customNotes = argv[i + 1];
      i += 1;
    } else if (arg.startsWith("--custom-notes=")) {
      options.customNotes = arg.slice("--custom-notes=".length);
    } else if (arg === "--sign-windows") {
      options.signStatus.windows = argv[i + 1] === "true";
      i += 1;
    } else if (arg === "--sign-macos") {
      options.signStatus.macos = argv[i + 1] === "true";
      i += 1;
    } else if (arg === "--sign-linux") {
      options.signStatus.linux = argv[i + 1] === "true";
      i += 1;
    } else if (arg === "--sign-checksums") {
      options.signStatus.checksums = argv[i + 1] === "true";
      i += 1;
    }
  }
  return options;
}

if (path.resolve(process.argv[1] || "") === path.resolve(fileURLToPath(import.meta.url))) {
  try {
    const options = parseCliArgs(process.argv);
    const markdown = generateReleaseNotes(options);
    if (options.output) {
      fs.writeFileSync(path.resolve(options.output), markdown, "utf8");
      console.log(`✓ Release notes written to ${options.output}`);
    } else {
      process.stdout.write(markdown);
    }
  } catch (err) {
    console.error(`✗ Error generating release notes: ${err.message}`);
    process.exit(1);
  }
}
