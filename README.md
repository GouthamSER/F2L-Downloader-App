# F2L Downloader

A direct-link Android download manager MVP.

## Features
- HTTP/HTTPS direct links
- Pause/resume
- Parallel segmented downloads when the server supports HTTP Range requests
- Automatic retry
- Progress, speed and ETA
- Background foreground-service downloading
- Download history
- Android share-menu support
- User-selected download directory via Storage Access Framework
- No YouTube/Instagram/Facebook extraction

## Build
Open this folder in Android Studio (Ladybug or newer), allow Gradle sync, then:
`Build > Build APK(s)`

The generated APK is normally under:
`app/build/outputs/apk/debug/app-debug.apk`

## Notes
Some servers do not support byte-range requests. Those files automatically fall back to a single connection.
The first version stores download metadata in SharedPreferences and uses `.f2l.part` temporary files in the selected directory.
