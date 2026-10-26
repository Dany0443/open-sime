#!/usr/bin/env node
// Sanitizes captures (HTML or JSON) from agenda.sime.md for safe fixture storage.
// Replaces personal names, IDs, schools, birthdates, addresses, checksums, salts,
// and session IDs with stable placeholders while keeping tag and attribute structure intact.

import fs from "node:fs";
import path from "node:path";
import * as cheerio from "cheerio";

export function sanitizeHtml(rawHtml) {
  const $ = cheerio.load(rawHtml, { decodeEntities: false }, false);

  const nameMap = new Map();
  const idMap = new Map();
  let nameCount = 0;
  let idCount = 0;
  let teacherCount = 0;
  const teacherMap = new Map();

  const getPlaceholderName = (real) => {
    if (!real || typeof real !== "string") return real;
    const trimmed = real.trim();
    if (!trimmed) return real;
    if (!nameMap.has(trimmed)) {
      nameCount += 1;
      nameMap.set(trimmed, `Elev ${nameCount}`);
    }
    return nameMap.get(trimmed);
  };

  const getPlaceholderTeacher = (real) => {
    if (!real || typeof real !== "string") return real;
    const trimmed = real.trim();
    if (!trimmed) return real;
    if (!teacherMap.has(trimmed)) {
      teacherCount += 1;
      teacherMap.set(trimmed, `Profesor ${teacherCount}`);
    }
    return teacherMap.get(trimmed);
  };

  const getPlaceholderId = (real) => {
    if (!real || typeof real !== "string") return real;
    const trimmed = real.trim();
    if (!trimmed) return real;
    if (!idMap.has(trimmed)) {
      idCount += 1;
      idMap.set(trimmed, `ID_${idCount * 100 + 1}`);
    }
    return idMap.get(trimmed);
  };

  // Sanitize student profile items
  const eleveInput = $("input#P1_ELEVE, input[name='P1_ELEVE']");
  if (eleveInput.length) {
    eleveInput.attr("value", getPlaceholderName(eleveInput.attr("value")));
  }
  const eleveIdInput = $("input#P1_ELEVE_ID, input[name='P1_ELEVE_ID']");
  if (eleveIdInput.length) {
    eleveIdInput.attr("value", getPlaceholderId(eleveIdInput.attr("value")));
  }
  $("input#P1_INSTITUTION_NAME, input[name='P1_INSTITUTION_NAME']").attr("value", "Liceul Teoretic Sanitize");
  $("input#P1_CLASS_NAME, input[name='P1_CLASS_NAME']").attr("value", "10A");
  const diriginteInput = $("input#P1_DIRIGINTE, input[name='P1_DIRIGINTE']");
  if (diriginteInput.length) {
    diriginteInput.attr("value", getPlaceholderTeacher(diriginteInput.attr("value")));
  }
  $("input#P1_BIRTH_DATE, input[name='P1_BIRTH_DATE']").attr("value", "01.01.2010");
  $("input#P1_ADDRESS, input[name='P1_ADDRESS']").attr("value", "Str. Exemplu 1, Chisinau");
  $("input#P1_ELEVE_UK, input[name='P1_ELEVE_UK'], input#P1_UK").attr("value", "UK_SANITIZED_1");

  // Sanitize APEX tokens
  $("input#pInstance").attr("value", "999999");
  $("input#pPageSubmissionId").attr("value", "111111");
  $("input#pSalt").attr("value", "222222");
  $("input#pPageItemsProtected").attr("value", "PROT_CHECKSUM_SANITIZED");

  $("input[data-for]").each((_i, el) => {
    $(el).attr("value", "ITEM_CHECKSUM_SANITIZED");
  });

  // Sanitize select options for students
  $("select#P1_ELEVE_ID, select[name='P1_ELEVE_ID']").find("option").each((_i, opt) => {
    const rawVal = $(opt).attr("value");
    if (rawVal) {
      $(opt).attr("value", getPlaceholderId(rawVal));
    }
    const rawText = $(opt).text().trim();
    if (rawText) {
      $(opt).text(getPlaceholderName(rawText));
    }
  });

  // Sanitize navigation tree links
  $("#t_TreeNav a[href]").each((_i, a) => {
    let href = $(a).attr("href");
    if (href) {
      // replace session id
      href = href.replace(/([?&;:]session=)\d+/gi, "$1999999");
      href = href.replace(/(AGENDA:\d+:)\d+/gi, "$1999999");
      // replace student id
      href = href.replace(/(?:user_eleve_id=|p1_eleve_id=|P1_ELEVE_ID:)([^&#:'"]+)/gi, (m, id) => {
        const ph = getPlaceholderId(decodeURIComponent(id));
        return m.slice(0, m.length - id.length) + ph;
      });
      // replace checksum in href
      href = href.replace(/([?&;]cs=)[A-Za-z0-9_-]+/gi, "$1CHECKSUM_SANITIZED");
      $(a).attr("href", href);
    }
    const txt = $(a).text().trim();
    if (txt) {
      $(a).text(getPlaceholderName(txt));
    }
  });

  // Sanitize report table teacher headers
  $("td").each((_i, td) => {
    const headers = ($(td).attr("headers") || "").toUpperCase();
    if (headers.includes("PROFESOR") || headers.includes("CADRU") || headers.includes("TEACHER")) {
      const orig = $(td).text().trim();
      if (orig) {
        $(td).text(getPlaceholderTeacher(orig));
      }
    }
  });

  // Sanitize calendar script tag
  $("script#P1_CALENDAR_EVENTS, script[data-calendar-events]").each((_i, script) => {
    const text = $(script).text().trim();
    if (text) {
      try {
        const events = JSON.parse(text);
        if (Array.isArray(events)) {
          const sanitizedEvents = sanitizeCalendarEvents(events, getPlaceholderTeacher);
          $(script).text(JSON.stringify(sanitizedEvents));
        }
      } catch {
        // keep text if not JSON
      }
    }
  });

  return $.html();
}

export function sanitizeCalendarEvents(events, getPlaceholderTeacher = null) {
  if (!Array.isArray(events)) return events;
  let tCount = 0;
  const tMap = new Map();
  const getTeacher = getPlaceholderTeacher || ((real) => {
    if (!real) return real;
    if (!tMap.has(real)) {
      tCount += 1;
      tMap.set(real, `Profesor ${tCount}`);
    }
    return tMap.get(real);
  });

  return events.map((ev) => {
    if (!ev || typeof ev !== "object") return ev;
    const cloned = { ...ev };
    if (typeof cloned.title === "string" && cloned.title.includes("<")) {
      const $title = cheerio.load(cloned.title, { decodeEntities: false }, false);
      $title(".lesson-teacher").each((_i, el) => {
        const orig = $title(el).text().trim();
        if (orig) $title(el).text(getTeacher(orig));
      });
      $title(".lesson-room").text("101");
      cloned.title = $title.html();
    }
    return cloned;
  });
}

export function sanitizeCapture(input) {
  let isJson = false;
  let parsedJson = null;
  const trimmed = input.trim();
  if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
    try {
      parsedJson = JSON.parse(trimmed);
      isJson = true;
    } catch {
      isJson = false;
    }
  }

  if (isJson) {
    if (Array.isArray(parsedJson)) {
      return JSON.stringify(sanitizeCalendarEvents(parsedJson), null, 2);
    }
    // generic JSON
    const sanitizedStr = JSON.stringify(parsedJson, null, 2)
      .replace(/("session(?:Id)?":\s*")\d+(")/g, '$1999999$2')
      .replace(/(session=)\d+/g, '$1999999');
    return sanitizedStr;
  }

  return sanitizeHtml(input);
}

// CLI execution
if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(new URL(import.meta.url).pathname)) {
  const inputFile = process.argv[2];
  if (!inputFile) {
    console.error("Usage: node scripts/sanitize-capture.mjs <input-file> [output-file]");
    process.exit(1);
  }

  const raw = fs.readFileSync(inputFile, "utf-8");
  const sanitized = sanitizeCapture(raw);

  const defaultOutDir = path.resolve("test/fixtures");
  fs.mkdirSync(defaultOutDir, { recursive: true });
  const outputFile = process.argv[3] || path.join(defaultOutDir, path.basename(inputFile));

  fs.writeFileSync(outputFile, sanitized, "utf-8");
  console.log(`Sanitized ${inputFile} -> ${outputFile}`);
}
