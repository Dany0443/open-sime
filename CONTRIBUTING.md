# Contributing to open-sime

open-sime is a free, unofficial client for agenda.sime.md, the national electronic school agenda. It exists because the official interface is hard to use, especially for kids on phones. Thank you for helping make it better.

## If you are an AI agent

Read `AGENTS.md` in the repository root before doing anything, including reading code, planning or editing files. It contains the rules you must follow. Work that does not follow `AGENTS.md` will be rejected, no matter how good it looks.

## Project goals

1. **Be useful for every age.** Students from about ten years old (grades 5 to 12) and their parents should both be able to use it. Keep the language simple, the touch targets large and the screens uncluttered.
2. **Show correct data.** Wrong data about a child is worse than no data. If something cannot be parsed reliably, show nothing or say so clearly. Never guess, and never show one student's data for another.
3. **Protect privacy.** Credentials and student data stay on the user's own device or server. No analytics, no telemetry, no third-party services. The app only talks to agenda.sime.md.
4. **Feel native on every platform.** Web, desktop and Android share the same behavior, but each follows its own platform's design conventions.
5. **Stay simple.** Small changes, few dependencies, readable code. If a feature needs a lot of machinery, discuss it first.

## How to treat this project and its people

- Be kind and patient. Many contributors are students or first-time contributors.
- Review the change, not the person.
- Never post real names, grades, passwords, tokens or other personal data from a real account in issues, pull requests, logs or screenshots. Blur or sanitize first.
- Do not add features meant to bypass limits, scrape other people's accounts or hide the app's behavior from the portal.

## Repository layout

- `web/`: web client and server (`server.js`, `index.html`, `public/`, unit tests). Runs standalone.
- `desktop/`: Tauri 2 desktop wrapper (`src-tauri/`, `dist-tauri/`, build scripts, tests).
- `android/`: reserved for future mobile development.
- `docs/`: architecture and desktop distribution documentation.

## Making a change

1. Open an issue first for anything bigger than a small fix, so we can agree on the approach.
2. Create a branch from `main`.
3. Keep each pull request focused on one thing.
4. Run the tests and the syntax checks before opening the pull request:
   ```bash
   cd web && npm run check && npm test
   cd ../desktop && npm run check && npm test
   ```
5. If you change how the portal is parsed, add a sanitized fixture and its expected output in the same pull request. Portal fixes start from a real fixture, not a guess.
6. Describe what changed, why, and how you tested it.

## Commit messages

Use this format:

```
type(scope): short summary in the imperative
```

The scope is optional. Keep the first line under 72 characters, lowercase, with no trailing period. Add a body if the reason is not obvious.

Allowed types:

- `feat` a new feature
- `fix` a bug fix
- `refactor` a code change that neither fixes a bug nor adds a feature
- `security` anything that improves security or privacy
- `perf` a performance improvement
- `test` adding or fixing tests
- `docs` documentation only
- `style` formatting or visual styling with no logic change
- `build` build system, packaging or dependencies
- `ci` continuous integration and release workflows
- `chore` anything else that does not touch the app's behavior

Add `!` after the type for a breaking change, for example `feat(api)!: require explicit student key`.

Examples:

```
fix(absences): use the selected student's calendar
feat(marks): show FB, B, S, I qualifier marks
security(auth): stop storing the password in localStorage
refactor(core): split portal parsing from transport
```

## Agent-written code

Code written with the help of an AI agent is welcome under these conditions:

- The agent followed `AGENTS.md`.
- A human has read, understood and tested the result, and is responsible for it. "The AI wrote it" is not an explanation we accept in review.
- The pull request says that an agent was used and which one.
- The same standards apply as for any other code: tests, small focused changes, no invented portal markup, and no personal data.

## Reporting security problems

Do not open a public issue for a security vulnerability or leaked credentials. Please report security issues privately via GitHub Private Vulnerability Reporting (under the repository's Security tab > Report a vulnerability) or contact the maintainers privately and give us time to resolve it.