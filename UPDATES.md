# In-app updates — setup (one time)

App checks `https://api.github.com/repos/GouthamSER/F2L-Downloader-App/releases/latest`.
If the tag is newer than the installed `versionName`, it offers to download the APK and install it.
(Change `REPO` in `UpdateChecker.kt` if your repo name differs.)

## 1. Make a signing key (once, keep it forever)
```
keytool -genkeypair -v -keystore f2l.jks -alias f2l -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 f2l.jks > f2l.jks.base64.txt
```
**Never lose f2l.jks or its passwords** — Android refuses updates signed with a different key.
Never commit it to git.

## 2. Add GitHub Secrets (repo → Settings → Secrets and variables → Actions)
| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | contents of `f2l.jks.base64.txt` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | `f2l` |
| `KEY_PASSWORD` | key password |

## 3. Release a new version
1. Raise `versionCode` and `versionName` in `app/build.gradle.kts` (e.g. 10 / "3.5.0").
2. Commit and push.
3. `git tag v3.5.0 && git push origin v3.5.0`
4. Workflow `Release APK` builds the signed APK and creates the GitHub Release. The release text is shown to users as "What's new".

## Notes
- Users on the old **debug** APK must uninstall once and install the first signed release (different signing key). After that, updates install over the top.
- First update: Android asks the user to allow "Install unknown apps" for F2L.

## v3.6.1
- Fix HTTP 429: one connection per file; 429/503 now wait and retry (honors Retry-After).
- No more `.f2l.part` files: download writes into the final file and resumes from its length.
- Old leftover part files are converted or deleted on next start; delete removes everything.

## v3.6.2
- Fix "Couldn't write the file to your folder": illegal characters (: * ? " < > | /) and very long names are cleaned before the file is created. Old failed items are fixed on Retry.
