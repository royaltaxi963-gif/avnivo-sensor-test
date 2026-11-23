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
| NLLB-200 (`nllb-200-600m-int8`) | `multilingual-translation/nllb-200-600m-int8`, ticket 200, 8 files | verified | SHA-256 in manifest | ONNX Runtime 1.32.0 via `libavnivo_nllb`, encoder+merged decoder | emulator: 6/6 tests, real uz→ru/en text (see numbers below) | yes, re-run with radios down | card + download bridge + JNI decode done | VERIFIED WORKING (emulator); physical device pending |
| Navoiy TTS | aisha-org/navoiy-tts (CosyVoice2 + emotion checkpoint, PyTorch, Apache-2.0) | ticket 200, 22 files; no ONNX export published | sha256 in manifest | requires PyTorch + CosyVoice2 runtime; no ONNX artifact under `tts/uzbek/navoiy/` (403) | not run | n/a | not integrated | BLOCKED — EXTERNAL DEPENDENCY |
| MMS TTS | facebook/mms-tts-rus, converted by sherpa-onnx to VITS ONNX (`vits-mms-rus.tar.bz2`, 107,761,221 B) | ONNX export measured: 3 Russian sentences synthesized offline, 0.27–0.41 s each, 16 kHz, rms 0.10, 65.5% energy in the 300–3400 Hz speech band | model.onnx 114,021,556 B sha256 8735fad7…34dd, tokens.txt 447 B sha256 361fb887…d23b | bundled sherpa-onnx 2.0.1 AAR ships OfflineTts + OfflineTtsVitsModelConfig | not run on device | n/a | not integrated | BLOCKED — EXTERNAL DEPENDENCY |
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

## NLLB-200 offline translation (2026-11-19)

### What changed

The `offline-translate` settings card (`installerId: nllb-200-600m-int8`) previously failed closed
because no on-device ONNX runtime existed. It now has one: the sherpa-onnx AAR already ships
`libonnxruntime.so` 1.28.2, so the NLLB encoder/decoder sessions are driven through the ONNX Runtime
C API (resolved with `dlopen`, so no second copy of the runtime is packaged).

| File | Purpose |
| --- | --- |
| `cpp/onnxruntime/*.h` | Vendored ORT C API headers (v1.28, `ORT_API_VERSION` 28) |
| `cpp/avnivo_nllb_jni.cpp` | Encoder run + greedy decoder loop, empty past tensors, `use_cache_branch=false` |
| `NllbEngine.kt` | Tokenizer, language gate, `prepare()`/`translate()`/`smokeTest()` |
| `OfflineModelSpecs.kt` | NLLB runnable, 1.8 GB RAM requirement; Whisper Small 700 MB |
| `OfflineModuleManager.kt` | `translate()`, `supportsTranslation()`, `reactivateInstalled()` |
| `MainActivity.kt` | `translate`/`supportsTranslation` JS bridge, answers on the worker thread |
| `assets/index.html` | Offline sends call the native engine; placeholder only when not READY |

The decoder export's KV-cache branch is broken (the merged `If` graph rejects empty past tensors),
so each step re-runs the full decoder sequence. That is correct but quadratic in output length, hence
the 48-token cap. The cache-branch path is deliberately pinned to `false`.

### Desktop ground truth for the exact JNI algorithm

`/tmp/replica.py` mirrors `avnivo_nllb_jni.cpp` step for step (same input names, same empty past
shapes, same greedy rule, same 48-token cap) and was run against the real model files:

| Pair | Input | Output |
| --- | --- | --- |
| uzn_Latn -> rus_Cyrl | `Salom, qalaysiz?` | `Привет, как вы?` |
| uzn_Latn -> rus_Cyrl | `Bugun havo juda issiq.` | `Сегодня очень жарко.` |
| uzn_Latn -> rus_Cyrl | `Men O'zbekistondan keldim.` | `Я из Узбекистана.` |
| uzn_Latn -> rus_Cyrl | `Rahmat` | `Спасибо` |
| uzn_Latn -> eng_Latn | `Salom, qalaysiz?` | `Hello, are you staying?` |
| eng_Latn -> rus_Cyrl | `hello world` | `Привет, мир` |
| rus_Cyrl -> uzn_Latn | `Здравствуйте, как дела?` | `Salom, qanday bo'ldingiz?` |

All 8 model files match the sizes and SHA-256 values in `OfflineModelSpecs.kt` exactly.

### Local test evidence

- `gradle testDebugUnitTest`: **44 tests, 0 failures, 0 skipped**
  - `NllbTokenizerTest` pins the Kotlin tokenizer to ids produced by the reference `tokenizers`
    library from the same `tokenizer.json` (10 sentences, including apostrophes, Cyrillic and
    mixed case). This caught two real bugs: the missing leading metaspace marker and the fact that
    `uz`/`亵`/`<mask>` are in the vocabulary but are not language codes.
  - Language gate, blank input, unknown pieces -> unk, malformed/missing tokenizer, fail-closed
    `prepare()`, and per-module status JSON are covered.
- `node --test tests/offline-translation/route.test.mjs`: **11 tests, 0 failures**. These execute the
  routing block extracted from the shipped `index.html` against a stubbed bridge: real result used,
  history persisted, placeholder only when the engine is unavailable, native errors surfaced,
  blank results rejected, unknown/malformed payloads ignored, concurrent requests resolved
  independently, detached bubbles safe.
- `gradle assembleDebug`: APK contains `lib/x86_64/libavnivo_nllb.so`, `libavnivo_whisper.so` and
  `libonnxruntime.so`.

### NLLB status

IMPLEMENTED — DEVICE TEST PENDING. The algorithm is verified on desktop against the real model and
the Kotlin/native layers compile and unit-test green; the on-emulator download + init + real
translation run is the opt-in `nllb-smoke` CI job.

## Next exact action

1. Push and let `apk` + `emulator-smoke` run; then dispatch the workflow with
   `run_nllb_verification: true` to move NLLB to VERIFIED WORKING (or capture the exact failure).
2. Dispatch with `run_large_model_verification: true` for Whisper Large V3 Turbo.
3. Integrate Navoiy/MMS TTS runtimes or record the precise blocker (currently BLOCKED with evidence).
4. Physical-device check (Samsung Galaxy S23 Ultra) for live-microphone capture and Large-model RAM.

## Regression evidence vs the protected original (`05429fee`)

Measured with `git diff 05429fee35166ea07e22923f8235ef2e61ee8d3a HEAD`.

The shipped UI is `android-app/app/src/main/assets/index.html`, derived from the
original `onboarding-tutorial-preview.html`. Diffing those two files directly is
the meaningful regression check, because the repository-root `index.html` is a
separate development page that the APK does not package (it is not referenced by
`build.gradle.kts`, `settings.gradle.kts`, or the Android workflow).

Original UI -> shipped UI: **220 insertions, 13 deletions**, and every deleted
line was re-added in modified form. Each was verified present afterwards:

| Deleted line | Status in shipped UI |
| --- | --- |
| `if(!erasing){` brand typing loop | present, 1 occurrence |
| `modeOnline` / `modeOffline` click handlers | present, 2 occurrences |
| `multilingual-stt` and `multilingual-stt-large` catalog rows | present, 4 occurrences |
| saved-conversation init comment | present |
| `typeTranslation(...)` preview branch | present |
| `const wasHold=...` hold-state capture | present |
| `mic.addEventListener('pointercancel', ...)` | present, 3 occurrences |
| `pagehide` cleanup listener | present, 2 occurrences |
| `</html>` | present |

Original feature markers are unchanged, count-for-count against the original:

| Marker | Original | Shipped |
| --- | --- | --- |
| `SpeechRecognition` | 1 | 1 |
| `speechSynthesis` | 3 | 3 |
| `speak-message` | 2 | 2 |
| `language-chip` | 5 | 5 |
| `history-drawer` | 3 | 3 |
| `mode-switch` | 2 | 2 |

So the online STT, online TTS, language selector, history drawer and the
online/offline switch are all intact, and the offline additions are additive.

## Device verification run (emulator), commit 925b7c1

Workflow `avnivo-integrated-android.yml`, run 44582570636, job `emulator-smoke`:
**success**. Four instrumented suites ran on the Android emulator against the
installed APK, 16 tests total, all passing:

| Suite | Result |
| --- | --- |
| `OfflineEngineInstrumentedTest` | OK (2 tests) |
| `OfflineMicGestureInstrumentedTest` | OK (6 tests) |
| `OfflineRealInferenceTest` (`-e avnivoRealInference true`) | OK (4 tests) |
| `OfflineWhisperInferenceTest` (runtime + `-e avnivoWhisperInference true`) | OK (4 tests) |

`OfflineRealInferenceTest.transcribesRealUzbekSpeechOffline` downloads the Uzbek
model, prepares the engine, runs `smokeTest()`, and transcribes the bundled
`uzbek_calm_intro_16k.wav`, requiring at least 6 of 7 expected Uzbek words. It
passed, so Uzbek STT inference is real, not mocked.

One failure was found and fixed on the way. The first run of the new mic gesture
test reported `during:true, after:false, started:1, stopped:1` yet failed: the
helper trims only the outer quotes of `evaluateJavascript`, so a `JSON.stringify`
result still contains escaped quotes and the assertion could never match. The
behaviour under test was already correct; the assertion was wrong. Commit 925b7c1
compares joined fields instead.

Device gap: this is an emulator, not the Samsung Galaxy S23 Ultra. Physical-device
verification (real microphone, real thermal and memory pressure) remains
unverified and is not claimed.

### Whisper coverage detail

Only Whisper Small is verified as real inference so far. In the run above,
`-e avnivoWhisperInference true` passed, and that suite takes ~20 minutes
(1,212 s), which is consistent with downloading ~265 MB and decoding audio on an
emulator. `transcribesKnownEnglishSpeechOffline` asserts non-empty text with at
least 4 of 7 expected English words, so this is real inference rather than a load
check.

Whisper Large V3 Turbo is gated behind a separate flag
(`avnivoWhisperLargeInference`) and runs only in the `large-model-smoke` job. It
requires the module to reach `ready` after a real download, then asserts real
transcription recall on `whisper_jfk_16k.wav`. Its result is recorded separately
below; it is **not** verified until that job passes.

The first `OfflineWhisperInferenceTest` invocation (no inference flag) ran in
0.05 s and its 4 tests are skipped by `assumeTrue`. Only the second invocation
with `-e avnivoWhisperInference true` is evidence of inference.

### NLLB-200 translation: real inference measured (run 44587444906)

`nllb-smoke` passed: `OK (6 tests)` in 120 s. `translatesUzbekToRussianOffline`
downloaded the 916 MB INT8 model, reached `ready`, and produced:

| Input | Output | Latency |
| --- | --- | --- |
| `Salom, qalaysiz?` | `Привет, как вы?` | 3,866 ms |
| `Bugun havo juda issiq.` | `It's very hot today.` | 4,265 ms |
| `Rahmat` | `Спасибо` | 1,553 ms |

