# AGENTS.md

Rules for any AI agent working in this repository. Read this whole file before reading code, planning or editing anything. `CONTRIBUTING.md` applies to you too, and where the two overlap, the stricter rule wins.

The file has two parts. Part 1 is how to behave. Part 2 is how this repository works and what is specific to it.

---

# Part 1. Behavioral guidelines

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

---

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.

---

# Part 2. How this repository works

## What this project is

open-sime is a free, unofficial client for agenda.sime.md, the national electronic school agenda (an Oracle APEX application). Students and parents log in with their existing accounts. The app reads their data from the portal and shows it in a clearer, friendlier interface. The users include children as young as seven, so correctness, privacy and simplicity matter more than features.

Goals, in priority order:

1. Show correct data. Wrong data about a child is worse than no data.
2. Protect privacy. Credentials and student data stay on the user's device or the user's own server.
3. Be usable by every age group and accessible.
4. Feel native on each platform.
5. Stay small and simple.

## Layout

Current:

```
server.js            Express server and all portal logic (login, parsing, caching, routes)
public/              Web UI: index.html, app.js, strings.js, style.css, sw.js, manifest.json, assets/
tests/               Automated tests (node:test) and the mock portal
test/fixtures/       Sanitized portal captures
scripts/             Tools, including sanitize-capture.mjs
README.md            Running and deployment notes
```

Planned (do not create these unless the task says so): a pure `core/` package with all portal logic and no I/O, a `spec/` folder with shared fixtures and expected JSON, a `server/` wrapper, a `desktop/` Electron wrapper and an `android/` Kotlin app. The web app with the server is the main product and the reference implementation. Android and Electron come later and must reproduce its behavior.

Useful commands:

```
npm test            run the whole test suite
npm run check       syntax check of server and client files
SIME_DEBUG=1        enables /api/debug/parser-health (never returns personal values)
```

## Rules that must never be broken

### Student data isolation

A parent account can have several children. The APEX session has one global "current student", so it is easy to serve one child's data for another.

- Every student-scoped request names its student explicitly. Never fall back to "the first student".
- Every cache, in-flight request map and stored snapshot, on the server and in the client, is keyed with the student.
- Calendar and report AJAX calls take the student id and checksum from that student's own page, never from another student's cached page.
- Before returning student-scoped data, verify that the parsed page belongs to the requested student. On mismatch, fail with `STUDENT_MISMATCH`, never return the data.
- A late response for one student must never overwrite another student's state.
- Class, school and class master (diriginte) belong to the student, not to the account.

Any change touching switching, caching or student-scoped routes needs a test with two students whose data differs, and with delayed responses.

### Portal parsing

- Never invent portal markup, selectors, field names or column headers. If a selector is a guess, mark it with a `UNVERIFIED` comment and list it in your report.
- Portal fixes start from a real sanitized fixture plus the expected output, then the code. Never the other way round.
- If the portal data is missing or ambiguous, show less (or say so) instead of guessing. Never compute or display numbers the portal did not provide unless they are clearly labeled as an estimate. Do not count qualifier marks in numeric averages.
- Marks can be numeric or qualifiers (FB, B, S, I). Handle them per mark. Theses ("teză") are marks with their own flag and appearance.
- Keep request volume low. A change that adds upstream calls needs a stated reason and a cap.

### Privacy and security

- Never store, log or transmit passwords, tokens, cookies, checksums, student names or any personal data, except as the existing design allows (credentials only to agenda.sime.md; the optional remember-me cookie is encrypted).
- No analytics, telemetry or third-party requests. The only host the server talks to is agenda.sime.md.
- Never put real personal data in code, tests, fixtures, commits, logs or reports. Sanitize captures with `scripts/sanitize-capture.mjs`.
- Do not weaken the Content Security Policy. No inline scripts or styles, no CDNs, no external fonts. Self-host assets.
- Do not add features meant to evade portal limits or hide the app's behavior.

### Dependencies

No new dependency without a one-sentence justification and the maintainers' agreement. Prefer the standard library. The test runner is the built-in `node:test`.

## UI conventions

- Material 3 Expressive on web and desktop (and later Android). Use design tokens (CSS custom properties) for color, shape, type and spacing. Components never hardcode colors.
- Support light, dark and high-contrast themes, text scaling, reduced motion and the simple mode for young users. Touch targets are at least 48 by 48 px. Contrast is at least 4.5:1 for text and 3:1 for controls.
- Use the window size classes: compact below 600px (bottom navigation), medium 600 to 839px (rail), expanded 840px and up (drawer).
- All user-facing text lives in `public/strings.js`. Romanian is the default and the source of truth. Provide ru and en for new keys, with fallback to ro. Files are UTF-8. Never type a placeholder `?` in place of a diacritic.
- Color is never the only signal. Every colored element also has text or a shape.
- Tone toward children: friendly and honest, never alarming.

## How you work in this repo

1. **Orient.** Read `README.md`, `CONTRIBUTING.md` and the code you will touch. Run `git status` and `npm test` to learn the starting state. Do not assume a clean tree: another agent or a person may have left work in progress. Never discard uncommitted changes you did not make.
2. **Plan.** State assumptions, ambiguities and a short plan with a verification step for each item (see Part 1). Ask the maintainers about anything that depends on portal behavior you cannot see.
3. **Test first.** Write or confirm a failing test, then make it pass, then run the whole suite.
4. **Small steps.** Prefer many small edits to one large one. Run `npm run check` after each edit. Do not rewrite large blocks or whole files unless the task requires it.
5. **Commit often**, one logical change per commit, using `type(scope): summary` with the types `feat`, `fix`, `refactor`, `security`, `perf`, `test`, `docs`, `style`, `build`, `ci`, `chore`.
6. **Leave a trail.** On long tasks keep a `PROGRESS.md` (gitignored) listing what is done, what is in progress, what is next and the command that proves each step, so work can be resumed after an interruption.
7. **Finish cleanly.** The tree must pass `npm test` and `npm run check` at the end of every step you hand over. Never leave a file half-edited or syntactically broken.

## When to stop and ask

Stop and ask a human before proceeding if:

- the task needs real portal responses you do not have (login, calendar, marks, theses, qualifiers, absences, teachers);
- two reasonable readings of the task lead to different behavior;
- the change would add a dependency, change the public API shape, touch authentication or credential handling, or change what is sent to the portal;
- a test fails for a reason you do not understand, or you are tempted to change a test to make it pass.

Do not delete or weaken a test to get a green run. If a test is wrong, explain why and fix it deliberately.

## Definition of done

A change is done when all of these hold:

- Every changed line traces to the request.
- New behavior has tests, and the whole suite passes.
- No personal data, secrets or invented portal markup were added.
- Strings are in `strings.js`, and the UI works in light, dark and high-contrast themes with the keyboard only.
- The commit messages follow the format.

## Report format

When you finish or get blocked, report:

1. What changed, per task item: status, files and functions touched, how you verified it.
2. Assumptions made and open questions.
3. Remaining `UNVERIFIED` selectors.
4. Problems you noticed outside the task. Recommend them, do not fix them silently.
5. Upstream request counts before and after, if the change affects portal traffic.
6. If you used or created `PROGRESS.md`, its final contents.