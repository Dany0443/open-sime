#!/usr/bin/env node
// Diagnostic capture script for agenda.sime.md absence sources.
// Dumps calendar AJAX, APEX absence regions, and semester report AJAX responses.
// Runs the output through sanitizeCapture before writing to disk.

import fs from "node:fs";
import path from "node:path";
import * as cheerio from "cheerio";
import {
  SimeClient,
  findCalendarAjaxId,
  findReportAjaxId,
  REGIONS,
  todayChisinau,
} from "../server.js";
import { sanitizeCapture } from "./sanitize-capture.mjs";

function parseArgs() {
  const args = process.argv.slice(2);
  let knownAbsencesFile = null;
  let outFile = null;

  for (let i = 0; i < args.length; i += 1) {
    if (args[i] === "--known-absences" && args[i + 1]) {
      knownAbsencesFile = args[i + 1];
      i += 1;
    } else if (args[i] === "--out" && args[i + 1]) {
      outFile = args[i + 1];
      i += 1;
    }
  }

  return { knownAbsencesFile, outFile };
}

function parseKnownAbsences(filePath) {
  if (!filePath || !fs.existsSync(filePath)) return [];
  const lines = fs.readFileSync(filePath, "utf-8").split(/\r?\n/);
  const out = [];
  for (const rawLine of lines) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#")) continue;
    // Expected format: "2024-10-14 Istorie" or "14.10.2024 Istorie"
    const match = line.match(/^(\d{4}-\d{2}-\d{2}|\d{2}\.\d{2}\.\d{4})\s+(.*)$/);
    if (match) {
      out.push({ date: match[1], subject: match[2].trim() });
    } else {
      out.push({ raw: line });
    }
  }
  return out;
}

function findAbsenceApexRegions($) {
  const regions = [];
  $("*").each((_i, el) => {
    const id = $(el).attr("id") || "";
    const cls = $(el).attr("class") || "";
    const headers = $(el).attr("headers") || "";
    const text = $(el).children().length === 0 ? $(el).text() : "";

    const hasAbsen =
      /absen/i.test(id) ||
      /absen/i.test(cls) ||
      /absen/i.test(headers) ||
      ($(el).is("h1, h2, h3, h4, h5, th, .t-Region-title") && /absen/i.test($(el).text()));

    if (hasAbsen) {
      const container = $(el).closest(".t-Region, table, section, div[id]");
      const target = container.length ? container : $(el);
      const html = $.html(target);
      if (html && !regions.some((r) => r.html === html)) {
        regions.push({
          tag: el.tagName,
          id,
          class: cls,
          headers,
          html,
        });
      }
    }
  });
  return regions;
}

function scanKnownAbsenceMatches(knownList, calendarEvents, apexRegions, reports) {
  const highlights = [];

  for (const item of knownList) {
    const query = item.date || item.raw;
    const subj = item.subject || "";

    // 1. Scan calendar events
    for (let idx = 0; idx < calendarEvents.length; idx += 1) {
      const ev = calendarEvents[idx];
      const evStr = JSON.stringify(ev);
      if ((query && evStr.includes(query)) || (subj && evStr.toLowerCase().includes(subj.toLowerCase()))) {
        highlights.push({
          target: item,
          source: "calendar",
          eventIndex: idx,
          start: ev.start,
          end: ev.end,
          className: ev.className || ev.classNames || null,
          titleMarkup: ev.title || null,
          dataAttributes: Object.fromEntries(
            Object.entries(ev).filter(([k]) => k.startsWith("data-") || k === "id" || k === "status")
          ),
        });
      }
    }

    // 2. Scan APEX regions and report tables
    const allHtmlSources = [
      ...apexRegions.map((r, i) => ({ name: `region_${i}`, html: r.html })),
      ...reports.map((rep) => ({ name: `report_sem${rep.semester}`, html: rep.html })),
    ];

    for (const src of allHtmlSources) {
      if (!src.html.includes(query) && (!subj || !src.html.toLowerCase().includes(subj.toLowerCase()))) {
        continue;
      }
      const $ = cheerio.load(src.html, { decodeEntities: false }, false);
      $("*").each((_i, el) => {
        const text = $(el).text();
        if ((query && text.includes(query)) || (subj && text.toLowerCase().includes(subj.toLowerCase()))) {
          const classes = $(el).attr("class") || "";
          const attrs = el.attribs || {};
          const parentClasses = $(el).parent().attr("class") || "";
          const iconEl = $(el).find("span[class*='fa-'], span[class*='t-Icon'], svg, i");
          highlights.push({
            target: item,
            source: src.name,
            tag: el.tagName,
            classes,
            parentClasses,
            attributes: Object.fromEntries(
              Object.entries(attrs).filter(([k]) => k.startsWith("data-") || k === "headers" || k === "id")
            ),
            icons: iconEl.map((_j, ic) => $(ic).attr("class") || ic.tagName).get(),
          });
        }
      });
    }
  }

  return highlights;
}