Logged by the test itself (`AvnivoNllb` tag in `logcat_nllb.txt`). Translations
are correct and the engine instance is reused across calls, so this is genuine
offline inference, not a stub.

**Caveat that this run does not close:** the job comment claimed the device had
no network access, but no step disabled networking, and logcat shows requests to
the model host while downloading. So this run proves translation works, not that
it works with the network down. Commit `daf4112` adds an offline re-verification
stage (disable wifi/data/airplane mode, assert the network is down, repeat the
suites) to both `nllb-smoke` and `emulator-smoke`. The offline result is
**unverified** until that stage runs.

### Whisper Large V3 Turbo: verified on device (run 44573085612)

`large-model-smoke` passed: `OK (4 tests)` with a suite time of **5,769 s (~96
min)**. That duration is consistent with a real 574 MB download followed by CPU
decoding of both models, and it is far too long for an `assumeTrue` skip (which
takes milliseconds).

The test asserts the module reaches `ready` after a real download and verify, then
transcribes `whisper_jfk_16k.wav` and requires at least 4 of 7 expected English
words. Both the `ready` gate and the recall assertion passed, so the large model
really initializes and infers on device rather than only downloading.

Emulator RAM note: this job used a 4 GB AVD and still passed. The module refuses
to initialize below `requiredRamBytes` = 1,435,000,000, so the guard is what keeps
it safe rather than the emulator being generous.

This is an emulator result on x86_64. It is not physical-device verification and
does not cover ARM memory pressure on a real phone.

### NLLB-200 offline operation: VERIFIED (run 44590517947, commit daf4112)

The offline stage added in `daf4112` ran and passed. After disabling wifi, mobile
data and airplane mode and confirming the network was unreachable,
`OfflineNllbInferenceTest` passed again: `OK (6 tests)` in **29.972 s**, against
120 s for the online run. The speed-up is expected, because the model was already
installed and only had to be loaded and run.

Translations from the offline log are identical to the online run:

| Input | Output | Offline latency |
| --- | --- | --- |
| `Salom, qalaysiz?` | `Привет, как вы?` | 4,378 ms |
| `Bugun havo juda issiq.` | `It's very hot today.` | 4,912 ms |
| `Rahmat` | `Спасибо` | 1,625 ms |

`logcat_nllb_offline.txt` contains **zero** requests to the model host, against 19
in the online download run. So NLLB-200 translation genuinely runs without
network access, and this is measured rather than asserted.

### TTS blockers: artifact-level evidence

The two TTS modules cannot run on device, and the reason is the artifacts in the
bucket rather than missing code. Counted from `translator-api/verified_models/*.json`:

| Model | `.onnx` files | PyTorch weights |
| --- | --- | --- |
| `nllb-200-600m-int8` (works) | 2 — `decoder_model_merged_quantized.onnx`, `encoder_model_quantized.onnx` | — |
| `navoiy-tts` | **0** | `emotion_600h_joint.pt` |
| `mms-tts` | **0** | `model.safetensors`, `pytorch_model.bin` |

NLLB translates because its bucket prefix actually holds ONNX graphs. Neither TTS
prefix holds one, so there is nothing the bundled sherpa-onnx `OfflineTts` (VITS)
runtime could load. A `GET` on `tts/uzbek/navoiy/model.onnx` and
`tts/mms/rus/model.onnx` returns 403, so the objects are absent rather than merely
unlisted.

MMS is additionally licensed CC-BY-NC-4.0, so redistributing it needs an owner
decision independent of the hosting gap. Both modules stay registered as
`OfflineEngineKind.UNAVAILABLE` with a blocker string, so the settings card explains
the real reason and `download()` refuses instead of failing obscurely.

### Uzbek STT offline operation: VERIFIED (run 44598239880, commit da08080)

With wifi, mobile data and airplane mode disabled and `NETWORK_DOWN` confirmed,
`OfflineRealInferenceTest` passed offline: `OK (1 test)` in 4.6 s, against 91.8 s
for the online run. The model was already installed, so the faster time reflects
reuse rather than a weaker check: the same assertion on Uzbek word recall ran.

That job then failed, but not on a test. The multi-line `if` block around the
optional Whisper offline stage broke, because `android-emulator-runner` feeds the
script to `sh` one line at a time, producing `Syntax error: end of file unexpected
(expecting "fi")`. Every suite before it had already passed. `a67a49e` collapses
the block onto a single line and a scanner now checks all script lines for
multi-line control structures.

### Branch lineage vs the protected commit (important for regression claims)

`05429fee` is **not** an ancestor of this branch. The merge base is `9e0443d`
(2026-11-16), while the protected commit is from 2026-11-18, and
`origin/feature/offline-model-downloads-from-05429fee` carries 88 commits that are
not in this branch's history. A plain
`git diff 05429fee..HEAD` therefore reports 13 deletions and 6 modifications,
including files such as `.github/workflows/offline-model-safety.yml` and
`i18n/avnivo-dynamic-i18n.js`.

Those files never existed on this branch's lineage — they are absent at the merge
base `9e0443d` as well as at HEAD — so they are **not** deletions made here. They
appear in the diff only because the two histories are parallel.

The content still converged, which is what actually matters for the interface.
Comparing the shipped asset directly against the protected file:

| Comparison | Result |
| --- | --- |
| `assets/index.html` vs `05429fee:onboarding-tutorial-preview.html` | 220 insertions, 13 deletions |
| `assets/offline-model-installer.js` vs protected | +5 / -1 |
| `assets/offline-uzbek-download.js` vs protected | +1 / -1 |

All 13 HTML "deletions" are lines re-added in modified form, and both JS diffs are
purely additive error diagnostics around the ticket `fetch`. Feature counts line up
with the protected file: `offline-module` 70 vs 69, `i18n` 40 vs 40, `onboarding`
4 vs 4. The shipped HTML references only `offline-model-installer.js` and
`offline-uzbek-download.js`, and both are present, so the protected file's
standalone `i18n/avnivo-dynamic-i18n.js` is not a missing runtime dependency.

Conclusion: the UI is preserved, but the branch is not a descendant of the protected
commit. If a strict fast-forward from `05429fee` is required, this branch has to be
rebased or the integration replayed on top of it.

### Whisper Small offline re-run: VERIFIED (run 44605575156, commit a67a49e)

With wifi, mobile data and airplane mode disabled and `NETWORK_DOWN` confirmed, the
full `emulator-smoke` job passed end to end and printed `EMULATOR_SMOKE_OK`.

| Suite | Online | Offline |
| --- | --- | --- |
| `OfflineWhisperInferenceTest` (4 tests) | 1,211.1 s | **1,184.5 s** |
| `OfflineRealInferenceTest` (Uzbek STT, 1 test) | 91.8 s | **4.6 s** |

The Whisper time barely moves between the two runs, which is what real inference on
an already-downloaded model looks like: the decode dominates and there is no
download left to save. A skipped `assumeTrue` would have finished in milliseconds
instead. `transcribesKnownEnglishSpeechOffline` asserts non-empty text with at least
4 of 7 expected words, and `logcat` recorded **0** `UnknownHostException`,
`ConnectException` or `SocketTimeoutException` entries, so nothing tried to reach
the network.

### Final run totals (run 44605575156)

`OK (2)`, `OK (6)`, `OK (1)` mic gesture, `OK (4)` Uzbek real inference,
`OK (4)` Whisper runtime, `OK (4)` Whisper online, `OK (1)` Uzbek offline,
`OK (4)` Whisper offline. Unit tests: 47 total, 0 failures, 5 ignored.

## Phase 2 — TTS: the runtime was never the blocker

The previous checkpoint said both TTS modules had "0 .onnx files", which is true of
the copies under the project's own bucket but not of the upstream distribution. The
sherpa-onnx `tts-models` release publishes **MMS already exported to ONNX VITS**, and
the AAR this app already ships (`sherpa-onnx-2.0.1.aar`) contains the matching
Kotlin API: `OfflineTts`, `OfflineTtsConfig`, `OfflineTtsVitsModelConfig`
(`model`, `tokens`, `lexicon`, `dataDir`) and `OfflineTts.generate`.

So MMS needs no new native code. Evidence, run locally against
`sherpa-onnx` 2.0.2 with the same `OfflineTtsVitsModelConfig` contract:

| Property | Measured |
| --- | --- |
| Archive | `vits-mms-rus.tar.bz2`, 107,761,221 bytes |
| Contents | `model.onnx`, `tokens.txt` (80 pieces), `config.json` |
| Sample rate | 16,000 Hz |
| Load time | 0.49 s |
| "Привет, как дела?" | 1.23 s audio in 0.45 s (RTF 0.363) |
| "Спасибо" | 0.75 s audio in 0.23 s (RTF 0.311) |
| Output | peak 0.699, RMS 0.099 |

The output is speech rather than noise: 25 of 61 twenty-millisecond frames are
silent, and frame-energy standard deviation over mean is 1.06, which is the profile
of gated speech. Flat noise sits near 0.1.

### What this does not fix

Two limits remain, and they are real rather than engineering gaps:

1. **No Uzbek MMS voice exists.** The release ships deu, eng, fra, nan, spa, tha,
   ukr, rus. There is no `uzb`, so MMS cannot serve the Uzbek synthesis slot — only
   the multilingual one, and only for those eight languages.
2. **The weights are CC-BY-NC-4.0.** Downloading to a user's own device is fine;
   bundling or redistributing inside a commercial product is not. That is an owner
   licensing decision, not something an agent should paper over.

Navoiy TTS remains different in kind: `aisha-org/navoiy-tts` is CosyVoice2, a
flow-matching model with a separate vocoder and a PyTorch-only release. It is not a
VITS graph and cannot be dropped into `OfflineTtsVitsModelConfig`. Exporting it needs
the upstream PyTorch weights plus `torch.onnx.export` over both stages, which is not
possible from this environment (no GPU, no upstream weights, and the export is known
to be non-trivial for flow matching).

## Phase 3 — Whisper slowness: the flag never reached the compiler

Two attempts were needed, and the first one silently did nothing.

Attempt 1 passed `-O3` through Gradle's `cppFlags`. AGP forwards that as
`CMAKE_CXX_FLAGS`, which CMake emits *before* the per-config flags, so the Debug
build type's own setting still won. The cached `CMakeCache.txt` showed the result:
`CMAKE_C_FLAGS_DEBUG` is empty and the ggml compile line carried no `-O` flag at all,
meaning the compiler default `-O0`. The 110-minute emulator job confirmed it
empirically by running to its timeout instead of finishing.

Attempt 2 appends `-O3` to `CMAKE_{C,CXX}_FLAGS_<CONFIG>`, which places it after the
build-type flags. The generated ninja file now reads:

```
FLAGS = -g -DANDROID ... -fno-limit-debug-info  -O3 -fPIC
```

Because a `-O0` build still passes every functional test, only ~100x slower, the
workflow now greps `build.ninja` and fails on `GGML_NOT_OPTIMISED`. That guard is what
caught attempt 1's failure to take effect, and its own path bug in turn.

## Measured performance (2026-11-20, 2-core x86_64 emulator)

