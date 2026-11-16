# AGENTS.md — AVNIVO repository notes

## What this repository is
AVNIVO Translator: a single-page web app (`index.html`) with a 100-language i18n
layer (`i18n/`), plus an additive Android WebView shell (`android/`) that bundles
the web app into an APK.

## Layout
- `index.html` — the whole web UI (single source of truth for the interface).
- `offline-uzbek-download.js` — offline module download helper.
- `i18n/` — i18n engine and data:
  - `avnivo-i18n.js` — engine (load one active bundle, cache, fallback, RTL).
  - `languages.json` — registry of exactly 100 languages (8 are RTL).
  - `locales/*.json` — 100 UTF-8 packages, 180 keys each.
  - `tests/run-tests.mjs` — Node test suite (run: `node i18n/tests/run-tests.mjs`).
  - `validate.mjs`, `quality-report.mjs`, `generate*.py` — generators/validators.
- `android/` — Gradle project (`com.avnivo.translator`), single `MainActivity`.

## Build and test
- Web i18n tests: `node i18n/tests/run-tests.mjs`
- Android JVM tests: `cd android && ./gradlew :app:testDebugUnitTest`
- Android WebView shim test: `node android/app/src/test/js/polyfill.test.mjs`
- Build APK: `cd android && ./gradlew :app:assembleDebug`
- CI: `.github/workflows/avnivo-android.yml` (builds and uploads the debug APK).

## Gotchas learned
- The web assets are NOT duplicated into `android/`; the `syncWebAssets` Gradle
  task copies `index.html`, `offline-uzbek-download.js` and `i18n/**` into
  `app/build/web-assets` at build time. Edit the root web app, not a copy.
- `android-actions/setup-android@v3` no longer ships the `tools` package; install
  `platforms;android-35` and `build-tools;35.0.0` explicitly.
- WebView compatibility: the UI uses `Element.replaceChildren` (Chrome 86+).
  `WebViewCompat.addDocumentStartJavaScript` throws `UnsupportedOperationException`
  below WebView 84, so it MUST be guarded with
  `WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)` and
  fall back to `onPageStarted` injection. The shim lives in
  `android/app/src/main/assets/polyfill-replace-children.js` (single source).
- The CI/emulator Android 30 image ships WebView 83 and runs under TCG (no KVM).
  It is CPU-starved: `com.android.systemui` raises its own ANR dialogs and the
  launcher can kill cached processes. This is an emulator artefact — verify the
  app process is alive and `mResumedActivity` is MainActivity before concluding a
  failure. Set `hide_error_dialogs 1` and `stay_on_while_plugged_in 3` to reduce noise.
