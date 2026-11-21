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