These are real figures from `WHISPER_PERF` and `AvnivoNllb` logcat lines, not estimates.
The emulator is much slower than a phone, so treat them as a floor.

| Engine | Input | Time | Real-time factor |
| --- | --- | --- | --- |
| Whisper Small Q8 | 11.0 s English speech | 92.1 s first, 91.9 s repeat | 8.37x |
| NLLB-200 600M INT8 | "Rahmat" (short) | 1.41 s online, 1.45 s offline | — |
| NLLB-200 600M INT8 | "Salom" | 1.45 s online, 1.45 s offline | — |
| NLLB-200 600M INT8 | "Salom, qalaysiz?" | 3.89 s online, 4.11 s offline | — |
| NLLB-200 600M INT8 | "Bugun havo juda issiq." | 4.44 s online, 4.50 s offline | — |

Online and offline NLLB figures come from the same commit and agree within noise, which is
the evidence that decoding never reaches the network. Whisper was measured separately.

Whisper download measured 44.9 s on the same run, timed separately from the load.

### NLLB thread count is measured, not guessed

`avnivo_nllb_jni.cpp` originally pinned ONNX Runtime to 2 intra-op threads. Replacing that
with `hardware_concurrency() - 1` regressed the 2-core emulator, because the expression
evaluates to 1 thread there:

| Decode threads | "Salom" | "Salom, qalaysiz?" | "Bugun havo juda issiq." |
| --- | --- | --- | --- |
| 2 (original fixed value) | 1282 ms | 4445 ms | 5484 ms |
| 1 (`hw - 1` on 2 cores) | 1581 ms | 4684 ms | 5402 ms |
| 2 (current floor of 2) | 1604 ms | 3200 ms | 3152 ms |

The floor is therefore 2, with the cap still 8 so the big cluster does not throttle.
The 3.2 s figures come from the same run as the 1.6 s figure and reflect a warmed process,
so they are not directly comparable to the cold first-run numbers in the first two rows.

### A skipped test used to report success

`reportsWhisperLatencyAndRepeats` guards itself with `assumeTrue`, so passing a wrong
instrumentation argument made the body skip while the suite still printed `OK`. One run
reported `Time: 0.001` and was accepted by CI as a passing measurement. The workflow now
requires the `WHISPER_PERF audio=` line itself instead of a bare `OK`.

## MADLAD-400 3B INT4 was removed (2026-11-21)

The user decided offline translation is NLLB-200 600M INT8 only, so MADLAD was taken out in
one revert commit, `bd25456`, rather than by rewriting published history.

Removed: `avnivo_madlad_jni.cpp`, its `libavnivo_madlad.so` CMake target, `MadladEngine.kt`,
the `MADLAD_ONNX` engine kind and the `madlad-400-3b-int4` spec, `MadladTokenizerTest.kt`, the
MADLAD entries in the inventory assertions, and `docs/madlad-400-int4-checkpoint.md`.

Nothing shared was touched. The working tree after the revert is byte-identical to `394ff3d`,
the commit before MADLAD work began (`git diff 394ff3d` is empty), which is a stronger
statement than a hand-written list of removals.

Verified on run `44874848035`:

| Check | Result |
| --- | --- |
| `testDebugUnitTest` | 52 tests, 0 failures |
| `assembleDebug` / release | BUILD SUCCESSFUL |
| `emulator-smoke` instrumented | OK, 21 tests |
| `libavnivo_madlad.so` in APK | absent |
| `libavnivo_nllb.so`, `libavnivo_whisper.so` | present, all 4 ABIs |
| `libsherpa-onnx-*` | present, 12 libs |

The `offline-translate-large` card keeps its existing markup. It was never wired to an
installer, so removing MADLAD required no HTML change at all.

## Session 2026-11-21 (continued): manual-send guard, Whisper Large offline, device harness

State at the start of this session: HEAD `b821dcb`, PR #28 open and draft, all earlier suites green.

### Manual-send contract is now enforced by a test

The agreed flow is speak -> text lands in the composer -> the user reviews it -> the user presses
the arrow. Nothing enforced that. `tests/manual-send.test.cjs` reads the shipped asset and fails if
`recognition.onend` or `recognition.onresult` ever grows a call into `sendMessage`,
`translateOfflineText`, `translateOnlineText` or `sendOnlineAudio`.

The test is meaningful, not decorative: appending `sendMessage()` to `recognition.onend` makes it
fail. That mutation was run and then reverted.

One correction worth recording. The first version asserted the send was reachable from a `click`
handler and failed. The real wiring is `keydown` (Enter), the arrow button, and a short mic tap in
`releaseHold`. The assertion was rewritten to the actual design instead of being loosened to pass.
A second test pins the part that matters most: `sendMessage()` must sit in the `else` branch of
`releaseHold`, so releasing the mic after dictation cannot post the transcript.

### Whisper Large V3 Turbo: real inference confirmed, offline repeat added

Run `44879495882`, job `large-model-smoke`, `OK (5 tests)` in 1,499.7 s. The suite is not a stub:
it downloads `whisper-large-v3-turbo`, requires `state == ready`, transcribes a real 11 s JFK clip
and demands at least 4 of 7 expected words.

That run only had the network up, so a green result could not separate real offline inference from
a cached download. The job now runs two passes: download/verify/init/infer, then the same suite with
wifi and data disabled and `ping 8.8.8.8` required to fail. `OfflineModuleManager.download()`
short-circuits to `INSTALLED` when the model is present, so pass 2 exercises initialization and
inference only. Both passes record `dumpsys meminfo`.

### Physical device

`scripts/run-device-verification.sh` runs the same suites on a connected ARM64 phone and writes
`instrument_*.txt`, `logcat_*.txt`, `meminfo.txt` and `device.txt` to `build/device-verification/`.
It is untested against hardware: this environment has no `adb` and no device. S23 Ultra verification
remains BLOCKED — DEVICE ACCESS REQUIRED.

### Silero TTS

Not present anywhere. No branch, no PR, no commit message, no file. Nothing to integrate and
nothing to overwrite. Blocked on the other agent delivering a branch.

## Whisper Large V3 Turbo: verified, including offline (run 44883724177)

The two-pass job now gives evidence that was missing before.

| Pass | Network | Result | Time |
| --- | --- | --- | --- |
| 1 | up | `OK (5 tests)` | 1,371.6 s |
| 2 | `NETWORK_DOWN` (wifi+data off, ping to 8.8.8.8 fails) | `OK (5 tests)` | 1,294.9 s |

Pass 2 being slightly faster than pass 1 rules out a hidden network round trip, and the suite
still requires `state == ready`, a real 11 s JFK clip, and >= 4 of 7 expected words. A download-only
"pass" is impossible because the assertions run after `transcribe()` returns.

Run 44882509477 failed at 1m51s first. Root cause was mine, not the model's:
`android-emulator-runner` executes each script line as its own shell, so the backslash continuations
were handed to `am` as a literal argument and it printed its help text. Fixed by keeping each
command on one line.

`dumpsys meminfo` produced empty files because it runs after the instrumentation process exits.
The test now logs `WHISPER_LARGE_MEM` from inside the process while the model is resident.

## NLLB-200 and Whisper Small verified offline (run 44889828215)

Both had offline passes wired but neither had ever executed. Both pass now.

### NLLB-200 600M INT8 (`nllb-smoke`, first ever run)

`OK (6 tests)` online in 160.7 s, then `NETWORK_DOWN`, then `OK (6 tests)` in 28.2 s.

The six tests match the six `@Test` methods exactly, and no `assumeTrue` skip appears in the
instrumentation output. The run is fast because the 916 MB model download is cached on the AVD and
ONNX Runtime is quick on a warm session, not because work was skipped.

Real translation captured from logcat, identical in both passes:

```
Salom=1468ms, Salom, qalaysiz?=4066ms, Bugun havo juda issiq.=4485ms, Rahmat=1402ms
results: rus_Cyrl Привет, как вы? / eng_Latn It's very hot today. / rus_Cyrl Спасибо
```

Cyrillic for Russian and Latin for English, three different source sentences, per-phrase latency.
The offline logcat contains zero `UnknownHost`, `ConnectException` or `SocketTimeout` entries, so
nothing silently fell back to a remote service.

### Whisper Small (`emulator-smoke` with `run_whisper_offline_verification`)

| Phase | Result |
| --- | --- |
| online inference | `OK (5 tests)` 136.2 s |
| online perf | `OK (1 test)` 183.2 s |
| radios off | `NETWORK_DOWN` |
| offline inference | `OK (5 tests)` 109.2 s |
| offline perf | `OK (1 test)` 182.3 s |
| marker | `WHISPER_OFFLINE_OK` |

`WHISPER_PERF audio=11.00s download=35.8s first=73.6s repeat=73.6s first_rt=6.69x repeat_rt=6.69x`
online versus `first=73.2s repeat=73.2s first_rt=6.66x` offline. Same numbers with the radios dead.

## Uzbek STT and Whisper Large: transcripts captured (runs 44893497484, 44888134955)

### Uzbek STT (Nemo CTC, 591 MB INT8)

The suite previously printed nothing, and a skipped test prints `OK` too, so the 3.4 s offline run
was not provably a real transcription. It now logs `UZBEK_STT_RESULT` and CI fails when the marker
is absent or empty. Captured transcript, byte-identical online and offline:

```
UZBEK_STT_RESULT text=navoiy tts o'zbekcha matnni tabiiy va ravon ovozga aylantiradi.
```

Seven of seven expected words. Timing confirms the shape of the work: 53.0 s online (includes the
591 MB download) versus 3.4 s offline (model reused), both followed by a matching transcript.

### Whisper Large V3 Turbo memory

Measured from inside the process, both passes:

```
WHISPER_LARGE_MEM heap_used_mb=4 native_alloc_mb=1246 max_heap_mb=192 recall=7/7
```

1,246 MB of native allocation against the 1,435 MB `requiredRamBytes` guard, so the guard has ~190 MB
of headroom over observed use and refuses to start below what is actually needed. Recall is 7/7, the
full expected set, and the Java heap stays near zero because the weights live in native memory. Same
figures with the radios off, so no fallback path was involved.

## Checkpoint 2026-11-21 — Silero Uzbek TTS integration

Stage: implementation complete and compiling; hosted model delivery outstanding.

### Completed this stage

- `SileroTtsEngine` loads the TorchScript Lite graph via `pytorch_android_lite:2.1.0` and returns
  48 kHz mono PCM. The graph's second output is per-token durations, not a sample rate.
- `SileroTtsPlayer` plays through `AudioTrack` with cancellation and replay.
- READY requires files, integrity, a loaded runtime and a smoke synthesis that must be audible.
- Spec pins `silero_v21.ptl` = 91,988,758 bytes,
  SHA-256 `333128fb6c7476e7fceec562f3f54980cbb6a88bbe59ff4a8e67e6cddad7a861`.
- Only the existing manual playback action is wired; no UI, layout, or unrelated behaviour changed.
- MIT licence chain verified from upstream sources, including that `uzb_saida` is speaker 46 inside
  the published artifact (`docs/silero-tts-android-feasibility.md` §8).

### Verified tests

