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
| Mic waveform follows real native amplitude (Defect A) | done |
| Press-and-hold recording stops on release (Defect B) | done |
| No recording-status text in the composer (Defect C) | done |
| Stable release signing + monotonic versionCode (in-place update) | done |

## Test evidence (local)

- `gradle testDebugUnitTest`: 23 tests, 0 failures
  (`OfflineModuleStateTest`, `OfflineSpeechEngineTest`, `VerifiedModelDownloaderTest`,
  `OfflinePcmRecorderAmplitudeTest` — silence is 0, louder speech is larger, bounded to 1).
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
- Jobs: `apk` (JVM unit tests + build), `update-install` (release signing + in-place
  update), `emulator-smoke` (install + launch + instrumented tests).

### Latest run (all green)

Run `44429259410`, HEAD `a1065b5`:

- `apk`: PASS (23 JVM unit tests, APK built).
- `update-install`: PASS. Two release APKs built with one identity and increasing
  versionCode; on the emulator `v100` installs, then `adb install -r v101` reports
  `Success`; `firstInstallTime` is unchanged (2026-11-19 00:17:42) and versionCode
  goes 100 -> 101, so the update replaced the app in place and kept its data
  (`UPDATE_INSTALL_OK`). Signing cert DN `CN=AVNIVO CI, O=AVNIVO`.
- `emulator-smoke`: PASS on x86_64 API 34 google_apis.
  - `OfflineEngineInstrumentedTest`: `OK (2 tests)` — native Sherpa runtime loads on
    the device ABI; engine fails closed with no model.
  - `OfflineMicGestureInstrumentedTest`: `OK (4 tests)` — the packaged interface is
    loaded in a real WebView; recognized text is inserted into the composer, terminal
    status/error clear the recording state without writing text, and the amplitude
    callback is accepted.
  - `OfflineRealInferenceTest`: `OK (1 test)` — downloads the real 592 MB `uzbek-stt`
    model through the production downloader, initializes the native engine, and
    transcribes the bundled 16 kHz Uzbek utterance with word recall 9/9.
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
    for the exact artifact of run 14414437306.
- On-device instrumented tests (run 44416575550, job 135199150422, both passed):
  - `OfflineEngineInstrumentedTest`: `System.loadLibrary("sherpa-onnx-jni")` succeeds on the x86_64
    device ABI, and `OfflineSpeechEngine.prepare()` fails closed when the model is absent.
  - `OfflineRealInferenceTest` (`OK (1 test)`, 41.5 s): the emulator downloaded the real 592 MB
    model through `VerifiedModelDownloader`, initialized the native engine, ran the silence smoke
    test, and transcribed the bundled real Uzbek utterance `uzbek_calm_intro_16k.wav` with word
    recall >= expected-1. This is real on-device inference, not a mock.
  - Limitation: audio is fed as a bundled WAV, not captured live from the microphone. Live-mic
    capture still needs a physical device (or an emulator mic-injection harness).

## Whisper integration (2026-11-19)

### Why a separate native runtime

The sherpa-onnx AAR only loads ONNX-format Whisper (encoder/decoder). The hosted Whisper models
are **ggml** files (`ggml-small-q8_0.bin`, `ggml-large-v3-turbo-q5_0.bin`), which sherpa-onnx
cannot read. A ggml-native runtime was therefore required: whisper.cpp built from source with the
NDK, wrapped in a narrow JNI bridge.

### Added components

| File | Purpose |
| --- | --- |
| `android-app/app/src/main/cpp/CMakeLists.txt` | Builds `libavnivo_whisper.so` against whisper.cpp |
| `android-app/app/src/main/cpp/avnivo_whisper_jni.cpp` | JNI init / transcribe / release |
| `.../WhisperEngine.kt` | Kotlin wrapper; `prepare()`, `transcribe()`, `smokeTest()` |
| `.../OfflineModelSpecs.kt` | Verified ids, sizes, SHA-256 per module |
| `.../OfflineModuleManager.kt` | Per-module state machine, download, init, dispose |
| `.../MainActivity.kt` | `AvnivoOfflineModules` JS bridge; mic routed to a ready Whisper engine |
| `assets/index.html` | Card sync with native state; progress/ready/error callbacks |

`whisper.cpp` is fetched by CI into `app/src/main/cpp/whisper.cpp` and is git-ignored. When the
source is absent (local builds), the native module is not compiled and the app still builds.

### Local evidence (this workspace)

- `gradle testDebugUnitTest`: **29 tests, 0 failures** (adds `OfflineModuleManagerTest`: unknown id
  fails closed, catalog only holds real engines, installed model reaches READY, engine failure never
  reports READY, wrong-size file is not installed, `statusAll` covers every module).
- `gradle assembleDebug` (x86_64): APK contains `libavnivo_whisper.so`, `libwhisper.so`,
  `libggml*.so` alongside the sherpa-onnx and ONNX Runtime libraries.
- `gradle assembleDebugAndroidTest`: compiles.

### On-device evidence (GitHub Actions run 44439575323, HEAD `adf18b6`)

x86_64 API 34 `google_apis` emulator, KVM enabled:

- `OfflineEngineInstrumentedTest`: `OK (2 tests)` — sherpa-onnx runtime loads; Uzbek engine fails closed.
- `OfflineMicGestureInstrumentedTest`: `OK (4 tests)` — WebView gesture/composer wiring intact.
- `OfflineRealInferenceTest`: `OK (1 test)` — real 592 MB Uzbek model downloaded, initialized, transcribed.
- `OfflineWhisperInferenceTest` (runtime layer): `OK (3 tests)` — `libavnivo_whisper` loads on the
  device ABI and the engine fails closed without a model.
