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
- **Real model inference (Linux x86-64, sherpa-onnx 2.0.1, same config as the app):**
  - Downloaded the *actual* `uzbek-stt` model from the live ticket endpoint.
    - `model.int8.onnx` 591,649,160 B, SHA-256 `8bff17ef…c1b0873` — matches app expectation.
    - `tokens.txt` 411 B, SHA-256 `2022129a…2f98710f` — matches app expectation.
  - Decoded real Uzbek audio (Navoiy TTS demo, documented transcript) with the same
    NeMo CTC int8 / 16 kHz / 80-dim config `OfflineSpeechEngine.kt` uses:
    - expected: `Navoiy TTS o'zbekcha matnni tabiiy va ravon ovozga aylantiradi.`
    - actual:   `navoiy tts o'zbekcha matnni tabiiy va ravon ovozga aylantiradi.`
    - word recall 9/9 = 1.00.
  - Reproduce with `offline-stt/verify_uzbek_stt_inference.py`.
  - This proves model + runtime + decoder correctness; Android *device* capture is still pending.

## CI evidence

- Workflow: "AVNIVO integrated Android APK" (`.github/workflows/avnivo-integrated-android.yml`).
- Jobs: `apk` (JVM unit tests + build) then `emulator-smoke` (needs apk).
- Emulator smoke (run 44413040002, job 135186695244) — PASSED:
  - x86_64 Android API 34 google_apis emulator, KVM enabled on the runner.
  - `adb install -r app-debug.apk` succeeded.
  - `pm list packages` found `com.avnivo.translator`.
  - `am start -W com.avnivo.translator/.MainActivity` -> `Displayed ... for user 0: +2s7ms`.
  - app process stayed alive (`app pid: 3562`), no `FATAL EXCEPTION` in logcat.
  - WebView loaded and rendered the packaged interface.
  - logcat artifact: `avnivo-emulator-logcat`.
- APK artifact (final HEAD `ef8374b`, run 44414437306, jobs `apk` + `emulator-smoke` both passed):
  - package `com.avnivo.translator`, versionName `1.0.0`, versionCode 1, minSdk 26, targetSdk 35.
  - ABIs packaged: arm64-v8a, armeabi-v7a, x86, x86_64.
  - SHA-256 `bc6f50010d827f74b37e5dc3a8555ba6d8d0165bfa083d938dabf7b9532f20fb`.
  - `Displayed com.avnivo.translator/.MainActivity for user 0: +6s396ms`; no fatal exception.
  - Note: debug APKs embed build timestamps, so the SHA-256 differs per build; the value above is
    for the exact artifact of run 44414437306.

## Module verification

| Module | Model source | Download | Integrity | Runtime | Real inference | Offline | Android integration | Classification |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Uzbek STT | user-supplied archive, live ticket | verified (200) | SHA-256 matched | sherpa-onnx 2.0.1 NeMo CTC int8 | 9/9 word recall on real Uzbek audio (Linux) | yes (no network at decode) | APK wiring done | IMPLEMENTED — DEVICE TEST PENDING |
| Navoiy TTS | aisha-org/navoiy-tts (CosyVoice2 + emotion checkpoint, PyTorch) | ticket 200, 22 files | sha256 in manifest | requires PyTorch + CUDA/GPU (CosyVoice2) | not run | n/a | not integrated | BLOCKED — EXTERNAL DEPENDENCY |
| MMS TTS | facebook/mms-tts (VITS, PyTorch/safetensors) | ticket 200, 6 files | sha256 in manifest | requires PyTorch VITS runtime | not run | n/a | not integrated | BLOCKED — EXTERNAL DEPENDENCY |
| Whisper Small (`whisper-small-q8`) | whisper.cpp (documented plan only) | 404 | n/a | whisper.cpp not integrated | not run | n/a | not integrated | BLOCKED — EXTERNAL DEPENDENCY |
| Whisper Large V3 Turbo | none hosted | 404 | n/a | not integrated | not run | n/a | not integrated | BLOCKED — EXTERNAL DEPENDENCY |
| NLLB-200 (`nllb-200-600m-int8`) | none hosted | 404 | n/a | not integrated | not run | n/a | not integrated | BLOCKED — EXTERNAL DEPENDENCY |
| Large Offline Translation | none identified | n/a | n/a | none | not run | n/a | not integrated | BLOCKED — EXTERNAL DEPENDENCY |

Blocker detail (evidence, not assumption):

- `navoiy-tts` manifest (`translator-api/verified_models/navoiy-tts.json`) records
  `checkpoint.repo = aisha-org/navoiy-tts`, `base_model.repo = FunAudioLLM/CosyVoice2-0.5B`,
  `runtime.torch = 2.3.1+cu121`, `runtime.gpu = NVIDIA A10-12Q`. CosyVoice2 needs a PyTorch
  GPU runtime; there is no Android CPU runtime packaged, so the app cannot load it.
- `mms-tts` ships `model.safetensors`/`pytorch_model.bin` (VITS). No on-device VITS runtime
  is integrated in the app.
- Whisper and NLLB modules return HTTP 404 from the live ticket endpoint, i.e. no verified,
  hosted model exists yet. The UI keeps these sections but must stay fail-closed.

No model URL, checksum, license, or capability was invented anywhere in this work.

## Remaining

1. Push branch, open one consolidated PR. — done (PR #28, draft).
2. Run GitHub Actions APK workflow; download and record APK SHA-256. — done.
3. CI emulator smoke: install + launch + crash check. — done (passed).
4. Real Uzbek-speech inference *on the Android device/emulator* and the airplane-mode re-test.
   The model+runtime are proven correct on Linux and the app installs/launches on the emulator,
   but no automated test yet feeds real microphone audio through the on-device engine. This needs
   either a physical device or an emulator audio-injection harness. Not yet done.
5. Integrate Navoiy/MMS TTS runtimes or report precise blockers. — classified BLOCKED with evidence.
6. Confirm no online-path regression. — diff shows no online files changed; runtime UI parity not
   machine-verified beyond successful launch.

## Next exact action

Feed a known Uzbek WAV through the on-device engine (emulator `adb` audio injection or a physical
device) to close the remaining on-device inference gap; otherwise report it as the open limitation.