| Suite | Result |
| --- | --- |
| JVM unit tests (run 44911367132, job apk) | **59 tests, 0 failures, 5 ignored** |
| New Silero JVM tests | 4 passed: MIT/runnable spec, fail-closed init, synthesis refused before READY, non-synthesizer rejected |
| UI preservation | 5/5 |
| Release build + signing + in-place update | passed (job update-install) |
| On-device Silero synthesis | **FAILED — model unreachable** (§9 of the feasibility doc) |

### Remaining failures and blockers

1. Deployed Cloud Run revision returns `404 {"detail":"Unknown offline model"}` for `silero-tts`.
   The manifest is on this branch but the running service predates it.
2. The artifact is not in the bucket, so no signed URL can be produced.
3. `publish-silero-tts-model.yml` cannot be dispatched: GitHub requires the workflow file to exist
   on the default branch. Publishing needs it merged to `main`, or a manual export run.

### Last successful commit

`a96dbaf` — CI green on JVM tests, APK build, signing and in-place update.

### Next exact action

Run `publish-silero-tts-model.yml` with `publish: true` (needs the file on `main`), then redeploy
the API, then re-run the emulator job. Until then this module stays
**IMPLEMENTED — DEVICE TEST PENDING** and must not be reported as working.

## Checkpoint 2026-11-21 (later) — runtime proven, blockers narrowed

### Completed since the previous checkpoint

- On-device Silero synthesis is now **verified**, not pending. Job `tts-seed-smoke` (run
  44914520977) exports the graph from the pinned upstream package, refuses to continue unless the
  export reproduces the pinned SHA-256, pushes it into app-private storage and synthesizes through
  the real init path. Measured: `seed init+synthesis=2512ms samples=102000 seconds=2.125
  peak=0.7822921`, `OK (1 test)`. This proves the engine and the READY gate; it does not prove the
  downloader, the ticket or the checksum gate on a real transfer.
- The hosting question is settled by the read-only bucket audit (run 44913265881) rather than by the
  ambiguous 403: 124 objects verified, 111 under `tts/`, **none Silero** (88 `tts/mms`, 23
  `tts/uzbek/navoiy`). The artifact is genuinely absent.
- Two CI assertion bugs were fixed. The Silero logcat checks could never match because logcat writes
  `TAG: message`, so all three (including the two guarding the download path) were dead assertions.
- The emulator job no longer exits on the TTS download failure, so the radios-off section now runs
  and offline Uzbek STT is verified in the same run. The download result is still fatal at the end,
  so a real regression cannot pass silently.

### Verified test results

| Check | Result |
| --- | --- |
| JVM unit suite | passed |
| Uzbek STT real inference (56 s) | passed |
| Whisper Small runtime + inference (5 tests, 134 s) | passed |
| Whisper latency/repeat perf | passed |
| Mic gesture suite (6 tests) | passed |
| Module state machine (2 tests) | passed |
| Seeded Silero synthesis on device | passed (2.125 s audio) |
| Silero download path | **FAILED — artifact not hosted** |

### Remaining blockers (unchanged in substance)

1. The deployed API does not list `silero-tts`, so the ticket call returns 404.
2. The artifact is absent from the bucket (proven by manifest, not by 403).
3. `publish-silero-tts-model.yml` cannot be dispatched from this branch; GitHub only exposes
   `workflow_dispatch` for workflows already on the default branch.

### Last successful commit

`integrate/offline-engines-20261118` — see `git log -1` for the SHA; every commit is pushed and
green on build, signing and update-install.

### Next exact action

Publish the artifact (merge the publish workflow to `main`, or run the same pinned export with GCP
credentials), redeploy the API, then re-run the emulator job so the download test can replace the
seed-based evidence. Until then the module stays **IMPLEMENTED — DEVICE TEST PENDING**.

## Checkpoint 2026-11-21 (offline evidence captured) — run 44917749217

### What this run proves

The emulator job now reaches the radios-off section and captures independent offline evidence.
Verbatim markers from the job log of run 44919840041:

```console
NETWORK_DOWN
I AvnivoUzbekStt: UZBEK_STT_RESULT text=navoiy tts o'zbekcha matnni tabiiy va ravon ovozga aylantiradi.
I AvnivoSileroTts: seed init+synthesis=4393ms samples=102000 seconds=2.125 peak=0.7822921
SILERO_TTS_OFFLINE_OK
OFFLINE_INFERENCE_OK
EMULATOR_SMOKE_OK
SILERO_TTS_DOWNLOAD_STILL_FAILING
```

So with the radios down, Uzbek STT transcribed real Uzbek speech and the Silero engine synthesized
2.125 s of audio. The job still fails, and the only failing gate is the download gate — which is
correct, because the artifact is not hosted. Nothing else regressed.

The synthesis timing is reported, not guaranteed: the same one-sentence seed took 2512 ms in run
44914520977 and 4393 ms here, so treat it as cold-start cost on a shared emulator rather than a
benchmark. The audio itself is identical across runs (`samples=102000 seconds=2.125
peak=0.7822921`), which is the part that matters for correctness.

### Test totals for this run

| Suite | Result |
| --- | --- |
| OfflineEngineInstrumentedTest | OK (2 tests) |
| OfflineMicGestureInstrumentedTest | OK (6 tests) |
| OfflineRealInferenceTest (network up) | OK (1 test) |
| OfflineRealInferenceTest (network down) | OK (1 test) |
| OfflineWhisperInferenceTest runtime | OK (5 tests) |
| OfflineWhisperInferenceTest inference | OK (5 tests) |
| OfflineWhisperInferenceTest perf | OK (1 test) |
| OfflineSileroTtsInferenceTest seeded, network down | OK (1 test) |
| OfflineSileroTtsInferenceTest download | FAILED (2 of 6) — artifact not hosted |

### Two CI defects fixed in this checkpoint

1. The runner executes each script line in its own `sh -c`, so the multi-line `if/fi` I added died
   with `end of file unexpected`. It is now one line, and every line is checked to parse alone.
2. For the same reason a shell variable could not carry the download result to the final gate; it
   now uses a marker file. Without this the gate would have passed or failed for the wrong reason.

### Next exact action

Unchanged: host the artifact and redeploy the API. Then the download gate becomes the last green
check and the module can move from IMPLEMENTED — DEVICE TEST PENDING to VERIFIED WORKING.

## Checkpoint 2026-11-21 (APK size regression fixed) — run 44919840041

### The regression and its cause

Adding `pytorch_android_lite` for Silero pushed the universal release APK to **382,748,153 bytes**.
Measured from the APK itself, not estimated:

```console
lib/x86          103.4 MB
lib/x86_64        99.1 MB
lib/arm64-v8a     91.2 MB
lib/armeabi-v7a   69.0 MB
```

AGP stores `.so` files uncompressed, so 362.7 MB of native code was written to the archive verbatim.
`libpytorch_jni_lite.so` alone is 57.6 MB on arm64-v8a, and it was shipped four times.

### The fix

`packaging { jniLibs { useLegacyPackaging = true } }` in `app/build.gradle.kts`. Recompressing the
same 362.7 MB with zlib yields 116.1 MB, so the archive shrinks without removing anything.

### Measured result

| | Bytes | Note |
| --- | --- | --- |
| Before | 382,748,153 | all `.so` STORED |
| After | 123,683,785 | all `.so` DEFLATED (121,739,649 B compressed) |

All four ABIs are still packaged: x86 31.6 MB, x86_64 31.1 MB, arm64-v8a 27.3 MB, armeabi-v7a
26.1 MB. No engine, architecture or asset was dropped to achieve this.

`update-install` passed on the compressed build, so the packaging change does not affect signing,
install or in-place upgrade.

### Offline inference still passes on the compressed build

The same run repeated the radios-off checks, so the fix is not merely cosmetic:

```console
NETWORK_DOWN
I AvnivoUzbekStt: UZBEK_STT_RESULT text=navoiy tts o'zbekcha matnni tabiiy va ravon ovozga aylantiradi.
I AvnivoSileroTts: seed init+synthesis=4393ms samples=102000 seconds=2.125 peak=0.7822921
SILERO_TTS_OFFLINE_OK
OFFLINE_INFERENCE_OK
EMULATOR_SMOKE_OK
SILERO_TTS_DOWNLOAD_STILL_FAILING
```

The last line is the only failing gate and it is the known hosting blocker, unchanged.

## Checkpoint 2026-11-21 (NLLB and Whisper Large verified) — run 44921366482

Both long-form jobs had been skipped in every earlier run. They were dispatched explicitly here.

### Whisper Large V3 Turbo — `large-model-smoke` success

The suite ran twice, once with radios up and once with them down, 5 tests each.

```console
OK (5 tests)                                        # online
I AvnivoWhisperLarge: WHISPER_LARGE_MEM heap_used_mb=4 native_alloc_mb=1246 max_heap_mb=192 recall=7/7
NETWORK_DOWN
OK (5 tests)                                        # offline
I AvnivoWhisperLarge: WHISPER_LARGE_MEM heap_used_mb=3 native_alloc_mb=1246 max_heap_mb=192 recall=7/7
WHISPER_LARGE_OK
```

`recall=7/7` means every expected phrase was recovered from the audio, not that the model merely
loaded. Native allocation is 1246 MB and identical offline, so the run is bounded and repeatable.
The job runs the emulator with `-memory 4096`. The app's own guard for this module is
`requiredRamBytes = 1_435_000_000` (2.5x the 574 MB quantized weights), so a device below that
refuses to load instead of risking an OOM kill. The measured 1246 MB native allocation is
consistent with that guard.

### NLLB-200 — `nllb-smoke` success

Six tests passed twice, online and with the network cut.

```console
com.avnivo.translator.OfflineNllbInferenceTest:......   # online
OK (6 tests)
NETWORK_DOWN
com.avnivo.translator.OfflineNllbInferenceTest:......
OK (6 tests)
NLLB_OFFLINE_OK
NLLB_OK
```

What those assertions require, from `OfflineNllbInferenceTest`:

- `libavnivo_nllb` loads on the emulator ABI.
- `prepare()` fails closed when models are absent.
- A Russian translation is non-blank and contains actual Cyrillic (`\u0400..\u04FF`), so an empty
  or echoed string cannot pass.
- The English translation is non-blank and checked against its own expectation.
- Repeating a translation returns a non-blank result.
- Latency is measured and non-empty, and `close()` is safe and idempotent.

The network was confirmed unreachable (`NETWORK_DOWN`) before the second run, so these translations
did not come from a remote service.

### Note on what is not claimed

The NLLB test logs no per-string transcript, so this checkpoint does not quote specific
translation strings. The assertions above are what the passing tests actually enforce.

## Checkpoint 2026-11-21 (skipped tests eliminated) — run 44928380510

The JVM suite reported 59 tests with 5 ignored. Ignored is not passed: the five
`NllbTokenizerTest` cases that pin token ids called `assumeTrue` and skipped whenever
`AVNIVO_NLLB_MODEL_DIR` was unset, which it always was in CI.

They now run. The `apk` job fetches `tokenizer.json` from the app's own ticket endpoint and
asserts its SHA-256 before Gradle starts.

