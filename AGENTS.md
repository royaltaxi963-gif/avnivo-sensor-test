# Repository notes for agents

Android app (`android-app/`), Node source-shape contracts (`tests/`), and delivery docs (`docs/`).

## Build and test

```bash
# JVM unit tests and release build (from android-app/)
gradle --no-daemon testDebugUnitTest
gradle --no-daemon assembleRelease
gradle --no-daemon compileDebugAndroidTestKotlin

# Node contracts (from the repository root)
node --test tests/*.test.cjs tests/*.test.mjs
```

The release build needs `app/libs/sherpa-onnx-2.0.1.aar` and `app/src/main/cpp/whisper.cpp`
(cloned at tag `v1.7.4`); CI fetches both, and they are not committed. Without
`AVNIVO_KEYSTORE_FILE` the release APK is unsigned — never generate a new key, because an APK
signed with a different key cannot be installed over the installed app.

`tests/*.test.cjs` assert the *shape* of Kotlin sources with regexes and balanced-brace extraction.
Changing a Kotlin signature — even adding a default parameter — breaks them, so update the matching
`functionBody(...)` call in the same commit.

## Signing identity

Client certificate SHA-256 `12e7e26104c7a5110a849baec36d6916efe0b2494384749d4c733286aeeecb8e`
(CN=AVNIVO Translator). The keystore lives only in repository secrets; it is never in the tree.

## Independent build

`android-app/cloudbuild.yaml` and `docs/independent-android-build.md` describe how to build, sign
and verify without GitHub Actions, including the cost of each path.

## Offline engines

Native loads and decodes must go through `OfflineGuard`, which runs them on their own thread with a
budget and a stage name. A decoder that is still inside native code is quarantined, never freed.
Diagnostics go through `OfflineDiagnostics` (logcat plus `diagnostics/offline.log`).
