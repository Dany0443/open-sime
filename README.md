# Open SIME

Open SIME este un client gratuit și neoficial pentru agenda.sime.md, agenda electronică școlară națională din Republica Moldova. Aplicația oferă elevilor din clasele 5-12 și părinților o interfață mai prietenoasă, rapidă și orientată spre confidențialitate.

---

Open SIME is a free, unofficial client for agenda.sime.md, the national electronic school agenda of the Republic of Moldova. It provides middle and high school students (grades 5 to 12) and their parents with a clearer, faster, and privacy-respecting interface for tracking marks, attendance, timetable, and homework.

## Disclaimer

This is an independent, unofficial open-source project. It is not affiliated with, endorsed by, or sponsored by CTICE (Centrul Tehnologii Informaționale și Comunicaționale în Educație) or the Ministry of Education and Research of the Republic of Moldova.

## Repository Layout

| Directory | Description |
| --- | --- |
| `web/` | Web client and Express server (`server.js`, `index.html`, `public/`, unit tests) |
| `desktop/` | Tauri 2 desktop application packaging (`src-tauri/`, `dist-tauri/`, scripts, tests) |
| `android/` | Reserved for future Android application |
| `docs/` | Project architecture and desktop build documentation |

## Running Locally

To run the web application locally with Node.js 20+:

```bash
cd web
npm ci
npm start
```

Open `http://localhost:3000` in your web browser.

## Releases

Official desktop releases for Windows, macOS, and Linux are available on [GitHub Releases](../../releases).

If running an unsigned build:
- **Windows**: Microsoft Defender SmartScreen may display "Windows protected your PC". Click **More info**, then **Run anyway**.
- **macOS**: Gatekeeper may block opening unidentified apps. In Finder, right-click (or Control-click) Open SIME in Applications and choose **Open**.

Verify download integrity with `sha256sum -c checksums.txt`.

## Contributing & Agent Guidelines

- Contributors: see [`CONTRIBUTING.md`](CONTRIBUTING.md) for workflow, style rules, and commit standards.
- AI Agents: see [`AGENTS.md`](AGENTS.md) before inspecting or editing any code.

## License

This project is licensed under the MIT License.