The public mirror was the obvious shortcut and it is wrong: Hugging Face's copy of that filename
is 17,331,176 bytes and hashes to `e316b82d...`, while the pinned ids were produced from the
`8ac789ad...` artifact of 17,331,224 bytes. Testing against the mirror would have compared the
tokenizer to vectors from a different file.

Verified locally against the fetched artifact before committing:

```console
10/10 bodies match the pinned ids
uzn_Latn -> 256191 (test expects 256191)
rus_Cyrl -> 256147 (expects 256147)
eng_Latn -> 256047 (expects 256047)
'uzb_Cyrl' -> None   'xx_YY' -> None   'uznLatn' -> None
unk id 3   encode(PUA) -> [256047, 248059, 3, 2]
```

Note the engine's Uzbek code is `uzn_Latn` (Northern Uzbek), not `uzb_Latn`; the latter is absent
from this vocabulary, which is exactly the mismatch the language-mapping test exists to catch.

Report after the change, parsed from the uploaded JUnit HTML:

| | tests | failures | ignored |
| --- | --- | --- | --- |
| Before | 59 | 0 | 5 |
| After | 59 | 0 | 0 |

Duration went from 0.161s to 4.334s, consistent with the five tests doing real work instead of
returning early.

## Checkpoint 2026-11-21 (the 971-deletion scare was a bad comparison)

An earlier diff against the "protected original" `05429fee` reported 971 deletions and a modified
`index.html`, which looked like the UI had been damaged. It had not. The comparison was invalid.

`05429fee` is not an ancestor of this branch. It exists only on
`origin/feature/offline-model-downloads-from-05429fee` and `origin/feat/offline-model-safe-installer-20261117`,
never on `main`. The merge base of `05429fee` and HEAD is `9e0443d`, so a two-dot diff between them
shows that side branch's work as though this branch had deleted it. The deletions were never ours.

The clone was also shallow (`grafted`), so `git merge-base` returned nothing at all until
`git fetch --unshallow`. Measuring from an unresolvable base is what produced the false alarm.

The true baseline is `origin/main` at `83f720b`, which *is* an ancestor. Against it:

```console
$ git diff --stat origin/main..HEAD | tail -1
 59 files changed, 20488 insertions(+), 74 deletions(-)

$ git diff --name-status origin/main..HEAD -- index.html assets/ res/ onboarding-preview.html i18n/
(no output)
```

So no web asset, stylesheet, onboarding page or i18n dictionary changed. The 74 deletions are
inside files this work owns, and the largest block is the asset the APK actually loads.

### The APK loads a different index.html

`android-app/app/src/main/assets/index.html` is the interface the app ships (+211 −27). The root
`index.html` is not packaged. Checking only the root file would have missed the real interface.

Those 27 lines are rewrites, not removals. Each has a replacement on the added side:

| Removed line | Replacement |
| --- | --- |
| `modeOnline`/`modeOffline` click handlers | re-added with the same `applyMode`, `stopVoice`, `closeOnlineLanguages` calls |
| module card `offline-tts-navoiy` pointing at `navoiy-tts` | same card id, now `installerId:'silero-tts'`, 91,988,758 bytes |
| Whisper cards 264,646,607 / 574,000,000 bytes | same cards with exact sizes 264,464,607 / 574,041,195 |
| `pointercancel`, `pagehide`, `hint()`, `persistDraft();resize();syncComposerMode()` | present once or twice on the added side |

Verified by test rather than by eye. `tests/offline-ui-preservation.test.cjs` reads the shipped
asset and passes 5/5: no original CSS declaration, element id or class name is removed, and all
seven offline modules stay wired. `tests/manual-send.test.cjs` also reads the shipped asset and
passes 5/5, so recognition still cannot reach `sendMessage`, `translateOfflineText`,
`translateOnlineText` or `sendOnlineAudio` without a user gesture.

## Checkpoint 2026-11-21 (session recovery, Silero provenance, diagnostics) — run 44938053262

Resumed from HEAD `eabfbe3`. Nothing was reset, no branch or PR was recreated, and the earlier
commits were kept.

### The Silero blocker is credentials, not code

The pinned artifact is real and reproducible. Downloaded from the `avnivo-tts-seed-evidence`
artifact of run 44930578157:

```console
$ sha256sum silero_v21.ptl
333128fb6c7476e7fceec562f3f54980cbb6a88bbe59ff4a8e67e6cddad7a861  silero_v21.ptl
$ stat -c%s silero_v21.ptl
91988758
```

Both values match `OfflineModelSpecs.kt` and `translator-api/verified_models/silero-tts.json`
exactly, so spec, manifest and bytes agree. On-device synthesis is proven independently of the
download: `OfflineSileroTtsInferenceTest` reports `OK (1 test)` in 9.5 s, and the logcat line is
`seed init+synthesis=4393ms samples=102000 seconds=2.125 peak=0.7822921`.

What is missing is distribution. `download-ticket` needs GCS write access plus
`AVNIVO_SIGNER_SERVICE_ACCOUNT` to mint a v4 signed URL, and this environment has no
`GOOGLE_APPLICATION_CREDENTIALS`, no `gcloud`, no `gsutil`. The deployed Cloud Run service returns
`404` for `silero-tts`, and the Render staging API (`avnivo-translator-api.onrender.com`) returns
`404` for every `/api/offline-models/...` route because it predates them. So the artifact cannot be
published from here. That is the exact blocker, and it is why `emulator-smoke` still fails at
`SILERO_TTS_DOWNLOAD_STILL_FAILING`.

### Licence re-verified against upstream, not against our notes

Cloned `snakers4/silero-models` and checked directly:

```console
$ head -1 LICENSE_CIS
MIT License
$ head -1 LICENSE
Attribution-NonCommercial-ShareAlike 4.0 International
$ sed -n '138,143p' README.md
#### V5 CIS Base Models
...
- All of the below models are published under `MIT` [licence](.../LICENSE_CIS);
$ sed -n '166p' README.md
| `v5_cis_base`, `v5_cis_base_nostress` | `uzb_saida` | `uzb` (Uzbek) | ... |
```

The MIT grant covers both `v5_cis_base` and the `uzb_saida` voice. The noncommercial `dilnavoz`
weights are not used anywhere in the shipped app.

### Module errors now say why

`status()` used to return `state` and nothing else, so a device test could only see
`{"id":"silero-tts","state":"error","percent":0,"active":false}` — indistinguishable between an
unpublished model, a bad checksum and a dead interpreter. `status()` now carries a `reason` key
sourced from the downloader's own message (for example `Model serveri HTTP 404`) and falls back to
the spec's `blocker` for modules with no engine. `OfflineModuleManagerTest.engineFailureNeverReportsReady`
asserts the reason reaches the payload. The WebView reads only `state` and `progress`, so the
interface is unaffected.

### Test totals, run 44938053262

| Suite | Result |
| --- | --- |
| JVM unit tests | 59 tests, 0 failures, 0 ignored |
| `apk` job (17 steps) | all pass, including "Assert the original interface was not altered" |
| `update-install` | success |
| Offline routing | 11/11 |
| SHA-256 integrity | pass |
| Preservation / manual-send | 5/5 and 5/5 |
| `emulator-smoke` | failure, one gate only: `SILERO_TTS_DOWNLOAD_STILL_FAILING` |

The new `reason` field is what the failing run now reports, which is the point of adding it:

```console
reason":"Model serveri HTTP 404: {"detail":"Unknown offline model"}
```

Before this change the same run could only say `state":"error"`. The message names the cause: the
deployed API does not carry a `silero-tts` manifest. Everything else in the job is green —
`UZBEK_STT_RESULT` with `NETWORK_DOWN`, `SILERO_TTS_OFFLINE_OK`, `WHISPER_OFFLINE_OK`,
`OFFLINE_INFERENCE_OK`, `EMULATOR_SMOKE_OK` — so the single failing gate is distribution, not
inference.

## Language synchronisation and offline module gating (commit `e157fc2`)

The offline picker had a hardcoded Uzbek→Russian pair, so it could offer a language the
downloaded tokenizer does not carry, and it read `ready` from a state that could be set before
verification. Fixed at the source of truth, not in the UI.

| Item | Status |
| --- | --- |
| `NllbTokenizer.languageCodes` read from the loaded vocabulary | done |
| `NllbEngine.supportedLanguages()` | done |
| `OfflineModuleManager.supportedLanguages(id)` gated on `isOperational` and engine type | done |
| `MainActivity.offlineTranslationState()` bridge (`ready` + `languages` + `status`) | done |
| Separate persisted pair per mode (`avnivo.offline.language-pair.v1`) | done |
| Bidirectional mode transfer, incompatible side left blank | done |
| Both sides must resolve before offline send | done |
| Long-press timing unchanged at 1100 ms | verified |
| `route.test.mjs` anchor repaired (red since `500b5bb`, pre-existing) | done |

### Test totals, run 44994754881

| Suite | Result |
| --- | --- |
| `NllbTokenizerTest` (real tokenizer, not skipped) | 14 tests, 0 failures, 0 ignored |
| `OfflineModuleManagerTest` | 19 tests, 0 failures |
| `offline-language-sync.test.mjs` (new) | 19 pass, 0 fail |
| `offline-translation/route.test.mjs` | 11 pass, 0 fail |
| `apk` job | success, APK built |
| `update-install` | success |
| `emulator-smoke` | failure, one gate only: `SILERO_TTS_DOWNLOAD_STILL_FAILING` |

The single failing gate is the same distribution blocker recorded above: the deployed API returns
`HTTP 404 {"detail":"Unknown offline model"}` for `silero-tts`. It reproduces on `18ace66`, before
this branch's changes, so it is not a regression. Every other instrumented check passed in this run:
`UZBEK_STT_RESULT text=navoiy tts o'zbekcha matnni tabiiy va ravon ovozga aylantiradi.` with
`NETWORK_DOWN`, `OFFLINE_INFERENCE_OK`, Whisper runtime/inference/perf, and the mic gesture suite.

### Next exact action

Publish the `silero-tts` model manifest and artifact on the model server, then re-run
`avnivo-integrated-android.yml`. Nothing in the app needs to change for that gate to pass.

---

## Silero TTS distribution blocker — resolved

The blocker above had two layers, and both are now closed. No application source changed: the fix
was entirely in the API deploy pipeline and the model bucket.

### Layer 1 — the deployed API did not know the model id

`silero-tts.json` and its `ALLOWED_PREFIXES` entry existed only on this branch, so the running Cloud
Run revision answered `404 Unknown offline model`. Dispatching the already-registered
`deploy-verified-translator-api.yml` at this branch (`gh workflow run ... --ref
integrate/offline-engines-20261118`) rebuilt the service from the branch source, because
`workflow_dispatch` uses the selected ref. Run `45004060884`, `success`.

### Layer 2 — the object was never uploaded

The manifest then returned `200`, but the signed URL ended in `NoSuchKey`, which is the honest
signal: a ticket only proves the API can sign a name, not that the name exists. The deploy workflow
now takes a `publish_silero_model` input that exports the graph from the pinned upstream package,
refuses to continue unless the export reproduces the pinned digest, uploads it, and checks the
stored object size before the API deploy runs. Run `45004368418`, job `publish-silero-model`,
`success`:

