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

## Build
Prerequisites: JDK 17, Android SDK 35. The Gradle wrapper (8.9) is committed.

```bash
cd android
./gradlew :app:testDebugUnitTest   # JVM tests for the WebView origin/microphone policy
./gradlew :app:assembleDebug       # produces app/build/outputs/apk/debug/app-debug.apk
```

CI (`.github/workflows/avnivo-android.yml`) runs the same steps and uploads the debug APK as the `avnivo-translator-debug-apk` artifact.

## Current state
The debug APK compiles and packages the full localized UI. JVM unit tests cover the origin and microphone policy. Device/emulator launch, microphone permission, and locked-screen behavior are **not yet verified** (no emulator in the build environment); they remain manual acceptance steps.

## Security gates
1. Confirm server-side ticket endpoint deployed and CORS/auth behavior.
2. Confirm GCS Range support, content length and Content-Range.
3. Never mark model ready until exact pinned SHA-256 for both files matches.
4. Keep signed URLs out of logs and WebView JavaScript.
5. Validate Android 14+ foreground service type and user-visible notification.
6. Verify locked-screen, app-background, network-off/on, force-stop and reboot behavior on a physical device.