- `OfflineWhisperInferenceTest` (real inference, 1528 s): `OK (3 tests)` — downloaded the real
  264,464,607 B `whisper-small-q8` model through the production downloader, SHA-256 verified,
  ggml runtime initialized, smoke test passed, and the bundled 11 s English utterance transcribed
  with the expected words. No network is used for inference.

Whisper Large V3 Turbo has its own opt-in job (`large-model-smoke`, dispatch input
`run_large_model_verification`) because the download plus CPU inference is long; it is not part of
the default pipeline.

## Module verification

| Module | Model source | Download | Integrity | Runtime | Real inference | Offline | Android integration | Classification |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Uzbek STT | user-supplied archive, live ticket | verified (200) | SHA-256 matched | sherpa-onnx 2.0.1 NeMo CTC int8 | 9/9 word recall (Linux) and on-device emulator test OK | yes (no network at decode) | APK wiring done | VERIFIED WORKING (emulator); physical device pending |
| Whisper Small (`whisper-small-q8`) | `multilingual-stt/whisper-small-q8_0` in the AVNIVO model bucket, ticket 200 | verified | SHA-256 matched | whisper.cpp v1.7.4 + ggml, NDK-built | on-device emulator: real transcription of a known utterance OK | yes | card + bridge + mic routing done | VERIFIED WORKING (emulator); physical device pending |
| Whisper Large V3 Turbo (`whisper-large-v3-turbo`) | `multilingual-stt/whisper-large-v3-turbo-q5_0`, ticket 200 | verified | SHA-256 matched | same whisper.cpp runtime | not yet run (opt-in job) | runtime is offline | card + bridge done | IMPLEMENTED — DEVICE TEST PENDING |
| NLLB-200 (`nllb-200-600m-int8`) | `multilingual-translation/nllb-200-600m-int8`, ticket 200, 8 files | verified | SHA-256 in manifest | no on-device runtime yet | not run | n/a | card + download bridge done | BLOCKED — EXTERNAL DEPENDENCY (runtime not integrated) |
| Navoiy TTS | aisha-org/navoiy-tts (CosyVoice2 + emotion checkpoint, PyTorch) | ticket 200, 22 files | sha256 in manifest | requires PyTorch + CUDA/GPU (CosyVoice2) | not run | n/a | not integrated | BLOCKED — EXTERNAL DEPENDENCY |
| MMS TTS | facebook/mms-tts (VITS, PyTorch/safetensors) | ticket 200, 6 files | sha256 in manifest | requires PyTorch VITS runtime | not run | n/a | not integrated | BLOCKED — EXTERNAL DEPENDENCY |
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

## Final artifact (run 44430821489, HEAD 6d84b12)

- Release APK artifact: `avnivo-translator-release-apk` (GitHub Actions artifact,
  built by the `update-install` job).
- Package `com.avnivo.translator`, versionCode 101, versionName 1.0.0, minSdk 26, targetSdk 35.
- ABIs: arm64-v8a, armeabi-v7a, x86, x86_64.
- SHA-256 `5e3271fdb225923f8a48955d814e6e695f7af4b65fc6552d6c6c6d134dccf0a5`
  (128,206,663 bytes). The hash is per-run because the artifact is signed with the
  ephemeral CI key; the APK installs and updates in place (verified in `update-install`).
- This is a GitHub Actions artifact, not a GitHub Release (no production release is
  published without approval).

## Remaining

1. Push branch, open one consolidated PR. — done (PR #28, draft).
2. Run GitHub Actions APK workflow; download and record APK SHA-256. — done.
3. CI emulator smoke: install + launch + crash check. — done (passed).
4. Real on-device inference on the emulator. — done (passed).
5. Mic gesture/composer wiring on-device (WebView). — done (4 tests passed).
6. Stable release signing for frictionless in-place update. — mechanism done and
   proven in CI (two APKs, one identity, `adb install -r` succeeds, data kept).
   Residual gap: the published artifact is signed with a per-run ephemeral key
   because this token cannot write repository secrets (HTTP 403 on
   `actions/secrets`). To publish a stable identity, a repo admin must add
   `AVNIVO_KEYSTORE_BASE64`, `AVNIVO_KEYSTORE_PASSWORD`, `AVNIVO_KEY_ALIAS`,
   `AVNIVO_KEY_PASSWORD`; the workflow already consumes them when present.
7. Live-microphone capture path on a physical device (or emulator mic injection).
   The engine, WAV inference, and WebView gesture/composer wiring are proven
   on-device; only live-mic audio capture into the engine is unverified automatically.
8. Integrate Navoiy/MMS TTS runtimes or report precise blockers. — classified BLOCKED with evidence.
9. Confirm no online-path regression. — `index.html` diff is offline-only (offline
   waveform driver, gesture handling, bridge handlers); no visual/CSS/markup or
   online-path change. Runtime UI parity is not machine-verified beyond successful launch.

## Next exact action

The offline Uzbek STT and Whisper Small modules are VERIFIED WORKING end to end on the emulator.
Remaining work, in priority order:

1. Run the opt-in `large-model-smoke` job (dispatch the workflow with
   `run_large_model_verification: true`) to move Whisper Large V3 Turbo from
   "IMPLEMENTED — DEVICE TEST PENDING" to a verified classification.
2. Integrate an NLLB ONNX runtime (encoder/decoder + SentencePiece tokenizer) if one can be built
   from the packaged ONNX Runtime; until then the card stays fail-closed.
3. Physical-device check (Samsung Galaxy S23 Ultra) for live-microphone capture and Large-model RAM
   behaviour.
4. Live-microphone capture path on a physical device (or emulator mic injection).