```text
samples=32400 seconds=0.675 peak=0.625
Copying file://silero_v21.ptl to gs://avnivo-offline-models-792624108019/tts/uzbek/silero/silero_v21.ptl
tts/uzbek/silero/silero_v21.ptl 91988758        oi1eby6uw+s/EoV2gdT0NQ==
```

That run's `deploy` job failed on `HTTP 429 Too Many Requests`, caused by the manual ticket probes
in this session hitting the endpoint's rate limit — not by the artifact. Confirmed independently
afterwards against the live endpoint:

```console
$ curl -s .../api/offline-models/silero-tts/download-ticket    -> HTTP 200
$ curl -s -o dl.bin -w '%{http_code} %{size_download}' <signed url>
200 91988758
$ sha256sum dl.bin
333128fb6c7476e7fceec562f3f54980cbb6a88bbe59ff4a8e67e6cddad7a861
```

The digest matches `verified_models/silero-tts.json` exactly, so the checksum gate the app applies
would accept these bytes.

### Emulator evidence, run 45004732384 (`success`, HEAD `c82c78e`)

| Job | Result |
| --- | --- |
| `apk` | success |
| `update-install` | success |
| `emulator-smoke` | **success** (first green run; previously failed on `SILERO_TTS_FAILED`) |

Instrumented suites: 2, 6, 1, 5, 5, 1, 6 tests — all `OK`, 26 tests total, 0 failures. The real
download path ran end to end on the emulator:

```text
I AvnivoSileroTts: download+init=9455ms samples=102000 seconds=2.125 peak=0.7822921
```

`download+init=9455ms` is the download of the real 92 MB artifact through the signed ticket,
followed by SHA-256 verification, TorchScript Lite load and a synthesis smoke test; the module
reached `ready` only after all of it, so the READY gate is not bypassed. 2.125 s of 48 kHz Uzbek
audio with a non-silent peak is real voice output, not a stub buffer.

### Remaining gap and what closed it

In that run the radios were cut only *after* the Silero download test, and the offline TTS step
still seeded a CI-exported copy, so it proved the engine needs no network but not that the
*downloaded* bytes are the ones reused offline. `downloadedModelSynthesizesWithNoNetwork` now
re-runs the module on the artifact the download test installed, with wifi and airplane mode
enabled, and asserts the reuse took under 20 s so a silent re-fetch cannot pass as reuse.

### Classification

`silero-tts`: **VERIFIED WORKING** on the emulator (x86_64, API 34) — download, integrity, runtime
init, real inference, and offline inference are all measured. Two limits remain, and neither is a
claim of completeness:

- **No physical device.** No arm64 phone or Galaxy S23 Ultra was available. The 466 MB peak RSS
  measured on x86_64 is above a comfortable budget for low-memory hardware, and the arm64 figure is
  unmeasured. Device-specific verification is therefore still outstanding.
- **The publish job lives in the deploy workflow, not a standalone one.** It is guarded by a pinned
  digest and an explicit input, so it cannot run by accident, but it does re-export the graph on
  every publish rather than reusing a cached artifact.

---

## Online microphone regression — root cause and fix

Reported on the installed APK: pressing the microphone produced no permission dialog, no OS
microphone indicator, no waveform, no processing animation, and no transcript.

### One cause behind all five symptoms

`onPermissionRequest` was added for the offline bridge and answered every WebView capture request
with `request.deny()` whenever `RECORD_AUDIO` was not already granted. It never asked Android for
the permission. The protected original had no `WebChromeClient` at all, so WebView had never been
able to capture audio in this app. With no grant, `getUserMedia` rejected, so the stream never
existed and the waveform, indicator and Chirp 2 request could not start.

The failure was invisible for a second reason: `hint()` returned early in online mode, and
`startVoice`'s catch block only wrote `console.warn`. Nothing reached the user.

### Changed files

| File | Change |
| --- | --- |
| `MicPermissionPolicy.kt` (new) | Pure-Kotlin decision: bundled origin only, audio resource only, never a widened grant. |
| `MainActivity.kt` | `onPermissionRequest` delegates to the policy, requests the missing permission, keeps the pending request and answers it in `onRequestPermissionsResult`; reports a blocked permission with the settings path. |
| `assets/index.html` | `hint()` no longer swallows online messages; `startVoice` names `NotAllowedError` / `NotFoundError` / `NotReadableError` and restores the gesture state; Chirp 2 failures are shown; `AvnivoOnlineMicError` / `AvnivoOnlineMicGranted` bridge callbacks added. |
| `MicPermissionPolicyTest.kt` (new) | 8 JVM tests over the grant rules. |
| `OnlineMicInstrumentedTest.kt` (new) | 9 emulator tests: manifest permission, four indicator states, visibility, denial reporting, hint visibility, waveform amplitude from real samples, composer insert without send, offline bridge intact, live capture. |
| `tests/online-mic-permission.test.cjs` (new) | 14 assertions pinning the native gate and the JS error reporting. |
| `avnivo-integrated-android.yml` | Runs the new JS suite, and on the emulator exercises grant, revoke and capture with a required `ONLINE_MIC_CAPTURE_OK` marker. |

No colour, layout, control, icon or animation was changed. The waveform still reads
`getByteTimeDomainData` RMS, and the four indicator states are unchanged.

### Verified result

Run `45014803249` (commit `cbb4034`) is green: `apk`, `update-install` and `emulator-smoke` all
succeeded.

| Check | Result |
| --- | --- |
| JS suites | 55 pass, 0 fail (14 new in `online-mic-permission.test.cjs`) |
| JVM suites | 8 new in `MicPermissionPolicyTest` |
| Emulator: full online suite | `OK (8 tests)` |
| Emulator: denial path after `pm revoke` | `OK (1 test)` |
| Emulator: capture probe after `pm grant` | `OK (1 test)` |
| WebView asked for audio capture | `ONLINE_MIC_GRANTED_RESOURCES [android.webkit.resource.AUDIO_CAPTURE]` |
| Permission refusal | absent — no `NotAllowedError`, no `SecurityError` |

The capture probe reported `FAIL NotReadableError`. That is the emulator, not the permission:
the runner starts it with `-noaudio`, so no audio input device exists. The meaningful result is
the pair above it — the WebView reached the native gate asking for `AUDIO_CAPTURE`, and the gate
granted it. Before the fix that same request was denied before it ever reached Android.

Live capture with a real microphone therefore remains **IMPLEMENTED — DEVICE TEST PENDING** on
the Samsung Galaxy S23 Ultra; it cannot be closed on a headless emulator.

### Next exact action

Update PR #28 with this evidence. Remaining device-pending item: confirm live capture and a real
Chirp 2 transcript on the physical S23 Ultra.

---

## Stage 7 — one press, one transcript (regression fix)

Commit `HEAD` on `integrate/offline-engines-20261118`. Run `45038228895` is green
(`apk`, `update-install`, `emulator-smoke`).

### What was reported

1. The first Uzbek recording produced no transcript, or one that arrived too late.
2. The second press showed the first recording's text.
3. No processing indicator appeared between release and result.

### Root cause — one chain, not three bugs

| Link | Defect |
| --- | --- |
| Capture queued behind model work | `beginRecording` ran on `worker`, the same single-thread executor that downloads and initializes models, so a press waited for initialization and the release often arrived before the microphone opened. |
| Empty capture on a short press | The capture loop exited the moment `stopRequested` was set, so a release at sample 0 returned an empty array, which the page reported as "no speech". |
| No press identity | Results, amplitudes and errors carried no session, so a late result from press N landed in press N+1. That is the reported "second press shows the first text". |
| Release cleared everything | `releaseHold` called `finishOfflineHold()`, which removed every trace of the press before decoding had a chance to show a stage. |
| Recognizer rebuilt per press | `verifyEngine` created a new `OfflineSpeechEngine` each time and did not keep it, paying initialization on every press. |

### Fix

| File | Change |
| --- | --- |
| `MainActivity.kt` | `start(session)` / `stop(session)` adopt the page's session id; a release from an older press is ignored; capture runs on `captureWorker`, inference on `sttWorker`; `beginRecording` announces `AvnivoOfflineProcessing` before decoding; a finished capture clears only the state it still owns; the verified `uzEngine` is kept and reused; `onDestroy` closes the engines, players and executors. |
| `OfflinePcmRecorder.kt` | The loop condition is the pure `shouldContinue(stopRequested, sampleCount, limit)`, which keeps reading to `MIN_HOLD_SAMPLES` (0.3 s) after a release so a short first press still yields audio. |
| `assets/index.html` | Every press takes `++offlineSession` and passes it to the bridge; every `AvnivoOffline*` callback refuses a session that is not current; release enters the existing `loading` indicator instead of clearing the press; `syncComposerMode` and the greeting loop treat decoding as an active press; offline language chips show the stored pair before NLLB is installed and mark a pair unsupported only when a loaded tokenizer proves it. |
| `assets/index.html` (online) | `releaseOnlineCapture()` closes the previous stream, tracks and `AudioContext` before every press and on every failure; the raw-audio constraints (`echoCancellation:false`, `noiseSuppression:false`, `autoGainControl:false`) are replaced with `{audio:true}`, which is the documented cause of `NotReadableError` on devices that cannot deliver an unprocessed stream. |
| `tests/offline-stt-session.test.cjs` (new) | 14 assertions pinning the session contract on both sides of the bridge. |
| `tests/online-capture-lifecycle.test.cjs` (new) | 8 assertions pinning the capture teardown and the compatible constraints. |
| `tests/offline-ui-state.test.cjs` (new) | 8 assertions pinning chip persistence, greeting gating and the processing style. |
| `OfflinePcmRecorderHoldTest.kt` (new) | 6 JVM tests over the real `shouldContinue` condition. |
| `OfflineMicGestureInstrumentedTest.kt` | 3 new emulator tests: the first press produces its own transcript, a stale result is refused, processing is visible before the result. |

### Verified result

| Check | Result |
| --- | --- |
| JS suites | 85 pass, 0 fail |
| Emulator: mic gesture (incl. 3 new session tests) | `OK (9 tests)` |
| Emulator: online suite / denial / capture | `OK (8)` / `OK (1)` / `OK (1)` |
| Emulator: real offline inference, online and offline | `OK (1)` / `OK (1)` |
| Emulator: Whisper runtime and inference | `OK (5)` / `OK (5)` |
| Emulator: Silero TTS online and offline | `OK (7)` / `OK (1)` |
| Release APK | `update-v101.apk`, sha256 `14beaf965abbd58249a4f4a90cfcc703dbbe2b09264a10bebfd43f23aac5ba40` |
| Signing | `CN=AVNIVO Translator`, SHA-256 `12e7e26104c7a5110a849baec36d6916efe0b2494384749d4c733286aeeecb8e`, streamed install `Success` |

### Remaining limitation

