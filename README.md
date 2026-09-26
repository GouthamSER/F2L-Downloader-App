# F2L Downloader

**Fast · Reliable · Simple**

An Android download manager built with Kotlin + Jetpack Compose. Multi-threaded direct-link downloads, a Liquid Glass UI, BitTorrent/magnet integration, and advanced network capabilities.

Version **3.3.0**

## Features

**Downloads**
- Multi-threaded segmented downloads (2–16 connections) for servers supporting HTTP Range requests, with automatic fallback to single-connection downloads.
- Real-time download speed graph and bandwidth chart with glowing gradient curves (in detail view and live sparkline cards).
- Batch URL import: paste multiple links directly, import from clipboard, or load `.txt`/`.m3u` link files.
- BitTorrent & magnet link integration (`magnet:?xt=urn:btih:...`): automatic metadata/hash extraction, tracker counting, and mirror fallback resolution.
- Advanced HTTP options: custom User-Agent presets (Chrome Android, Chrome Windows PC, Firefox, Safari, Custom), custom Referer, and arbitrary custom headers/cookies (`Cookie: session=...`).
- Pause / resume / retry, with automatic retry-on-failure (configurable attempt count).
- Real-time progress, speed, and ETA — in-app and in persistent notifications with dedicated progress bars and quick actions (Pause / Cancel).
- Foreground service architecture: downloads continue in the background and survive process restarts (state persisted to disk).
- Automatic file-name detection from server headers (`Content-Disposition`) or torrent display names.
- Direct file opening and sharing with mime-type detection.

**Interface**
- Liquid Glass design: blurred glow background, frosted translucent cards/nav/FAB (Android 12+, graceful flat fallback on older devices).
- Light and dark theme switching.
- Status tabs (All / Active / Completed / Failed), a Files tab for browsing completed downloads, and full detail screens with live bandwidth charts.
- Onboarding flow for notification permission and default download directory on first launch.
- Exit confirmation with active-downloads warning; back button navigation preserves active background tasks.

**Storage & safety**
- User-selected download directory via Storage Access Framework (SAF) — F2L only accesses the folder you select.
- Resilient storage error handling: graceful prompts on permission revocations or low disk space.
- Direct permanent deletion confirmation with disk cleanup for segment files (`.f2l.part*`).
- Direct HTTP(S) and BitTorrent/magnet links only — no social media extractors.

## Build

Open this folder in Android Studio (Ladybug or newer), let Gradle sync, then:

`Build ▸ Build APK(s)`

Or via command line: `./gradlew assembleDebug`

The generated APK is under:
`app/build/outputs/apk/debug/app-debug.apk`

A GitHub Actions workflow (`.github/workflows/build-apk.yml`) is included — push to `main`/`master` to build a debug APK automatically.

## Developed by

**Goutham Josh** — [github.com/GouthamSER](https://github.com/GouthamSER)
