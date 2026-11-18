# AVNIVO Offline Integration — Progress Checkpoint

Branch: `integrate/offline-engines-20261118`
Base: `main` @ `83f720b` (protected original `05429fee35166ea07e22923f8235ef2e61ee8d3a` preserved, untouched)
Scope: offline engines only. No redesign, no online-path changes.

## Completed

| Item | Status |
| --- | --- |
| Repository + module audit | done |
| Toolchain (JDK21, Android SDK 35, Gradle 8.10.2) | done |
| Baseline local `assembleDebug` | done |
| Activation state machine (`OfflineModuleState.kt`) | done |
| Engine init + inference smoke test gate (`READY` only after real init) | done |
| Verified downloader hardening (temp cleanup on checksum failure) | done |
| Mic bridge wiring (`beginHold`/`startVoice` -> `AvnivoOfflineSTT.start()`) | done |
| `AvnivoOfflineResult` -> transcript; `AvnivoOfflineError` -> real diagnostics | done |
| JVM unit tests (19) | done, passing |
| Backend tests (37) | done, passing |
| Android CI runs unit tests + builds APK | done |

## Test evidence (local)

- `gradle testDebugUnitTest`: 19 tests, 0 failures
  (`OfflineModuleStateTest`, `OfflineSpeechEngineTest`, `VerifiedModelDownloaderTest`)
  - `VerifiedModelDownloaderTest` runs against a real loopback HTTP server (`MiniHttpServer`),
    no network mocks: valid download, corrupt file, missing file, resume, server without Range,
    untrusted host, wrong hash, repeated download.
- `pytest translator-api`: 37 tests, 0 failures.
- `gradle assembleDebug`: APK produced (debug, ~129 MB with Sherpa runtime).

## Module verification

| Module | Backend ticket | Status |
| --- | --- | --- |
| Uzbek STT (`uzbek-stt`) | 200, 2 files, 591,649,571 B | IMPLEMENTED — DEVICE TEST PENDING |
| Navoiy TTS (`navoiy-tts`) | 200, 22 files | BLOCKED — engine/runtime not integrated in app |
| MMS TTS (`mms-tts`) | 200, 6 files | BLOCKED — engine/runtime not integrated in app |
| Whisper Small (`whisper-small-q8`) | 404 | BLOCKED — EXTERNAL DEPENDENCY (no verified manifest) |
| Whisper Large V3 Turbo | 404 | BLOCKED — EXTERNAL DEPENDENCY |
| NLLB-200 (`nllb-200-600m-int8`) | 404 | BLOCKED — EXTERNAL DEPENDENCY |
| Large Offline Translation | n/a | BLOCKED — no supported model/runtime identified |

Fail-closed: unknown/unavailable models 404; the UI must not mark them ready.

## Remaining

1. Push branch, open one consolidated PR.
2. Run GitHub Actions APK workflow; download and record APK SHA-256.
3. Device/emulator acceptance for Uzbek STT with real Uzbek speech, then offline (airplane) re-test.
   Local limitation: no `/dev/kvm`, so no local emulator — must use CI/remote device.
4. Integrate Navoiy/MMS TTS runtimes or report precise blockers.
5. Verify app restart persistence and no online-path regression.

## Next exact action

Commit, push branch, and trigger the "AVNIVO integrated Android APK" workflow.