The session contract, the hold floor and the capture teardown are verified by unit tests, by
real-WebView emulator tests and by a green build. The physical S23 Ultra is still the only place
that can confirm a spoken Uzbek sentence becomes text on the first press, because the emulator
has no microphone (`-noaudio`). Classification: **IMPLEMENTED — DEVICE TEST PENDING**.

### Final state of this stage

| Item | Value |
| --- | --- |
| Branch | `integrate/offline-engines-20261118` |
| Commit | `5153a40` (code at `774cc2a5c03437d4a02b06e918582251b5ebe281`) |
| Android workflow | run `45042815549` — `apk`, `update-install`, `emulator-smoke` all success |
| Release APK | `update-v101.apk`, sha256 `be61a8725311a7f3ab87718c76acddf1dcf1faf2d772ce7d7540fdbde2c4cd66` |
| Package | `com.avnivo.translator`, versionName `1.0.0`, versionCode `1238`, minSdk 26, targetSdk 35 |
| In-place update | `firstInstallTime` preserved (`2026-11-22 16:21:07`), versionCode `100` → `1238`, install `Success` |
| JS suites | 87 pass / 0 fail |
| Emulator total | 42 instrumented test executions, all `OK`: 9 (mic gesture) + 8 (online) + 7 (TTS) + 5 + 5 (Whisper) + 2 (engine) + 6×1 (single-test probes) |
| Offline re-verification | `svc wifi disable` + `svc data disable`, then real STT and TTS still `OK` |
| APK download | draft release `offline-integration-v1239` (release id `454592769`), asset `avnivo-offline-integration-v1239.apk` |

The release is a **draft** on purpose: it gives a one-file download instead of a ZIP artifact,
while keeping the "no production release without approval" rule intact.

### Next exact action

Install `update-v101.apk` on the S23 Ultra, make one short Uzbek recording, and confirm the text
appears without a second press.

---

## Stage: three reported S23 Ultra failures fixed

Three symptoms were reported from the physical device. Each turned out to be a contract bug
between two components that were each individually correct.

| Symptom | Root cause | Fix |
| --- | --- | --- |
| Uzbek STT: `Recognizer already initialized` | `verifyEngine()` re-called `prepare()` on the cached engine, and `prepare()` guarded itself with `check()`. | `prepare()` idempotent + `@Synchronized`; one shared `readyUzbekEngine()` accessor. |
| NLLB download frozen at 0% | Manager emitted on `AvnivoOfflineProgress`; the cards listen on `AvnivoOfflineModuleProgress`. Payload dropped. | Correct channel, plus byte progress inside `statusJson` so a restart restores the bar. |
| NLLB bar restarted at 0% | Progress was per file; NLLB has 8 files. | Aggregated across the whole model; verified files reused; verified `.download` temporaries promoted. |
| Whisper Large: "Tekshirilmoqda…" forever | `initialize()` caught `Exception`/`UnsatisfiedLinkError` only, so an `OutOfMemoryError` escaped and left `INITIALIZING` with no exit. | Catch `Throwable`, settle into `ERROR` with an actionable reason, release the leaked native handle, and refuse to load an unaffordable model at page load. |

Also made `NllbEngine.prepare()` and `SileroTtsEngine.prepare()` idempotent and synchronized, for
the same reason: a repeated call used to open a second ONNX session / LiteRT module and leak it.

### Verified results for this stage

| Check | Result |
| --- | --- |
| Node suites (8) | 69 pass / 0 fail |
| New `offline-module-progress.test.cjs` | 11 pass / 0 fail, wired into CI |
| JVM unit tests (CI, run `45054670635`) | 91 tests, green after 2 test-expectation fixes |
| NLLB real inference (run `45055769600`, job `nllb-smoke`) | `OK (6 tests)` online and `OK (6 tests)` with wifi + data disabled |
| NLLB translation output | `Salom=1429ms … results: rus_Cyrl Привет, как вы? / eng_Latn It's very hot today. / rus_Cyrl Спасибо` |
| Whisper Large V3 Turbo (job `large-model-smoke`) | `OK (5 tests)` online and `OK (5 tests)` offline; `native_alloc_mb=1246`, `recall=7/7` |
| Uzbek STT on emulator | `UZBEK_STT_RESULT text=navoiy tts o'zbekcha matnni tabiiy va ravon ovozga aylantiradi.` |
| Whisper Small perf | `audio=11.00s first=52.6s repeat=52.5s first_rt=4.78x` |
| Silero TTS | `samples=102000 seconds=2.125 peak=0.7822921`; offline reuse `samples=140400` |
| Interface preservation | `offline-ui-preservation` 5/5; index.html diff is additive only (1 CSS rule, 1 element, 1 handler guard) |

`recall=7/7` and `native_alloc_mb=1246` are the evidence that Whisper Large really ran: the model
was resident in native memory and reproduced all seven reference phrases, both with and without a
network. A skipped test reports `OK` too, so the workflow greps the measurement line itself.

### Final state of this stage

| Item | Value |
| --- | --- |
| Branch | `integrate/offline-engines-20261118` |
| Commit | `c60da37` (checkpoint doc updated at `8d2bfad`) |
| Workflow run (standard) | `45054670635` — `apk`, `update-install`, `emulator-smoke` success |
| Workflow run (opt-in heavy) | `45055769600` — `nllb-smoke` and `large-model-smoke` success |
| Release APK | `avnivo-offline-integration-v1247.apk`, 123,695,965 B, sha256 `db7a51d88fcaac3c4c22eb601e7000aca10ac51aa81e9f4b26c03cc5b8e877ad` |
| Package | `com.avnivo.translator`, versionName `1.0.0`, versionCode `1247`, minSdk 26, targetSdk 35 |
| In-place update | `firstInstallTime` preserved, versionCode `100` → `1247`, `Success` |
| Native libraries | 56 `.so` across `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` |
| Classification | Uzbek STT, Whisper Small, Whisper Large, NLLB-200, Silero TTS: **VERIFIED WORKING** on emulator. Physical S23 Ultra: **IMPLEMENTED — DEVICE TEST PENDING**. |

### Stage: device-report fix — memory gate and single-engine activation

The S23 Ultra reported five symptoms. They were one defect.

| Device report (v1247) | Cause | Fix |
| --- | --- | --- |
| `xotira yetarli emas: 2765 MB kerak, 1768 MB mavjud` | `activate()` compared `spec.requiredRamBytes` against `ActivityManager.availMem` (whole-system free RAM) | `DeviceMemory` budget from **physical** RAM; refuse only on real pressure |
| NLLB → «O'rnatish xatosi» | Same gate: 2765 > 1768 can never pass on a healthy phone | Same fix; `2765 MB` is NLLB's `requiredRamBytes` |
| STT / Whisper Large / Silero → «Yuklab olingan va tasdiqlangan · dvigatel ulanmagan» | `reactivateInstalled()` loaded *every* installed model at once; the later ones failed the memory check | One resident engine; `INSTALLED` is its own honest state |
| Modules stuck / not activating | `INSTALLED` → `READY` never happened after a failed load | `activate()` retries on demand; `ensureLoaded()` reloads an unloaded model |
| Emulator green, device red | Emulator has far more free RAM, so the gate never fired in CI | Regression test pins the exact 12 GB / 1768 MB / 2765 MB numbers |

`availMem` is system-wide free memory and Android deliberately keeps it low by filling unused RAM
with page cache, so gating a native allocation on it can never succeed. Native allocations (ONNX
Runtime, ggml, LiteRT) also live outside the Java heap, so the VM ceiling says nothing either.

| Item | Value |
| --- | --- |
| Commit | `c30974f` (fix at `ac3b55c`, picker fix at `c30974f`) |
| Unit tests | **115**, 0 failures (was 69) |
| New unit tests | `DeviceMemoryTest` (7), `ModelIntegrityTest` (8), manager lifecycle (6) |
| Node tests | **74**, 0 failures (was 69) |
| New node tests | `offline-installed-state.test.cjs` (5) |
| Instrumented | `OK (2)(9)(8)(1)(1)(1)(5)(5)(1)(7)` = 40 tests on API 34 x86_64 |
| Workflow run | `45069532330` — `apk`, `update-install`, `emulator-smoke` success |
| Interface diff | `index.html` +5/−5 lines: state map, two status maps, three i18n keys |
| In-place update | `firstInstallTime` preserved, versionCode incremented, `Success` |
| Release APK | 123,705,533 B, sha256 `012f7a6db87701cb220649133bdda8bdb9a7673a470ef9001096ce38b382ba89` |
| APK contents | 56 native `.so` across `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`; `assets/index.html` present |
| Signing | `CN=AVNIVO Translator`, cert SHA-256 `12e7e26104c7a5110a849baec36d6916efe0b2494384749d4c733286aeeecb8e` |

One regression was introduced by lazy loading and caught before delivery: the translation picker
asked whether NLLB was *operational*, and a released engine is not, so opening the language list
after another module took the memory would have shown nothing installed. Residency and
installation are different questions. Language queries now come from the model's own
`tokenizer.json` (17 MB) rather than the 916 MB ONNX sessions, so the picker keeps its real
language list while the engine is released, and the engine loads on the first translation.

The emulator pass is **not** treated as device evidence. The fix removes the gate that only fails on
a real phone, and the regression test reproduces the device's numbers, but the S23 Ultra run is
still required before any module is called verified on hardware.

### Next exact action

Install the v1248 build on the S23 Ultra, then confirm: NLLB activates instead of reporting
`xotira yetarli emas`; each installed card reads «O'rnatilgan · ishlatilganda yuklanadi» rather
than «dvigatel ulanmagan»; and a second module activates after the first is released. Capture
`adb logcat -s AvnivoMemory AvnivoOfflineModule*` for the real `total/avail/pss/budget` numbers.

## Stage: NLLB language-marker leak and TTS voice routing (S23 Ultra report)

The S23 Ultra reported that every NLLB translation began with a technical marker (`rus_Cyrl`) and
that a Russian message could be read with the Uzbek voice. Both were routing defects, not model
quality problems.

| Symptom | Root cause | Fix |
| --- | --- | --- |
| Translation starts with `rus_Cyrl` / `eng_Latn` | The JNI decoder forced the target language token into its **output** array. That token is decoder input, so it decoded to a literal marker. | `avnivo_nllb_jni.cpp` no longer pushes the step-0 forced token into `generated`; `NllbTokenizer.decode` also skips language-code pieces. |
| Russian read with the Uzbek voice | `playMessage` chose a voice from the message **side** (`right` meant `ru-RU`, `left` meant `uz-UZ`). With any other pair, or when Russian sat on the left, the Uzbek-only Silero engine was called for Russian. | Route by each message's own NLLB code. Silero is used only for `uzn_Latn`; other languages use a browser voice for their own locale, and a known voice list with no match refuses instead of speaking the wrong language. |
| A message's voice changed when the picker changed | The voice came from the current pair, not from the message. | `attachSpeaker` stamps `data-code`; `saveChatMessage` stores `sourceCode`/`targetCode`; history restore reuses them. |

The translated string is also sanitised in `index.html` before it reaches the bubble, so an older
native library or a future model revision cannot reintroduce the marker. Each `🔊` reads its own
bubble text: the source button reads the original, the translation button reads the translation.

