# F2L Downloader

**Fast · Reliable · Simple**

An Android download manager built with Kotlin + Jetpack Compose. Multi-threaded direct-link downloads, a Liquid Glass UI, and no social-media video extraction.

Version **3.1.0 · Minor Upgrade With Torrents**

## Features

**Downloads**
- High-performance BitTorrent and magnet link downloading with live peer discovery, DHT, and public tracker acceleration
- Multi-threaded segmented downloads (2–16 connections) for servers that support HTTP Range requests, automatic fallback to a single connection otherwise
- Pause / resume / retry, with automatic retry-on-failure (configurable attempt count)
- Real-time progress, speed, and ETA — in-app and in the persistent notification (with a real progress bar)
- Runs in a foreground service — downloads continue when the app is backgrounded, and survive even if the app process is killed (state is written straight to disk, not just kept in memory)
- Automatic file-name detection from server headers (`Content-Disposition`), independent of the URL
- Tap any completed file to open it directly, or share it to another app

**Interface**
- Liquid Glass design: blurred glow background, frosted translucent cards/nav/FAB (Android 12+, graceful flat fallback on older devices)
- Light and dark theme
- Status tabs (All / Active / Completed / Failed), a Files tab for browsing completed downloads, and a full detail screen per download
- Onboarding flow for notification permission + default download folder on first launch
- Exit confirmation with an active-downloads warning; back button navigates properly instead of exiting mid-flow

**Storage & safety**
- User-selected download directory via Storage Access Framework (SAF) — F2L only ever gets access to the folder you pick, nothing else
- Delete confirmation dialog before removing a file — deletes the real file from storage, not just the app's list
- No YouTube / Instagram / Facebook / social-media extraction — direct HTTP(S) links only

## Build

Open this folder in Android Studio (Ladybug or newer), let Gradle sync, then:

`Build ▸ Build APK(s)`

Or via command line: `./gradlew assembleDebug`

The generated APK is under:
`app/build/outputs/apk/debug/app-debug.apk`

A GitHub Actions workflow (`.github/workflows/build-apk.yml`) is included — push to `main`/`master` and it builds a debug APK automatically, downloadable from the Actions tab.

## Notes

- Servers without byte-range support automatically fall back to a single-connection download.
- Download metadata is persisted in SharedPreferences; in-progress files use temporary `.f2l.part<N>` segment files in the selected directory, merged into the final file on completion.
- Requires Android 8.0 (API 26) or newer.

## Developed by

**Goutham Josh** — [github.com/GouthamSER](https://github.com/GouthamSER)
