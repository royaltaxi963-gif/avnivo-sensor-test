# AVNIVO offline STT — in-browser Whisper Small engine

This document describes the **working, tested in-browser** offline speech-to-text
engine for AVNIVO Tarjimon. It is separate from, and complementary to, the native
Android plan in `docs/offline-stt-whisper-small.md` and the download catalogue in
`models/offline-stt-whisper-small.json`.

## What this is

A real inference engine that runs Whisper Small **inside the browser**, with the model
stored on the device. Once the model is downloaded the page transcribes microphone
audio with the network switched off. No server API is involved.

- Engine: Transformers.js (ONNX Runtime Web) running OpenAI **Whisper Small multilingual**.
- Model export: `Xenova/whisper-small`, int8 (`dtype: "q8"`).
- Runtime: vendored locally under `vendor/transformers/` (no third-party script fetch).
- Uzbek is **not** handled here: it keeps using the existing custom offline module.

## Measured facts

| Item | Value |
| --- | --- |
| Model files | 7 files, 251,875,316 bytes total (~252 MB / 240 MiB) |
| Pinned revision | `2d67713f236afa48a18992566e7647f6ca848e13` |
| Largest file | `onnx/decoder_model_merged_quantized.onnx`, 156,780,950 bytes |
| Vendored runtime | `transformers.min.js` 888 KB + ONNX WASM JSEP 21.6 MB |
| Integrity | per-file byte length + sha256 verified before a file is accepted |
| Storage | Cache Storage (`avnivo-whisper-small-v1`), with in-memory fallback |

## Architecture

| Component | File | Responsibility |
| --- | --- | --- |
| `LanguageRegistry` | `offline-stt/language-registry.mjs` | seven languages; Uzbek → custom, six → Whisper |
| `STTRouter` | `offline-stt/stt-router.mjs` | decides which engine owns the microphone |
| `ModelManager` | `offline-stt/model-manager.mjs` | download, resume, hash-verify, remove |
| `OfflineStorage` | `offline-stt/offline-storage.mjs` | device persistence + runtime cache adapter |
| `AudioRecorder` | `offline-stt/audio-recorder.mjs` | microphone → mono 16 kHz Float32 |
| `STTEngine` | `offline-stt/stt-engine.mjs` + `.worker.mjs` | worker-isolated inference |
| UI / integration | `offline-stt/avnivo-offline-stt.mjs` | settings UI, model lifecycle, mic routing |

The worker is loaded with `{ type: 'module' }` and keeps inference off the main thread.
`window.avnivoOfflineStt` exposes a small, stable API (`load`, `transcribe`,
`setLanguage`, `isInstalled`, `routeLanguage`) so a future Android WebView bridge can
reuse the same logic.

## Download and storage

The settings section (Sozlamalar → Offline) shows all seven languages, the module that
serves each, and its state. A single "Yuklab olish" button downloads the shared model
once; later languages never re-download it. Progress is shown as a percentage with
downloaded MB. Free space is checked against 1.25× the model size before downloading.
Interrupted downloads resume at file granularity, because already-stored files are
skipped. "Tayyor" is only shown after the model has actually loaded and run.

## Honest accuracy evidence

Measured in headless Chromium (CPU / WASM, no WebGPU) on **8 real speech samples per
language**, model read from device storage with `huggingface.co` unreachable. Audio
sources: FLEURS validation for ru/en/hi/tr; `arif11/Bengali_AI_Speech` and
`imonghose/bengali-asr-data` for bn (FLEURS ships Bengali as `bn_in`); `rozumov/TurkmenSpeech`
for tk (FLEURS has no Turkmen). WER and CER are computed against the dataset reference
transcripts.

| Language | Samples | WER mean | WER median | CER mean | RTF median | Verdict |
| --- | --- | --- | --- | --- | --- | --- |
| English | 8 | 0.114 | 0.048 | 0.033 | 1.83 | usable |
| Turkish | 8 | 0.199 | 0.156 | 0.062 | 0.93 | usable |
| Russian | 8 | 0.211 | 0.211 | 0.075 | 1.20 | usable |
| Hindi | 8 | 0.425 | 0.396 | 0.295 | 1.60 | degraded but readable |
| Turkmen | 8 | 1.167 | 1.072 | 0.966 | 2.15 | **not usable** |
| Bengali | 8 | 2.969 | 1.000 | 3.378 | 3.05 | **not usable** |

Model load time was 3.3 s; zero requests to `huggingface.co` occurred during inference.

### Bengali and Turkmen: cause, not a pipeline bug

A focused diagnostic confirmed the `language` argument is genuinely applied:

- Turkmen (`tk`): forcing `tk` gives Turkic-looking but wrong text; forcing the related
  `turkish` token on the same clip produces the correct sentence content. The model's
  Turkmen weights are simply weak.
- Bengali (`bn`): forcing `bn` returns Devanagari (Hindi) text and, on some clips,
  degenerate repetition. Whisper Small does not reliably cover Bengali.

So these are **model-quality limits of Whisper Small**, not an integration fault. Both
language tokens (`<|bn|>` = 50302, `<|tk|>` = 50341) exist in the model and in the
vendored runtime, and forcing them changes the output.

These numbers are CPU-only and non-Android. They show the engine really transcribes;
they are not a device-quality guarantee. Bengali and Turkmen should be treated as
unsupported until proven otherwise on the target device, and the interface should say so.

## Offline behaviour and limits

- The page, the module set and the runtime are cached by `sw.js` (Service Worker), so
  the app reopens with the network off.
- The model lives in its own Cache Storage. The Service Worker's activate step
  explicitly preserves `avnivo-whisper-small-*`; a version bump can no longer wipe the
  downloaded model.
- Cross-origin isolation (COOP/COEP) is **not** required; the engine runs single-threaded
  WASM. Multi-threaded WASM would need those headers, which GitHub Pages does not send.
- A worker fetch guard answers every model URL from device storage and fails fast
  otherwise, so no weights can be fetched from the network during inference.
- Cache Storage can still be evicted by the browser under storage pressure. Presence is
  re-checked before any language is reported ready.

## Not done / not claimed

- No Android APK was produced.
- No real Android Chrome run was performed; all measurements are desktop headless
  Chromium. Performance on a mid-range Android phone is expected to be slower than the
  RTF column above and must be measured on-device.
- WebGPU was not exercised. The WASM/CPU path is the one measured.
- This engine is unrelated to Groq and Google Chirp 2, which are untouched.

## APK path

The module split above is already WebView-friendly. For an APK: bundle the HTML/JS in
assets, keep `ModelManager`/`OfflineStorage` behind a native bridge, and replace
`STTEngine` with a native runtime if browser inference is too slow on device. Do not
assume WebView reproduces desktop performance.

## Tests

`offline-stt/tests/offline-stt.test.mjs` (20 cases, all passing) covers sha256 vectors,
model-spec sizes/hashes, registry consistency, routing, and the model manager
(resume, integrity rejection, no re-download). Browser behaviour was verified with a
Puppeteer harness against the real app page.