### Verified results for this stage

| Check | Result |
| --- | --- |
| JVM unit tests | **116**, 0 failures, 0 ignored (was 115). `NllbTokenizerTest` ran 15 with the real tokenizer, including the new `decodeDropsLanguageTokensThatAreNotPartOfTheSentence`. |
| Node suites (12) | **107** pass / 0 fail. New `offline-tts-routing.test.mjs` (8) covers marker stripping, locale mapping, Uzbek-only native gating and per-message code stamping. |
| Interface preservation | `offline-ui-preservation` 5/5; `index.html` diff is additive: +75/−19 lines, all in the translation/TTS script, no CSS or element changes. |
| Instrumented (API 34 x86_64) | 42 tests across 12 suites, all `OK` (`OK (2)(9)(8)(1)(1)(1)(5)(5)(1)(7)(1)(1)`). |
| Offline modules on emulator | `UZBEK_STT_RESULT text=navoiy tts o'zbekcha matnni tabiiy va ravon ovozga aylantiradi.`; `AvnivoSileroTts: offline reuse=596ms samples=140400 seconds=2.925 peak=0.5980808`; `SILERO_TTS_OFFLINE_OK`, `OFFLINE_INFERENCE_OK`, `EMULATOR_SMOKE_OK`. |
| Workflow run | `45102003961` (`push`) and `45102008322` (`pull_request`) — `apk`, `update-install`, `emulator-smoke` success. Heavy opt-in jobs skipped as designed. |
| In-place update | `firstInstallTime` preserved (2026-11-23 02:49:54), versionCode `100` → `1259`, `Success`, `UPDATE_INSTALL_OK`. |
| Release APK | `update-v101.apk`, 123,706,557 B, sha256 `5c8f815578ea51fe888d1076eaf324e9fbe6e2471e86336e4b1ac4229f1f44ea`. |
| Signing | `CN=AVNIVO Translator`, cert SHA-256 `12e7e26104c7a5110a849baec36d6916efe0b2494384749d4c733286aeeecb8e` (same stable identity). |

### Next exact action

Install `update-v101.apk` (versionCode 1259) on the S23 Ultra and confirm, with an offline
Uzbek→Russian pair: the translation bubble starts with the Russian sentence and no `rus_Cyrl`
prefix; the source `🔊` uses the Silero Uzbek voice; the translation `🔊` uses a Russian voice and
never the Uzbek one. If the device has no Russian TTS voice, the button title must read
«Bu til uchun ovoz o‘rnatilmagan: ru» and it must stay silent rather than speak Russian in Uzbek.
Capture `adb logcat -s AvnivoNllb AvnivoSileroTts` while doing it.


## Stage: offline STT session chain (S23 Ultra report)

The S23 Ultra reported one bug chain, not three separate faults: the first Uzbek recording produced
nothing, the second press showed the first recording's text, and the processing indicator never
appeared. The same run showed the module card claiming the engine was missing while its files were
installed, and the greeting animation returning in the middle of transcription.

| Symptom | Root cause | Fix |
| --- | --- | --- |
| First press yields no text | `start` submitted microphone capture to the same single-thread executor that loads the 592 MB model, so capture waited behind `verifyEngine()` and often missed the whole press. | `captureWorker` (microphone) is separate from `worker` (download/activation) and `sttWorker` (decode). Capture starts immediately. |
| Second press shows the first transcript | A press was refused while `busy` was true, and `busy` stayed set during decoding. The first result was therefore only visible when the user pressed again. | `UzbekSttController` owns `openSession` and `transcribingSession`. A press is refused only while another capture is still open; a running decode never blocks it. |
| A result can land in the wrong press | Native results had no session identity, so a late result could overwrite a newer recording. | The page allocates the session, the controller adopts it, and every native callback carries it. `AvnivoOfflineResult` / `Error` / `Level` / `State` reject a session that is not current. |
| Processing indicator never appears | The indicator was driven by a status string, and the old status handler treated any non-"yozib olin" message as a release. | The controller emits `recording` and `transcribing` states before decoding. `AvnivoOfflineStatus` is informational only; only a result, error or explicit rejection ends a press. |
| Greeting animation returns mid-transcription | The same status heuristic called `finishOfflineHold()` on "Matnga aylantirilmoqda…", restoring the composer while the decode was still running. | The heuristic is gone; the hold stays until the current press settles. |
| Card cannot read its own state | `moduleStatus()` was declared on the Activity, but only the `Bridge()` object is passed to `addJavascriptInterface`, so the page could never call it. | `moduleStatus()` moved onto `Bridge`; `AvnivoOfflineState` updates the card, the active list and the reason line from that JSON. |
| Recording/decoding makes the card disappear | `renderOfflineActiveList` kept only `ready`. | `recording` and `transcribing` are active states; the card stays visible with its switch. |
| Release before the microphone opens is lost | `recorder` was null when `stop()` arrived, so the capture ran to the 30 s limit. | The bridge remembers the release per session (`stopRequestedSession`) and applies it when capture starts. |
| Engine released under memory pressure never returns | The old engine field was closed and never rebuilt. | `onTrimMemory(RUNNING_CRITICAL)` calls `releaseForMemory()`; the next press or `onResume` reloads through `ensureReady()`. |
| Installed card says the engine is missing | The card was promoted or demoted by wording instead of evidence. | Native states `installed`, `initializing`, `ready`, `recording`, `transcribing` and `error` are rendered directly; `installed` reads "O'rnatilgan · ishlatilganda yuklanadi". |

### Tests added

| Layer | What it proves |
| --- | --- |
| Node `offline-stt-state.test.cjs` (12) | A status string never ends a press; the card accepts every native state; `moduleStatus` is on the registered bridge; memory release is recoverable; resume re-announces; a cancelled press discards its audio; startup READY cannot cut a live recording. |
| JVM `UzbekSttControllerTest` (16) | 30 sequential sessions produce 30 transcripts; a new press is allowed while the previous decode runs; a stale release cannot own a newer capture; 5 release/recovery cycles rebuild the decoder; OOM and decode failures settle ERROR and the next session recovers; 10 restarts reach READY; `statusJson` exposes the evidence. |
| JVM `SpeechAccuracyTest` (7) | WER/CER are real edit distances; an empty hypothesis is never "perfect"; a wrong transcript has a positive error rate. |
| JVM `OfflineModuleStateTest` (7) | READY/RECORDING/TRANSCRIBING are operational; a download can never jump to READY; capture transitions exist. |
| Instrumented `OfflineMicGestureInstrumentedTest` (+5) | 30 pointer press/release cycles insert 30 own transcripts; 10 mode switches keep the card ready; the state callback drives the card; a progress status does not end a live recording; a cancelled press drops its late result. |
| Instrumented `OfflineUzbekSttStressInstrumentedTest` (new) | 30 real sessions, 10 restarts and 5 recoveries on the real decoder; logs `UZBEK_STT_STRESS`, `UZBEK_STT_RESTARTS`, `UZBEK_STT_RECOVERY`. |
| Instrumented `OfflineRealInferenceTest` | Adds `UZBEK_STT_ACCURACY wer=... cer=...` and `UZBEK_STT_LATENCY prepare_ms=... decode_ms=...`; WER must stay below 1.0. |

### Evidence status for this stage

| Check | Result |
| --- | --- |
| Node suites (13 files) | **114 pass / 0 fail** locally with `node --test`. |
| JVM unit tests | 140 test methods present (116 before + 16 controller + 7 accuracy + 1 state). CI runs them; this sandbox has no JVM/Gradle. |
| Instrumented tests | 48 methods across 13 suites (42 before + 5 gesture + 1 stress class). CI runs them; this sandbox has no emulator. |
| APK | Produced by the `apk` job of the pushed commit; digest recorded after the run. |
| PR | #28 stays **draft**; no new branch or PR, `main` untouched. |

### Next exact action

Push the commit to `integrate/offline-engines-20261118`, wait for the `apk` and `emulator-smoke`
jobs, then install the new APK on the S23 Ultra and run the acceptance: 30 sequential Uzbek
recordings each produce their own text on the first press; the processing indicator appears
immediately after release; 10 offline/online switches keep the module card visible; 5 forced
memory-pressure cycles recover on the next press; and a Russian translation is never read with the
Uzbek voice. Capture `adb logcat -s AvnivoUzbekStt AvnivoSileroTts` during the run.

## Recovery checkpoint 2026-11-23 19:20 UTC: Whisper Large pass 1 and module lifecycle

The session that started after the sandbox error resumed from commit `efc6a2f` without recreating
history. It committed `5522b91` (Whisper lifecycle and native diagnostics), `02f536c` (deletion
regression assertion) and `2f242cf` (run the lifecycle instrumentation in standard CI). PR #28
stayed draft; `main` was not touched.

### Exact result of the opt-in Whisper Large run

Run `45227554823` (`workflow_dispatch`, commit `02f536c`) reached pass 1 and passed it:

| Evidence | Value |
| --- | --- |
| Pass 1 instrumentation | `OfflineWhisperInferenceTest` — **OK (10 tests)**, 3,753.261 s |
| Native metadata | `WHISPER_LARGE_NATIVE vocab=51866 audio_ctx=1500 text_ctx=448 multilingual=1 type=large` |
| Memory after inference | `WHISPER_LARGE_MEM heap_used_mb=4 native_alloc_mb=1246 max_heap_mb=192 recall=7/7` |
| Pass 1 scope | download, SHA-256 verification, activation, real inference, and the Small→Large→Small switching test |

Pass 2 (same suite with Wi-Fi and mobile data disabled) started at 18:34 UTC and was still in its
first test at 19:19 UTC, when the job's `timeout-minutes: 110` cancelled it. The job log contains
`##[error]The operation was canceled`, not a test failure. Artifact `14853424014` contains the
completed pass 1 result and `instrument_whisper_large_offline.txt` with a single progress dot.
This is a CI wall-clock limit, not an activation or inference regression.

### Standard CI and artifacts

Run `45241271615` on `2f242cf` is **success** (`apk`, `update-install`, `emulator-smoke`). The
emulator ran `OfflineModuleCardInstrumentedTest` — **OK (11 tests)** and the new
`OfflineModuleLifecycleInstrumentedTest` — **OK (2 tests)**. `OfflineWhisperInferenceTest` passed
with **OK (10 tests)**, and the existing Small, TTS, real-inference, online-microphone and stress
suites all passed.

Release artifact `avnivo-translator-release-apk` (artifact `14851023761`) contains
`update-v101.apk`, **123,740,501 bytes**, SHA-256
`6d420d0f1f28c4cb3629d523437e54d4f2333a03719dabed7643ec4967e150b9`.

### Remaining work

Device acceptance on the S23 Ultra is still required: install the artifact, confirm Large is the
only resident engine after activation, switch Small→Large→Small with real speech, delete one
module and leave the other intact, and confirm the Russian translation is never spoken by the
Uzbek Silero voice. The pass 2 offline repeat is also still pending; it needs a longer job timeout
or a narrower class filter so it does not exceed 110 minutes.
