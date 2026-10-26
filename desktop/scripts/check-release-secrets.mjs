#!/usr/bin/env node
// Validates that all required code signing and release secrets are configured
// in the environment before a production release build proceeds.
//
// Fails fast with actionable error messages if any secret is missing.

export function validateReleaseSecrets(env = process.env) {
  const missing = [];

  // Windows Authenticode
  if (!env.WINDOWS_CERTIFICATE?.trim()) {
    missing.push({
      secret: "WINDOWS_CERTIFICATE",
      platform: "Windows",
      purpose: "Base64-encoded Authenticode PFX certificate for signing executable and installer",
    });
  }
  if (!env.WINDOWS_CERTIFICATE_PASSWORD?.trim()) {
    missing.push({
      secret: "WINDOWS_CERTIFICATE_PASSWORD",
      platform: "Windows",
      purpose: "Password for the Authenticode PFX certificate",
    });
  }

  // macOS Developer ID signing
  if (!env.APPLE_CERTIFICATE?.trim()) {
    missing.push({
      secret: "APPLE_CERTIFICATE",
      platform: "macOS",
      purpose: "Base64-encoded Developer ID Application .p12 certificate",
    });
  }
  if (!env.APPLE_CERTIFICATE_PASSWORD?.trim()) {
    missing.push({
      secret: "APPLE_CERTIFICATE_PASSWORD",
      platform: "macOS",
      purpose: "Password for the Developer ID .p12 certificate",
    });
  }
  if (!env.APPLE_SIGNING_IDENTITY?.trim()) {
    missing.push({
      secret: "APPLE_SIGNING_IDENTITY",
      platform: "macOS",
      purpose: 'Apple signing identity name (e.g. "Developer ID Application: Team Name (TEAMID)")',
    });
  }
  if (!env.APPLE_TEAM_ID?.trim()) {
    missing.push({
      secret: "APPLE_TEAM_ID",
      platform: "macOS",
      purpose: "10-character Apple Developer Team ID",
    });
  }

  // macOS Notarization: check App Store Connect API Key (preferred) or Apple ID + app password (fallback)
  const hasApiKey =
    Boolean(env.APPLE_API_KEY?.trim()) &&
    Boolean(env.APPLE_API_ISSUER?.trim()) &&
    Boolean(env.APPLE_API_KEY_BASE64?.trim() || env.APPLE_API_KEY_PATH?.trim());
  const hasAppleId = Boolean(env.APPLE_ID?.trim()) && Boolean(env.APPLE_PASSWORD?.trim());

  if (!hasApiKey && !hasAppleId) {
    missing.push({
      secret: "APPLE_API_KEY / APPLE_API_ISSUER / APPLE_API_KEY_BASE64",
      platform: "macOS",
      purpose: "App Store Connect API key credentials for xcrun notarytool notarization (or APPLE_ID + APPLE_PASSWORD)",
    });
  }

  // Linux AppImage GPG Signing
  const hasLinuxGpg =
    Boolean(env.LINUX_GPG_PRIVATE_KEY?.trim() || env.RELEASE_GPG_PRIVATE_KEY?.trim()) &&
    Boolean(env.LINUX_GPG_PASSPHRASE?.trim() || env.RELEASE_GPG_PASSPHRASE?.trim());

  if (!hasLinuxGpg) {
    missing.push({
      secret: "LINUX_GPG_PRIVATE_KEY & LINUX_GPG_PASSPHRASE",
      platform: "Linux",
      purpose: "GPG private key and passphrase for signing the AppImage and package manifest",
    });
  }

  // Release Checksum Manifest GPG Signing
  const hasReleaseGpg =
    Boolean(env.RELEASE_GPG_PRIVATE_KEY?.trim() || env.LINUX_GPG_PRIVATE_KEY?.trim()) &&
    Boolean(env.RELEASE_GPG_PASSPHRASE?.trim() || env.LINUX_GPG_PASSPHRASE?.trim());

  if (!hasReleaseGpg) {
    missing.push({
      secret: "RELEASE_GPG_PRIVATE_KEY & RELEASE_GPG_PASSPHRASE",
      platform: "Release",
      purpose: "GPG private key and passphrase for generating checksums.txt.sig detached signature",
    });
  }

  return {
    valid: missing.length === 0,
    missing,
  };
}

if (process.argv[1] && import.meta.url.endsWith(process.argv[1].replace(/\\/g, "/"))) {
  const result = validateReleaseSecrets(process.env);
  if (!result.valid) {
    console.error("══════════════════════════════════════════════════════════════════════════════");
    console.error(" RELEASE SIGNING CREDENTIALS VALIDATION FAILED");
    console.error(" Production releases require real signing credentials. Missing secrets:");
    for (const item of result.missing) {
      console.error(`   - [${item.platform}] ${item.secret}: ${item.purpose}`);
    }
    console.error("──────────────────────────────────────────────────────────────────────────────");
    console.error(" Configure these in GitHub Repository Secrets (Settings > Secrets > Actions).");
    console.error(" For unsigned preview builds, use standard CI pull request / branch pushes.");
    console.error("══════════════════════════════════════════════════════════════════════════════");
    process.exit(1);
  }
  console.log("✓ All required release signing secrets are present.");
  process.exit(0);
}

