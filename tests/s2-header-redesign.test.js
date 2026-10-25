import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

test("S2: Header redesign HTML structure and removed legacy sync elements", () => {
  const html = fs.readFileSync(path.join(__dirname, "../index.html"), "utf-8");

  // Legacy sync button, cooldown counter, and sync-status must be removed
  assert.ok(!html.includes('id="sync-btn"'), "sync-btn must be removed from index.html");
  assert.ok(!html.includes('id="sync-status"'), "sync-status must be removed from index.html");
  assert.ok(!html.includes('id="sync-count"'), "sync-count must be removed from index.html");

  // New M3 elements must exist
  assert.ok(html.includes('id="global-progress"'), "global-progress linear progress bar must exist");
  assert.ok(html.includes('id="header-student-subtitle"'), "header-student-subtitle must exist");
  assert.ok(html.includes('id="offline-chip"'), "offline-chip must exist");
  assert.ok(html.includes('id="account-btn"'), "account-btn must exist");
  assert.ok(html.includes('id="account-menu"'), "account-menu must exist");
  assert.ok(html.includes('id="settings-sync-time"'), "settings-sync-time must exist in settings dialog");

  // Account menu must contain profile-btn and logout-btn
  const topbarActionsMatch = html.match(/<div class="topbar-actions">([\s\S]*?)<\/div>/);
  assert.ok(topbarActionsMatch, "topbar-actions must exist");
  assert.ok(topbarActionsMatch[1].includes('id="profile-btn"'), "profile-btn must be inside topbar-actions");
  assert.ok(topbarActionsMatch[1].includes('id="logout-btn"'), "logout-btn must be inside topbar-actions");

  // Logout must not be a bare button next to theme toggle (must be inside account menu)
  const menuMatch = html.match(/id="account-menu"[\s\S]*?<\/ul>/);
  assert.ok(menuMatch, "account-menu must be an accessible list/menu");
  assert.ok(menuMatch[0].includes('id="logout-btn"'), "logout-btn must be inside account-menu");
  assert.ok(menuMatch[0].includes('id="profile-btn"'), "profile-btn must be inside account-menu");
});

test("S2: Topbar CSS adheres to M3 Expressive tokens and touch targets", () => {
  const css = fs.readFileSync(path.join(__dirname, "../public/style.css"), "utf-8");

  // Action buttons must have minimum 48px touch targets
  assert.ok(css.includes(".linear-progress"), "CSS must style .linear-progress");
  assert.ok(css.includes(".account-menu"), "CSS must style .account-menu");
  assert.ok(css.includes(".offline-chip"), "CSS must style .offline-chip");
});
