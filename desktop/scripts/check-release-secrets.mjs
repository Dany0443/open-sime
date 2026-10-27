#!/usr/bin/env node
// Inspects code signing and release secrets in the environment per platform.
// Code signing is optional per platform:
//   - windows: sign if WINDOWS_CERTIFICATE is present; build unsigned if absent.
//   - macos: sign and notarize if APPLE_CERTIFICATE is present; build unsigned if absent.
//   - linux: sign AppImage if LINUX_GPG_PRIVATE_KEY (or RELEASE_GPG_PRIVATE_KEY) is present; build unsigned if absent.
//   - checksums: sign checksums.txt if RELEASE_GPG_PRIVATE_KEY (or LINUX_GPG_PRIVATE_KEY) is present; generate without signature if absent.
//
// Fails only if secrets for a platform are partially configured.
// Never prints secret values.

import fs from "node:fs";

export function validateReleaseSecrets(env = process.env) {
  const errors = [];
  const platforms = {
    windows: { sign: false, configured: false },
    macos: { sign: false, configured: false },
    linux: { sign: false, configured: false },
    checksums: { sign: false, configured: false },
    android: { sign: false, configured: false },
  };

  // 1. Windows Authenticode
  const hasWinCert = Boolean(env.WINDOWS_CERTIFICATE?.trim());
  const hasWinPass = Boolean(env.WINDOWS_CERTIFICATE_PASSWORD?.trim());

  if (hasWinCert || hasWinPass) {
    platforms.windows.configured = true;
    if (!hasWinCert) {
      errors.push("Windows signing is partially configured: missing WINDOWS_CERTIFICATE.");
    }
    if (!hasWinPass) {
      errors.push("Windows signing is partially configured: missing WINDOWS_CERTIFICATE_PASSWORD.");
    }
    if (hasWinCert && hasWinPass) {
      platforms.windows.sign = true;
    }
  }

  // 2. macOS Developer ID + Notarization
  const hasMacCert = Boolean(env.APPLE_CERTIFICATE?.trim());
  const hasMacPass = Boolean(env.APPLE_CERTIFICATE_PASSWORD?.trim());
  const hasMacIdentity = Boolean(env.APPLE_SIGNING_IDENTITY?.trim());
  const hasMacTeam = Boolean(env.APPLE_TEAM_ID?.trim());
  const hasApiKey =
    Boolean(env.APPLE_API_KEY?.trim()) &&
    Boolean(env.APPLE_API_ISSUER?.trim()) &&
    Boolean(env.APPLE_API_KEY_BASE64?.trim() || env.APPLE_API_KEY_PATH?.trim());
  const hasAppleId = Boolean(env.APPLE_ID?.trim()) && Boolean(env.APPLE_PASSWORD?.trim());
  const hasAnyNotarization =
    Boolean(env.APPLE_API_KEY?.trim()) ||
    Boolean(env.APPLE_API_ISSUER?.trim()) ||
    Boolean(env.APPLE_API_KEY_BASE64?.trim()) ||
    Boolean(env.APPLE_API_KEY_PATH?.trim()) ||
    Boolean(env.APPLE_ID?.trim()) ||
    Boolean(env.APPLE_PASSWORD?.trim());

  const hasAnyMac = hasMacCert || hasMacPass || hasMacIdentity || hasMacTeam || hasAnyNotarization;

  if (hasAnyMac) {
    platforms.macos.configured = true;
    if (!hasMacCert) {
      errors.push("macOS signing is partially configured: missing APPLE_CERTIFICATE.");
    }
    if (!hasMacPass) {
      errors.push("macOS signing is partially configured: missing APPLE_CERTIFICATE_PASSWORD.");
    }
    if (!hasMacIdentity) {
      errors.push("macOS signing is partially configured: missing APPLE_SIGNING_IDENTITY.");
    }
    if (!hasMacTeam) {
      errors.push("macOS signing is partially configured: missing APPLE_TEAM_ID.");
    }
    if (!hasApiKey && !hasAppleId) {
      errors.push(
        "macOS notarization is partially configured: requires either App Store Connect API Key (APPLE_API_KEY, APPLE_API_ISSUER, and APPLE_API_KEY_BASE64 or APPLE_API_KEY_PATH) or Apple ID credentials (APPLE_ID and APPLE_PASSWORD)."
      );
    }
    if (hasMacCert && hasMacPass && hasMacIdentity && hasMacTeam && (hasApiKey || hasAppleId)) {
      platforms.macos.sign = true;
    }
  }

  // 3. Linux AppImage GPG signing
  const hasLinuxKey = Boolean(env.LINUX_GPG_PRIVATE_KEY?.trim() || env.RELEASE_GPG_PRIVATE_KEY?.trim());
  const hasLinuxPass = Boolean(env.LINUX_GPG_PASSPHRASE?.trim() || env.RELEASE_GPG_PASSPHRASE?.trim());

  if (hasLinuxKey || hasLinuxPass) {
    platforms.linux.configured = true;
    if (!hasLinuxKey) {
      errors.push("Linux signing is partially configured: missing LINUX_GPG_PRIVATE_KEY (or RELEASE_GPG_PRIVATE_KEY).");
    }
    if (!hasLinuxPass) {
      errors.push("Linux signing is partially configured: missing LINUX_GPG_PASSPHRASE (or RELEASE_GPG_PASSPHRASE).");
    }
    if (hasLinuxKey && hasLinuxPass) {
      platforms.linux.sign = true;
    }
  }

  // 4. Release Checksums manifest GPG signing
  const hasChecksumKey = Boolean(env.RELEASE_GPG_PRIVATE_KEY?.trim() || env.LINUX_GPG_PRIVATE_KEY?.trim());
  const hasChecksumPass = Boolean(env.RELEASE_GPG_PASSPHRASE?.trim() || env.LINUX_GPG_PASSPHRASE?.trim());

  if (hasChecksumKey || hasChecksumPass) {
    platforms.checksums.configured = true;
    if (!hasChecksumKey) {
      errors.push("Checksums signing is partially configured: missing RELEASE_GPG_PRIVATE_KEY (or LINUX_GPG_PRIVATE_KEY).");
    }
    if (!hasChecksumPass) {
      errors.push("Checksums signing is partially configured: missing RELEASE_GPG_PASSPHRASE (or LINUX_GPG_PASSPHRASE).");
    }
    if (hasChecksumKey && hasChecksumPass) {
      platforms.checksums.sign = true;
    }
  }

  // 5. Android APK/AAB signing
  const hasAndroidKeystore = Boolean(
    env.ANDROID_KEYSTORE_BASE64?.trim() ||
    env.SIME_ANDROID_KEYSTORE_BASE64?.trim() ||
    env.ANDROID_KEYSTORE_PATH?.trim() ||
    env.SIME_ANDROID_KEYSTORE_PATH?.trim()
  );
  const hasAndroidStorePass = Boolean(env.ANDROID_KEYSTORE_PASSWORD?.trim() || env.SIME_ANDROID_KEYSTORE_PASSWORD?.trim());
  const hasAndroidKeyAlias = Boolean(env.ANDROID_KEY_ALIAS?.trim() || env.SIME_ANDROID_KEY_ALIAS?.trim());
  const hasAndroidKeyPass = Boolean(env.ANDROID_KEY_PASSWORD?.trim() || env.SIME_ANDROID_KEY_PASSWORD?.trim());

  if (hasAndroidKeystore || hasAndroidStorePass || hasAndroidKeyAlias || hasAndroidKeyPass) {
    platforms.android.configured = true;
    if (!hasAndroidKeystore) {
      errors.push("Android signing is partially configured: missing ANDROID_KEYSTORE_BASE64 (or ANDROID_KEYSTORE_PATH).");
    }
    if (!hasAndroidStorePass) {
      errors.push("Android signing is partially configured: missing ANDROID_KEYSTORE_PASSWORD.");
    }
    if (!hasAndroidKeyAlias) {
      errors.push("Android signing is partially configured: missing ANDROID_KEY_ALIAS.");
    }
    if (!hasAndroidKeyPass) {
      errors.push("Android signing is partially configured: missing ANDROID_KEY_PASSWORD.");
    }
    if (hasAndroidKeystore && hasAndroidStorePass && hasAndroidKeyAlias && hasAndroidKeyPass) {
      platforms.android.sign = true;
    }
  }

  const valid = errors.length === 0;

  return {
    valid,
    errors,
    sign_windows: platforms.windows.sign,
    sign_macos: platforms.macos.sign,
    sign_linux: platforms.linux.sign,
    sign_checksums: platforms.checksums.sign,
    sign_android: platforms.android.sign,
    platforms,
  };
}

