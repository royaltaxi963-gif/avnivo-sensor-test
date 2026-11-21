# AVNIVO Translator — offline engine integration report

Branch `integrate/offline-engines-20261118` · PR #28 (open, draft, `mergeable=MERGEABLE`,
`mergeStateStatus=CLEAN`) · HEAD `79404b89275e11ff18be3659db6054284219f762`

Nothing was merged into `main` and no release was published. Both need explicit approval.

## Repository evidence

| Item | Value |
| --- | --- |
| Protected original | `05429fee35166ea07e22923f8235ef2e61ee8d3a` (untouched) |
| Branch | `integrate/offline-engines-20261118` |
| PR | https://github.com/royaltaxi963-gif/avnivo-sensor-test/pull/28 |
| APK workflow run | https://github.com/royaltaxi963-gif/avnivo-sensor-test/actions/runs/44895328615 |
| APK artifact | `avnivo-translator-release-apk` → `update-v101.apk` (142,393,511 bytes) |
| APK SHA-256 | `4d5a9727f685f898d314a9788e8e9abd1c60327eb595ed7853a1762b78117d2c` |
| Package / version | `com.avnivo.translator` · versionName `1.0.0` |
| Signing | `CN=AVNIVO Translator, OU=Development, O=AVNIVO, C=uz`, SHA-256 `12e7e261…eecb8e`, in-place update install `Success` |

The artifact is a GitHub Actions artifact, not a Release asset. Publishing a Release was not done
because the directive requires release criteria to pass first and forbids publishing without
approval. The APK installs directly with `adb install` — no ZIP extraction needed.

## Module verification

| Module | Model source | Download | Integrity | Runtime | Real inference | Offline | Android | Classification |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Uzbek STT | Nemo CTC INT8 591 MB, GCS + signed ticket | pass | SHA-256 pinned | sherpa-onnx JNI | transcript 7/7 words | pass, radios off | 4 ABIs | **VERIFIED WORKING** |
| Multilingual STT (Whisper Small) | `ggml-small-q8_0.bin` 264 MB | pass | SHA-256 pinned | whisper.cpp JNI | 11 s JFK clip, 6.7× RT | pass, identical timings | 4 ABIs | **VERIFIED WORKING** |
| Large Multilingual STT (Whisper Large V3 Turbo) | `ggml-large-v3-turbo-q5_0.bin` 574 MB | pass | SHA-256 pinned | whisper.cpp JNI | recall 7/7 | pass, 1,246 MB native | 4 ABIs | **VERIFIED WORKING** |
| Offline Translation (NLLB-200 600M INT8) | 2 ONNX sessions + tokenizer, 916 MB | pass | SHA-256 pinned | ONNX Runtime JNI | `Привет, как вы?` / `It's very hot today.` / `Спасибо` | pass, 0 network errors | 4 ABIs | **VERIFIED WORKING** |
| Large Offline Translation | none — card is a placeholder | n/a | n/a | n/a | n/a | n/a | UI retained | **BLOCKED — NO MODEL** |
| Navoiy TTS (existing card, CosyVoice2) | CosyVoice2, PyTorch weights only (`emotion_600h_joint.pt`) | n/a | n/a | no ONNX export | n/a | n/a | UI retained | **BLOCKED — NO ONNX ARTIFACT** |
| MMS TTS | VITS ONNX exists upstream but absent from bucket (403) | n/a | n/a | sherpa-onnx OfflineTts could run it | n/a | n/a | UI retained | **BLOCKED — LICENSE + MISSING OBJECT** |
| `silero-tts` (new spec, Uzbek TTS runtime) | Silero `v5_cis_base`, MIT (`LICENSE_CIS`), 91.7 MB TorchScript | **fails** — not hosted | digest pinned | PyTorch Lite JNI | 2.125 s audio, peak 0.782 | pass, radios off | 4 ABIs | **IMPLEMENTED — HOSTING PENDING** |

The three blocked modules are not stubs that pretend to work. `OfflineModelSpecs` registers them
with `engine = UNAVAILABLE` and an Uzbek `blocker` string, so the existing card reports the real
reason and `download()` refuses before touching the network.

### Evidence per module

Uzbek STT, identical online and offline:

```
UZBEK_STT_RESULT text=navoiy tts o'zbekcha matnni tabiiy va ravon ovozga aylantiradi.
```

NLLB-200, identical online and offline:

```
Salom=1468ms, Salom, qalaysiz?=4066ms, Bugun havo juda issiq.=4485ms, Rahmat=1402ms
results: rus_Cyrl Привет, как вы? / eng_Latn It's very hot today. / rus_Cyrl Спасибо
```

Whisper Large memory, measured in-process with the model resident:

```
WHISPER_LARGE_MEM heap_used_mb=4 native_alloc_mb=1246 max_heap_mb=192 recall=7/7
```

Whisper Small, online versus radios off:

```
online:  WHISPER_PERF audio=11.00s download=35.8s first=73.6s repeat=73.6s first_rt=6.69x
offline: WHISPER_PERF audio=11.00s download=35.7s first=73.2s repeat=73.2s first_rt=6.66x
```

## Regression evidence

| Check | Result |
| --- | --- |
| UI files touched in this session | none (`git diff b821dcb..HEAD -- assets/ res/` is empty) |
| Preservation test | 5/5 — every original CSS selector, declaration, element id and class retained; all seven modules still wired |
| Manual-send guard | 5/5 — STT handlers cannot send, translate or speak; mutation-injected `sendMessage()` is caught |
| SHA-256 vectors | 13 cases pass |
| JVM unit tests | 47 tests, 0 failures, 5 ignored (52 before, minus 5 MADLAD tests removed) |
| Instrumented suites | emulator-smoke `OK`, NLLB `OK (6 tests)` ×2, Whisper `OK (5 tests)`, Large `OK (5 tests)` ×2 |
| Online functionality | untouched; no online source file was modified |
| APK install | `Success` in-place over the previous build, same signing key |

All required PR checks pass: `apk`, `test`, `update-install`, `emulator-smoke`.

## Remaining limitations

1. **No physical device.** Every result above comes from an x86_64 API 34 emulator with KVM.
   No Samsung Galaxy S23 Ultra was available and this environment has no `adb`, so ARM64
   performance and thermals are unverified. `scripts/run-device-verification.sh` runs the same
   suites on a connected phone; it has never been executed against hardware.
2. **Silero TTS is integrated but cannot reach READY in this environment.** The earlier version of
   this report said the module did not exist; that was true of the repository then and is not true
   now. `OfflineModelSpecs` carries a `silero-tts` spec (`SILERO_TTS` engine, MIT licence, digest
   `ba41b18f…`, 91,680,514 bytes) and on-device synthesis is proven against a CI-exported artifact.
   What is missing is the hosted download: the file is not in the bucket and the deployed API
   predates the manifest entry, so the download path still fails and `emulator-smoke` reports
   `SILERO_TTS_DOWNLOAD_STILL_FAILING`.
3. **Three modules are blocked by missing artifacts or licensing**, not by engineering effort.
4. **Draft PR.** Not marked ready, not merged, no Release published — all awaiting approval.

## What changed in this session

Six files, all tests, CI and documentation. No application source file was modified.

| File | Change |
| --- | --- |
| `tests/manual-send.test.cjs` | new — enforces the manual-send contract |
| `scripts/run-device-verification.sh` | new — physical-device harness |
| `OfflineRealInferenceTest.kt` | +6 lines — log `UZBEK_STT_RESULT` |
| `OfflineWhisperInferenceTest.kt` | +13 lines — log `WHISPER_LARGE_MEM` |
| `avnivo-integrated-android.yml` | +31 — two-pass large-model job, offline passes, marker gates |
| `docs/offline-integration-checkpoint.md` | +130 — evidence log |

## Two defects found and fixed in the verification itself

Both were cases where a green run did not mean what it appeared to mean.

`large-model-smoke` failed at 1m51s on its first two-pass attempt. `android-emulator-runner`
executes each script line as its own shell, so backslash continuations reached `am` as a literal
argument and it printed its help text. Each command is now on one line.

The Uzbek suite printed nothing, and a skipped test also prints `OK`. A 3.4 s "pass" on a 591 MB
model was therefore not provable. The test now logs the transcript and CI fails when the marker is
missing or empty — the same guard already used for `WHISPER_PERF`.
