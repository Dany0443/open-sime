# Open SIME

Open SIME is a free, independent client for `agenda.sime.md`, the national electronic school agenda in the Republic of Moldova. It provides a clearer, faster, and privacy-first interface for viewing marks, attendance, schedules, and homework.

## Who It Is For

Open SIME is designed for:

- **Students**: Middle and high school students (grades 5 to 12) who want a fast, readable way to check daily schedules, upcoming homework, and recent marks without navigating heavy web forms.
- **Parents**: Parents who want a calm, clear overview of their children's school progress, with seamless switching and strict separation between siblings under the same parent account.

The interface adheres to Material 3 design guidelines and is built to be accessible to all age groups, supporting light and dark themes, text scaling, high-contrast modes, and Romanian, Russian, and English languages.

## How It Works Under the Hood

The national school agenda portal is an Oracle APEX application. Open SIME acts as a modern front-end for this service:

- **Direct Authentication**: Users sign in directly using their existing `agenda.sime.md` credentials. The app does not maintain a central account database or custom cloud backend.
- **Native Android Client**: The Android application is written in Kotlin and Jetpack Compose. It connects directly to the school portal from the device, handling session management, HTTP handshakes, and page parsing locally.
- **Desktop and Web Clients**: The desktop applications (Windows, macOS, Linux) are built with Tauri and run an embedded, loopback-only local engine that communicates with the portal, manages session security, and serves the user interface.
- **Student Data Isolation**: For parents with multiple children, every request, cache entry, and data snapshot is strictly keyed by student identifier to ensure records from different children never cross.
- **Privacy by Design**: All student records, grades, and credentials stay entirely on the user's device. There is no telemetry, no analytics, no third-party network requests, and no intermediate proxy server.

## Platforms

- **Android**: Native application for phones and tablets.
- **Desktop**: Installable desktop applications for Windows (x64), macOS (Universal for Intel and Apple Silicon), and Linux (x64 AppImage).
- **Web**: Lightweight client that can be run locally or self-hosted on a private network.

## Disclaimer

This is an independent open-source project. It is not affiliated with, endorsed by, or sponsored by CTICE (Centrul Tehnologii Informaționale și Comunicaționale în Educație) or the Ministry of Education and Research of the Republic of Moldova.

## License

This project is licensed under the MIT License.
