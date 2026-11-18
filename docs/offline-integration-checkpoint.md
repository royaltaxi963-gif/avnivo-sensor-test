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
- Runs JVM unit tests, builds the debug APK, then installs and launches it on an x86_64
  Android API 34 emulator, asserting the process stays alive and no fatal exception is logged.
- APK artifact (verified locally from run 44405275116):
  - package `com.avnivo.translator`, versionName `1.0.0`, minSdk 26, targetSdk 35.
  - ABIs packaged: arm64-v8a, armeabi-v7a, x86, x86_64.
  - SHA-256 `a66303509bd26dbdd031a16b66a7251c373c2cbebab939284c984b5028a86f46`.

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
3. Device/emulator acceptance for Uzbek STT with real Uzbek speech, then offline (airplane) re-test.
   Local limitation: no `/dev/kvm`, so no local emulator — must use CI/remote device.
   In progress: CI emulator smoke job installs + launches the APK and checks for crashes.
4. Integrate Navoiy/MMS TTS runtimes or report precise blockers. — classified BLOCKED with evidence.
5. Verify app restart persistence and no online-path regression.

## Next exact action

Confirm the CI emulator smoke job passes, then report the final delivery status.
