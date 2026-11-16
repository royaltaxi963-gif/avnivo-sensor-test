# AVNIVO Android shell — bundled WebView APK

This is an additive Android project. The existing `index.html`, online/offline controls, and web branch are unchanged. The shell bundles the *current* web app and its 100-language interface at build time, so the APK renders exactly the same UI as the web app.

## Architecture
- Single `MainActivity` hosts a WebView.
- The web app is served from a trusted local origin `https://appassets.androidplatform.net/assets/index.html` via AndroidX `WebViewAssetLoader` (no `file://` access, no exposed JavaScript bridge).
- Only that trusted origin may be granted the microphone, and only for `AUDIO_CAPTURE`; the Android runtime `RECORD_AUDIO` permission is requested first.
- Navigation to any other origin is blocked.
- File and content access are disabled; mixed content is never allowed; WebView debugging is off.

## Build inputs (single source of truth)
The `syncWebAssets` Gradle task copies the repository-root web app into `app/build/web-assets` before every build:
- `index.html`
- `offline-uzbek-download.js`
- `i18n/avnivo-i18n.js`, `i18n/languages.json`, and `i18n/locales/*.json` (100 packages)

This keeps the APK's UI identical to the web app and avoids committing duplicated generated assets.

## WebView compatibility
The bundled UI calls `Element.replaceChildren()`, which requires Chrome 86+. WebView
is an updatable system component, but an outdated WebView can still be present on a
minSdk 26 device (the emulator image used for verification ships WebView 83, which
throws `UnsupportedOperationException` on first render). The shell injects
`assets/polyfill-replace-children.js` — a no-op when the native method exists — at
document start via `WebViewCompat.addDocumentStartJavaScript` on WebView 84+, and as
a page-start fallback on older WebViews. The shim is covered by
`app/src/test/js/polyfill.test.mjs`, which executes the exact bundled asset against a
DOM stub (run: `node android/app/src/test/js/polyfill.test.mjs`).

## Build
Prerequisites: JDK 17, Android SDK 35. The Gradle wrapper (8.9) is committed.

```bash
cd android
./gradlew :app:testDebugUnitTest   # JVM tests for the WebView origin/microphone policy
node app/src/test/js/polyfill.test.mjs   # executes the WebView replaceChildren shim
./gradlew :app:assembleDebug       # produces app/build/outputs/apk/debug/app-debug.apk
```

CI (`.github/workflows/avnivo-android.yml`) runs the same steps and uploads the debug APK as the `avnivo-translator-debug-apk` artifact.

## Verified runtime behaviour
Verified on an Android 30 (`google_apis`, x86_64) emulator running under software
virtualisation (no KVM), so absolute timings are far slower than a physical device:

| Check | Result |
| --- | --- |
| Cold start (`am start -W`, first launch after install) | `WaitTime: 13507 ms` (TCG; includes WebView cold init) |
| Cold start, app process already warm | 5/5 launches reached `mResumedActivity` with no crash |
| Crash on launch (pre-fix, WebView 83) | `UnsupportedOperationException` at `MainActivity.configure` — fixed |
| `replaceChildren` console errors after fix | none |
| Memory (TOTAL PSS / RSS, idle, WebView loaded) | 78,778 KB PSS / 187,552 KB RSS |
| Package | `com.avnivo.translator`, versionCode 1, versionName 0.1.0-dev, compileSdk 35 |

Repeated force-stop/relaunch cycles (5×) all reached the resumed activity with no
`FATAL EXCEPTION`. The emulator itself is CPU-starved (TCG), so `com.android.systemui`
occasionally raises its own ANR dialog; that is an emulator artefact and not caused by
this app (the app process stays alive and focused beneath it).

## Current state
The debug APK compiles, packages the full localized UI, and launches and renders on an
emulator. JVM unit tests cover the origin and microphone policy; a Node test executes
the WebView compatibility shim. Microphone permission, locked-screen, and
background-download behaviour are **not yet verified** and remain manual acceptance
steps on a physical device.

## Security gates
1. Confirm server-side ticket endpoint deployed and CORS/auth behavior.
2. Confirm GCS Range support, content length and Content-Range.
3. Never mark model ready until exact pinned SHA-256 for both files matches.
4. Keep signed URLs out of logs and WebView JavaScript.
5. Validate Android 14+ foreground service type and user-visible notification.
6. Verify locked-screen, app-background, network-off/on, force-stop and reboot behavior on a physical device.