export function writeGitHubOutputs(result, outputPath = process.env.GITHUB_OUTPUT) {
  if (!outputPath) return;
  const lines = [
    `sign_windows=${result.sign_windows}`,
    `sign_macos=${result.sign_macos}`,
    `sign_linux=${result.sign_linux}`,
    `sign_checksums=${result.sign_checksums}`,
    `sign_android=${result.sign_android}`,
  ].join("\n") + "\n";
  fs.appendFileSync(outputPath, lines, "utf8");
}

if (process.argv[1] && import.meta.url.endsWith(process.argv[1].replace(/\\/g, "/"))) {
  const result = validateReleaseSecrets(process.env);
  if (!result.valid) {
    console.error("══════════════════════════════════════════════════════════════════════════════");
    console.error(" RELEASE SIGNING CREDENTIALS ERROR: PARTIAL CONFIGURATION DETECTED");
    console.error(" Secrets for one or more platforms are incomplete. Refusing to proceed:");
    for (const err of result.errors) {
      console.error(`   - ${err}`);
    }
    console.error("──────────────────────────────────────────────────────────────────────────────");
    console.error(" Provide all required secrets for that platform, or remove them entirely to build unsigned.");
    console.error(" NEVER log or commit secret values.");
    console.error("══════════════════════════════════════════════════════════════════════════════");
    process.exit(1);
  }

  writeGitHubOutputs(result);

  console.log("=== Release Signing Status ===");
  console.log(`  Windows (Authenticode):  ${result.sign_windows ? "ENABLED (signed installer)" : "DISABLED (unsigned installer)"}`);
  console.log(`  macOS (Developer ID):    ${result.sign_macos ? "ENABLED (signed & notarized)" : "DISABLED (unsigned DMG)"}`);
  console.log(`  Linux (AppImage GPG):    ${result.sign_linux ? "ENABLED (signed AppImage)" : "DISABLED (unsigned AppImage)"}`);
  console.log(`  Android (APK/AAB):       ${result.sign_android ? "ENABLED (signed release)" : "DISABLED (unsigned build)"}`);
  console.log(`  Checksums (GPG):         ${result.sign_checksums ? "ENABLED (checksums.txt.sig)" : "DISABLED (unsigned checksums.txt)"}`);
  console.log("==============================");
  process.exit(0);
}
