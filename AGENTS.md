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

## Whisper on device

`whisper.cpp` always encodes 1500 positions - a full 30 s window - whatever the recording length.
That single fact explains the offline STT latency: on a 5 s clip the encoder was 37.8 s of a 37.9 s
decode and the decoder was 78 ms. `WhisperTuning.audioContextFor` sizes the window to the recording
(`positions + max(160, positions/2)`, clamped to `[320, 1500]`); the 160 floor is measured, because a
3 s clip loops on a repetition below 288 positions. Leave whisper's temperature ladder alone - it is
the safety net that recovers the clips that still trip a loop.

`WHISPER_STAGE_TIMING` in logcat is `whisper_print_timings()` captured through the JNI log callback;
it is the only way to see where a decode went without instrumenting native code.

Never hand whisper's transcript to `NewStringUTF`: it wants Modified UTF-8 and aborts the process
(CheckJNI is on for every debuggable build) when the text holds a four-byte sequence. `utf8ToJString`
in `avnivo_whisper_jni.cpp` does the conversion.

## Emulator smoke job

The `emulator-smoke` job runs every instrumented suite in one long shell step, so a single flaky
suite fails the job. It has failed on different suites in different runs (Maven Central 429 during
`checkReleaseAarMetadata`, `OnlineMicInstrumentedTest` process crash, whisper inference crash) while
`apk` and `update-install` stayed green. Read the failing step before assuming a code regression, and
remember that a dead instrumentation process prints no test dots - its buffered output is lost.
