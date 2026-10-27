# Open SIME Android Architecture & API/Session Audit

## 1. Architecture Overview

Open SIME Android (`android/`) is a single-module (`:app`) native Kotlin + Jetpack Compose application built with Material 3 Expressive (`androidx.compose.material3:material3:1.5.0-beta02`).

### Core Design Principles
1. **Student Data Isolation:**
   - Every student is identified by a strongly-typed `StudentId`.
   - `OpenSimeSessionCoordinator` keys all cached snapshots by `StudentId`, tracks a monotonically increasing request ticket so delayed responses for an earlier student never overwrite the active student's UI state, and calls `StudentAgendaSnapshot.verifyStudentOwnership(expectedStudentId)` before exposing any data (throwing `StudentMismatchException` on mismatch).
   - `StudentProfile` owns `className`, `schoolName`, and `classMasterName` (diriginte)—never the parent account.
2. **Honesty in Data Presentation:**
   - Sample data is tagged with `DataSourceMode.SAMPLE_PREVIEW` and rendered with a prominent top banner (`Previzualizare cu date demonstrative` / `Date demonstrative`).
   - Averages computed client-side from numeric marks carry `AverageMetric(isEstimated = true)` and are always badged as `Estimată`.
   - Qualifier marks (`FB`, `B`, `S`, `I`) are modeled via `MarkValue.Qualifier` and are **never** included in numeric averages.
   - Thesis evaluations carry `isThesis = true` and a dedicated `Teză` badge.
3. **Adaptive Material 3 Expressive UI:**
   - **Compact (`< 600dp`):** Bottom `ShortNavigationBar` with the five primary destinations (`Acasă`, `Reușita`, `Orar`, `Absențe`, `Teme`). `Profil` and `Setări` are accessible via 48dp top app bar action buttons.
   - **Medium (`600–839dp`):** Collapsed `WideNavigationRail` with the five primary destinations plus `Profil` and `Setări` rail actions.
   - **Expanded (`>= 840dp`):** Expanded `WideNavigationRail` (drawer width) showing full labels and header metadata.
4. **Centralized Expressive Theming & Accessibility:**
   - `OpenSimeTheme` wraps `MaterialExpressiveTheme` with `MotionScheme.expressive()` (or `MotionScheme.standard()` when `reduceMotion` is enabled), `OpenSimeShapes` (`largeIncreased`, `extraLargeIncreased`, `extraExtraLarge`), and `OpenSimeTypography` (including emphasized styles).
   - Supports `ThemeMode.SYSTEM`, `ThemeMode.LIGHT`, and `ThemeMode.DARK`, High Contrast mode, Simple Mode for younger students, trilingual UI (`ro` default and source of truth, `ru`, `en`), and a `NamedThemePalette` registry (`SIME_INDIGO`, `FOREST_CALM`, `WARM_AMBER`).

---

## 2. Audit of Existing HTTP API & Authentication/Session Behavior

Before implementing a production data layer on Android, we audited `web/server.js` and `web/public/app.js` to determine what can be reused and what requires further investigation.

### 2.1 How `web/server.js` Works Today

`web/server.js` is an Express backend that acts as a stateful proxy and parser between the browser/Tauri UI and `https://agenda.sime.md` (an Oracle APEX application, Flow ID `104`, Step ID `1`).

