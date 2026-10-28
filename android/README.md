# Open SIME Android (`android`)

Native Kotlin + Jetpack Compose (Material 3 Expressive) client for Open SIME (`md.sime.opensime`).

For the web client and server, see [`../web/`](../web/). For the desktop application, see [`../desktop/`](../desktop/). For detailed Android architecture and API audit notes, see [`../docs/android-architecture.md`](../docs/android-architecture.md).

## Current Milestone & Architecture Direction

- **What this app is:** A standalone, native Kotlin + Jetpack Compose application (`md.sime.opensime`), not a WebView wrapper.
- **Current status (UI Foundation with Sample Data):** Reliable launch, native Kotlin + Jetpack Compose port of the Open SIME web UI (`Acasă`, `Reușita`, `Orar`, `Absențe`, `Teme`, `Profil și profesori`, `Setări`), adaptive navigation (`ShortNavigationBar` on compact screens `<600dp`, vertically scrollable side rail/drawer on `>=600dp`), header student switcher strip reachable from any tab, centralized theming (`System` / `Light` / `Dark`, text scaling `S/M/L/XL`, High Contrast, Simple Mode, Reduced Motion, and `ro` / `ru` / `en` localization persisted across rotation and cold restarts), and student-isolated state coordination backed by **clearly labeled synthetic sample data** (`DataSourceMode.SAMPLE_PREVIEW`).
- **Planned next phase (Direct SIME Portal Integration):** The Android client will communicate **directly with `https://agenda.sime.md`** on the user's device, without requiring an Open SIME backend server. Production networking to `agenda.sime.md` is intentionally not implemented in this milestone.
- **Privacy, Credentials & Student Isolation:**
  - Every student-scoped request, cache entry, and UI state transition is keyed by an explicit `StudentId`, verified via `StudentAgendaSnapshot.verifyStudentOwnership(expectedStudentId)` (failing with `StudentMismatchException` on mismatch), and guarded by monotonically increasing request tickets so delayed responses never overwrite the active student.
  - Cleartext network traffic is disabled (`cleartextTrafficPermitted="false"`), cloud backups and device-transfer extraction are disabled (`allowBackup="false"`, `data_extraction_rules.xml`, `backup_rules.xml`), and no analytics, telemetry, or third-party endpoints are permitted.
- **Shared Brand Icon & Versioning:**
  - Launcher icons (`mipmap-mdpi` through `mipmap-xxxhdpi` and adaptive `drawable/ic_launcher_*.xml`) are generated reproducibly from the canonical [`../web/public/assets/favicon.svg`](../web/public/assets/favicon.svg) via `cd ../desktop && npm run icons:sync` ([`../desktop/scripts/sync-brand-icons.mjs`](../desktop/scripts/sync-brand-icons.mjs)).
  - `app/build.gradle.kts` tracks `versionName` (aligned with the repository release version, currently `"1.0.1"`) and an integer `versionCode` (currently `2`). Web and desktop versions are validated by `desktop/scripts/check-versions.mjs`.

## Toolchain & Dependency Baseline

| Component | Version | Notes |
| :--- | :--- | :--- |
| **JDK** | `21` | `JavaVersion.VERSION_21` (`JAVA_HOME` must point to JDK 21) |
| **Gradle Wrapper** | `9.6.0` | Configured in `gradle/wrapper/gradle-wrapper.properties` |
| **Android Gradle Plugin (AGP)** | `9.4.0` | Uses AGP 9 built-in Kotlin and new DSL (`org.jetbrains.kotlin.android` is not applied) |
| **Kotlin / Compose Compiler Plugin** | `2.2.10` | Aligned with AGP `9.4.0` built-in Kotlin compiler |
| **`compileSdk` / `targetSdk` / `minSdk`** | `37` / `37` / `26` | Verified against Android SDK Platform 37 |
| **Jetpack Compose BOM** | `2026.09.00` | Aligns Compose UI, Foundation, Runtime, Animation at `1.12.1` |
| **Material 3 Expressive** | `1.5.0-beta02` | Explicit pin for `MaterialExpressiveTheme`, `MotionScheme`, `ShortNavigationBar`, `WideNavigationRail`, `SegmentedListItem`, emphasized typography, and shape tokens |
| **AndroidX Core KTX** | `1.19.1` | Stable |
| **AndroidX Activity Compose** | `1.13.0` | Stable (`enableEdgeToEdge()`) |
| **AndroidX Lifecycle** | `2.11.0` | Stable (`lifecycle-runtime-ktx`, `lifecycle-runtime-compose`, `lifecycle-viewmodel-compose`) |
| **Testing** | `JUnit 4.13.2`, `AndroidX JUnit 1.3.0`, `Espresso 3.7.0` | Unit and instrumentation testing baseline |

## Package Structure & Where New Features Belong

Single `:app` module under `app/src/main/java/md/sime/opensime/`:

```text
md.sime.opensime/
├── MainActivity.kt                  # Edge-to-edge ComponentActivity host
├── OpenSimeApp.kt                   # Root Composable & OpenSimeSessionCoordinator (student isolation)
├── core/
│   ├── model/
│   │   └── AgendaModels.kt          # StudentId, StudentProfile, StudentAgendaSnapshot, MarkValue, QualifierCode
│   ├── data/
│   │   ├── AgendaRepository.kt      # Student-scoped data boundary interface
│   │   └── SampleAgendaRepository.kt# Clearly labeled synthetic sample data for 2 distinct students
│   ├── i18n/
│   │   └── AppStrings.kt            # Trilingual catalog (ro source of truth, ru, en) + Simple Mode labels
│   └── designsystem/
│       ├── Theme.kt                 # MaterialExpressiveTheme, text scale S/M/L/XL, High Contrast, SharedPreferences + Saver
│       ├── OpenSimeComponents.kt    # ScoreBadge, BandChip, StatusChip, CookieAvatar, EmptyStateCard, ErrorStateCard
│       └── OpenSimeIcons.kt         # Self-hosted SVG PathParser ImageVectors (no material-icons-extended)
├── navigation/
│   ├── TopLevelDestination.kt       # 5 primary tabs (Acasă, Reușita, Orar, Absențe, Teme) + window width classes
│   └── OpenSimeAdaptiveShell.kt     # Header + student strip + Compact ShortNavigationBar vs Scrollable Rail/Drawer
└── feature/
    ├── home/HomeScreen.kt           # Acasă (live lesson hero, weekday mini-schedule, GPA/absences/last-grade cards, recent marks, homework preview)
    ├── marks/MarksScreen.kt         # Reușita (adaptive cards <840dp / table >=840dp, semester mark groups, qualifiers FB/B/S/I, thesis badges)
    ├── timetable/TimetableScreen.kt # Orar (weekday selector with date numbers, lesson cards, break pills, localized empty state)
    ├── absences/AbsencesScreen.kt   # Absențe (semester toggle, 3-stat row, collapsible by-subject breakdown, chronological list)
    ├── homework/HomeworkScreen.kt   # Teme (assignments grouped by due date with relative & human-readable dates)
    ├── profile/ProfileSheet.kt      # Profil și profesori full view (hero card, student info grid, teacher cards with CookieAvatar)
    └── settings/SettingsSheet.kt    # Setări dialog (Language ro/ru/en, Text Size S/M/L/XL, Theme System/Light/Dark, Accessibility options)
```

### Adding a New Feature
1. **Domain types:** Add pure Kotlin models and invariants to `core/model/` (always keyed by `StudentId`).
2. **Repository contract:** Extend `core/data/AgendaRepository.kt` and update `SampleAgendaRepository.kt` with sanitized sample fixtures before adding upstream portal calls.
3. **Strings:** Add Romanian (`ro`, source of truth), Russian (`ru`), and English (`en`) strings in `core/i18n/AppStrings.kt`.
4. **UI screen:** Create a feature package under `feature/<feature_name>/` consuming immutable snapshot state and M3 Expressive tokens from `MaterialTheme`.

## Build, Test & Install Commands

Run commands from the `android/` directory (with `JAVA_HOME` set to JDK 21 if your default system `java` is newer):

```bash
cd android

# Assemble & install debug APK (works without release credentials)
./gradlew assembleDebug
./gradlew installDebug

# Run unit tests and Android Lint
./gradlew testDebugUnitTest
./gradlew lintDebug

# Assemble & install release APK (requires private signing credentials; runs R8)
./gradlew assembleRelease
./gradlew installRelease
```

## Release Signing & R8 Configuration

- **Debug builds** (`assembleDebug`, `installDebug`, `testDebugUnitTest`, `lintDebug`) work out of the box without any signing configuration.
- **Release builds** (`assembleRelease`, `installRelease`, `bundleRelease`) enable R8 code shrinking (`isMinifyEnabled = true`), resource shrinking (`isShrinkResources = true`), and `proguard-android-optimize.txt` + `app/proguard-rules.pro`.
- Release tasks run `validateReleaseSigning` first and **fail clearly** if signing credentials are missing or incomplete.
- Provide release credentials via environment variables (recommended for CI) or an untracked `android/keystore.properties` (or `android/local.properties`) file:

### Option A: Environment Variables
```bash
export SIME_ANDROID_KEYSTORE_PATH="/absolute/path/to/release.jks"
export SIME_ANDROID_KEYSTORE_PASSWORD="..."
export SIME_ANDROID_KEY_ALIAS="..."
export SIME_ANDROID_KEY_PASSWORD="..."
```

### Option B: Untracked `android/keystore.properties`
```properties
sime.release.storeFile=/absolute/path/to/release.jks
sime.release.storePassword=...
sime.release.keyAlias=...
sime.release.keyPassword=...
```

Never commit keystore files, `keystore.properties`, or `local.properties`.