async function run() {
  const username = process.env.SIME_USER;
  const password = process.env.SIME_PASS;
  const role = process.env.SIME_ROLE || "student";

  if (!username || !password) {
    console.error("Error: SIME_USER and SIME_PASS environment variables must be set.");
    console.error("Usage: SIME_USER=... SIME_PASS=... [SIME_ROLE=student|parent] node scripts/capture-absence-sources.mjs [--known-absences <file>] [--out <file>]");
    process.exit(1);
  }

  const { knownAbsencesFile, outFile } = parseArgs();
  const knownAbsences = parseKnownAbsences(knownAbsencesFile);

  console.log(`Connecting to agenda.sime.md as role: ${role}...`);
  const client = new SimeClient(username, password, role);

  const portalPage = await client.loadPortal();
  const students = client.registerStudents(portalPage);
  const activeStudent = students[0] || { id: null };
  const studentId = activeStudent.id;

  const studentPage = studentId ? await client.loadStudent(studentId, { assertCurrent: true }) : portalPage;
  const $ = studentPage.$;

  // 1. Calendar raw events
  let rawCalendarEvents = [];
  const calAjaxId = findCalendarAjaxId(studentPage.html);
  if (calAjaxId) {
    const today = todayChisinau();
    const year = Number(today.slice(0, 4));
    const startYear = Number(today.slice(5, 7)) >= 9 ? year : year - 1;
    const startStr = `${startYear}0901000000`;
    const endStr = `${today.replaceAll("-", "")}235959`;

    try {
      const resp = await client.ajax(studentPage, calAjaxId, {
        x01: "GET",
        x02: startStr,
        x03: endStr,
      });
      const parsed = JSON.parse(resp);
      if (Array.isArray(parsed)) rawCalendarEvents = parsed;
    } catch (err) {
      console.warn("Could not fetch calendar AJAX:", err.message);
    }
  }

  // 2. APEX absence regions from dashboard
  const absenceApexRegions = findAbsenceApexRegions($);

  // 3. Semester report AJAX responses
  const reports = [];
  for (const reg of REGIONS) {
    const repAjaxId = findReportAjaxId(studentPage.html, reg.id);
    if (!repAjaxId) continue;
    try {
      const repHtml = await client.ajax(studentPage, repAjaxId);
      reports.push({ semester: reg.semester, regionId: reg.id, html: repHtml });
    } catch (err) {
      console.warn(`Could not fetch report ${reg.id}:`, err.message);
    }
  }

  // 4. Highlight matches if known absences provided
  const highlights = knownAbsences.length
    ? scanKnownAbsenceMatches(knownAbsences, rawCalendarEvents, absenceApexRegions, reports)
    : [];

  // Summary counts
  let calFlaggedCount = 0;
  for (const ev of rawCalendarEvents) {
    const evStr = JSON.stringify(ev).toLowerCase();
    if (evStr.includes("absen") || evStr.includes("motivat") || evStr.includes("nemotivat")) {
      calFlaggedCount += 1;
    }
  }

  let tableMatchCount = 0;
  $("tr").each((_i, tr) => {
    const trText = $(tr).text().toLowerCase();
    if (trText.includes("absen") || trText.includes("motivat") || trText.includes("nemotivat")) {
      tableMatchCount += 1;
    }
  });

  const rawDump = {
    capturedAt: new Date().toISOString(),
    studentRole: role,
    calendarAjaxId: calAjaxId,
    calendarEvents: rawCalendarEvents,
    apexRegions: absenceApexRegions,
    semesterReports: reports,
    knownAbsenceHighlights: highlights,
  };

  // Run through sanitizeCapture
  const sanitizedJsonString = sanitizeCapture(JSON.stringify(rawDump, null, 2));

  const targetPath =
    outFile ||
    path.resolve(`test/fixtures/absence-capture-${Date.now()}.json`);

  fs.mkdirSync(path.dirname(targetPath), { recursive: true });
  fs.writeFileSync(targetPath, sanitizedJsonString, "utf-8");

  // Output format required by spec:
  console.log(`Found ${calFlaggedCount} calendar events with status flags, ${tableMatchCount} table rows matching absences`);
  console.log(`Sanitized dump written to: ${targetPath}`);
}

// Execute if run directly
if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(new URL(import.meta.url).pathname)) {
  run().catch((err) => {
    console.error("Capture failed:", err);
    process.exit(1);
  });
}

export { findAbsenceApexRegions, scanKnownAbsenceMatches };