#### Upstream Oracle APEX Authentication & Session Mechanics (`agenda.sime.md`)
1. **Three-step atomic handshake (`executeHandshake` / `SimeAuth.login`):**
   - `GET https://agenda.sime.md/ords/r/agenda/main/public` — scrapes the `login_desktop` dialog URL for `p101_user_type=1` (parent) or `p101_user_type=2` (student).
   - `GET <dialogUrl>` — parses hidden APEX form tokens (`pFlowId`, `pFlowStepId`, `pInstance`, `pPageSubmissionId`, `pSalt`, `pPageItemsProtected`, `pReloadOnSubmit`) and per-item `data-for` checksums (`P101_USER_TYPE`, `P101_USER_TYPE_NAME`).
   - `POST https://agenda.sime.md/ords/wwv_flow.accept` (with `X-Requested-With: XMLHttpRequest`) — submits `p_request=LOGIN` and `p_json` containing `P101_USERNAME`, `P101_PASSWORD`, `salt`, and item checksums.
   - Parses JSON response `{ "redirectURL": "javascript:apex.navigation.dialog.close(true,function(pDialog){ ... atob(\"...\") })" }`, base64-decodes the target dashboard URL (`f?p=104:1:<sessionId>...`), extracts the APEX `sessionId`, and `GET`s the landing dashboard HTML while maintaining an in-memory HTTP cookie jar.
2. **Global Active Student Hazard in Oracle APEX:**
   - An Oracle APEX session on `agenda.sime.md` holds **one global current student** (`P1_ELEVE_ID`) on the server side.
   - Switching students requires navigating to the student's checksummed link (`f?p=AGENDA:1:<sessionId>::NO::P1_ELEVE_ID:<studentId>&cs=<checksum>`) scraped from `#t_TreeNav` on the dashboard, then verifying that the resulting dashboard HTML's `P1_ELEVE_ID` / `P1_ELEVE` matches the requested student (`STUDENT_MISMATCH` check).
   - Subsequent AJAX calls (`POST /ords/wwv_flow.ajax?p_context=main/main-page/<sessionId>` with `p_request=PLUGIN=<ajaxId>` for FullCalendar lesson ranges and semester report regions) require `P1_ELEVE_ID`, its item checksum (`data-for="P1_ELEVE_ID"`), `pSalt`, and `pPageItemsProtected` from **that specific student's own dashboard page**, and require the APEX session to currently be positioned on that student (`assertCurrent: true`).

#### Local REST API Exposed by `web/server.js` to `web/public/app.js`
| Endpoint | Method | Auth | Request Parameters | Response / Behavior |
| :--- | :--- | :--- | :--- | :--- |
| `/api/health` | `GET` | None | — | `{ ok: true, service: "open-sime-server", instanceId }` |
| `/api/auth/config` | `GET` | None | — | `{ rememberMeAvailable: true }` |
| `/api/auth/login` | `POST` | None | JSON `{ username, password, role, remember }` | Runs APEX handshake, creates in-memory session token (`Bearer <token>`), optionally sets `HttpOnly` AES-256-GCM encrypted `sime_remember` cookie, returns `{ token, role, student, students, data }` |
| `/api/auth/resume` | `POST` | Cookie `sime_remember` | — | Rotates `sime_remember` cookie, re-runs APEX login, returns `{ token, role, student, students, data }` |
| `/api/auth/forget-device` | `POST` | Cookie `sime_remember` | — | Deletes stored remember token hash and clears cookie |
| `/api/auth/logout` | `POST` | `Bearer <token>` | — | Clears in-memory session, student caches, and `sime_remember` cookie (`204 No Content`) |
| `/api/student/switch` | `POST` | `Bearer <token>` | JSON `{ studentId }` | Serializes on session chain, switches APEX student via checksummed URL, verifies ownership, returns `{ role, studentId, student, students, data, cache }` |
| `/api/overview` | `GET` | `Bearer <token>` | `?student=<id>&force=1` | Returns full parsed dashboard snapshot (`student`, `students`, `data`, `cache`) |
| `/api/profile` | `GET` | `Bearer <token>` | `?student=<id>` | Student metadata (`fullName`, `className`, `schoolName`, `classMaster`, `currentSemester`) |
| `/api/teachers` | `GET` | `Bearer <token>` | `?student=<id>` | Derived teacher list from 3-week calendar window + class master + catalog |
| `/api/grades` | `GET` | `Bearer <token>` | `?student=<id>` | Recent evaluations parsed from `P1_LAST_3_EVAL_LIST` |
| `/api/catalog` & `/api/reusita` | `GET` | `Bearer <token>` | `?student=<id>` | Full semester report tables & subject averages |
| `/api/homework` | `GET` | `Bearer <token>` | `?student=<id>` | Homework list parsed from `P1_HOMEWORK` |
| `/api/absences` | `GET` | `Bearer <token>` | `?student=<id>` | Summary + per-lesson absence records (`buildSemesterAbsences`) |
| `/api/schedule` | `GET` | `Bearer <token>` | `?student=<id>&date=YYYY-MM-DD` | Monday–Sunday lesson map (`weekStart`, `days`) via FullCalendar AJAX plugin |

### 2.2 What Is Reusable for Open SIME Android

1. **Domain Models, Normalization & Invariants (100% Reusable):**
   - Student isolation rules (`StudentId` keying, alias resolution between `#t_TreeNav` link IDs and `P1_ELEVE_ID`, `STUDENT_MISMATCH` verification, serialized student switching).
   - Mark & qualifier normalization (`QUALIFIERS`: `FB`, `B`, `S`, `I`, thesis flag extraction, excluding qualifiers from numeric averages, `SUBJECT_SCALE_REGISTRY`).
   - Timezone handling (`Europe/Chisinau` date/time boundaries and semester inference).
   - Error taxonomy (`PORTAL_THROTTLED`, `PORTAL_MAINTENANCE`, `PORTAL_LOGIN_REJECTED`, `APP_SESSION_INVALID`, `STUDENT_MISMATCH`, `STUDENT_NOT_FOUND`, `PORTAL_UNEXPECTED_PAGE`).
   - Rate-limiting & resilience parameters (`RateGate`, per-account and global `CircuitBreaker`, `PAGE_TTL_MS = 60_000`, `CALENDAR_CHUNK_DAYS = 180`).
2. **Sanitized Test Fixtures (`web/tests/fixtures/`):**
   - Existing sanitized HTML and AJAX fixtures used by `web/tests/` can be shared directly with Android unit tests to verify parity between Kotlin and Node parsers.

### 2.3 Architectural Decision for Production Networking

1. **Direct On-Device APEX Client (Chosen Architecture):**
   - The Android client will communicate **directly with `https://agenda.sime.md` from the user's device**, without requiring an Open SIME backend server (`web/server.js`) in the middle.
   - This preserves Open SIME's core privacy guarantee—credentials and student data never touch a third-party proxy—while allowing students and parents to install and use the Android app standalone without self-hosting a Node.js server.
   - Implementing direct portal access requires porting the 3-step Oracle APEX authentication handshake, in-memory cookie jar, serialized per-student switching state machine, HTML/AJAX parsers, rate limiting, and circuit breaker into Kotlin inside `android/`.
   - **Current Status:** Production networking to `agenda.sime.md` is not yet implemented. The Android app currently runs on `SampleAgendaRepository` (`DataSourceMode.SAMPLE_PREVIEW`) with clearly labeled synthetic sample data.
2. **On-Device Credential Storage & Privacy Rules:**
   - Credentials are sent only to `https://agenda.sime.md` over HTTPS (`cleartextTrafficPermitted="false"`).
   - Never log or persist raw passwords, session cookies, tokens, checksums, or student personal data in plaintext.
   - When optional session renewal or "Remember me" is added in the future, credentials or session material must be encrypted using the Android Keystore (`Cipher` / hardware-backed `SecretKey`) with explicit user opt-in, and excluded from cloud/device backups (`allowBackup="false"`, `data_extraction_rules.xml`, `backup_rules.xml`).
3. **Remaining `UNVERIFIED` Portal Selectors in `web/server.js`:**
   - Semester report region fallback selectors (`#sem1`, `#SEMESTER_REG_1`, `#R_SEM1`, `#sem2`, `#SEMESTER_REG_2`, `#R_SEM2`) and several inline absence table fallbacks in `web/server.js` are marked `/* UNVERIFIED */` and must only be ported from verified, sanitized portal captures.

