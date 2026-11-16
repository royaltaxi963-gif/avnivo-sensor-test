# AVNIVO offline STT — Android Chrome readiness

Scope: the browser offline STT engine in `offline-stt/` (Whisper Small multilingual).
Uzbek keeps using the existing custom module and is out of scope here.

> **Android device status: NOT TESTED.**
> Every measurement below comes from desktop headless Chromium. No real Android Chrome
> run was performed. The device procedure in the last section is the way to change this
> to TESTED; until it is run, treat Android performance and behaviour as unverified.

## 1. Microphone permissions and recording

- Recording uses `navigator.mediaDevices.getUserMedia` + `MediaRecorder`. Both are
  supported by Android Chrome over HTTPS.
- Permission is required on first use. A denied prompt raises `NotAllowedError` and the
  UI shows "Mikrofonga ruxsat berilmagan." No retry loop, no silent failure.
- The recorded clip is decoded and resampled to mono 16 kHz Float32 entirely on the
  device with the Web Audio API.
- `getUserMedia` only works in a **secure context** (HTTPS, or `localhost`). Over plain
  `file://` Android Chrome blocks the microphone, so the PWA/HTTPS path is required.

## 2. WASM initialisation and worker compatibility

- Inference runs in a module worker (`new Worker(url, { type: 'module' })`) so the UI
  thread is never blocked.
- ONNX Runtime Web loads its WASM from the same origin (`vendor/transformers/`), pinned
  to the vendored files; nothing is fetched from a CDN.
- Single-threaded WASM is used. It needs no COOP/COEP headers, which matters because
  GitHub Pages cannot send them. Multi-threaded WASM would require those headers.
- Module workers are supported in Android Chrome. A `Worker` failure is surfaced as an
  error, not a hang.

## 3. Model download and storage limits

- The model is 251,875,316 bytes (~252 MB) in 7 files. Download is `fetch` + streamed
  hashing, so progress is real.
- Free space is checked before download against 1.25x the model size. If the estimate is
  unavailable the check is skipped rather than blocking.
- Storage is Cache Storage (`avnivo-whisper-small-v1`). Cache Storage is subject to
  browser eviction under pressure, so presence is always re-checked before a language is
  reported ready.
- An interrupted download resumes at file granularity: stored files are skipped.
- For an APK, a native path (app-private storage, no eviction) is the durable option.

## 4. Memory and inference performance

- Desktop Chromium (CPU/WASM) measured a real-time factor of roughly 1-4 (see the
  benchmark in `docs/offline-stt-web-engine.md`). Inference is slower than real time on
  CPU.
- A mid-range Android phone has less CPU and less memory than the test machine, so expect
  a higher RTF and real memory pressure from a ~252 MB int8 model. **This must be
  measured on device**; the desktop number is not a device number.
- WebGPU, where present on Android Chrome, is expected to be faster but was not
  exercised here.

## 5. Reload and airplane-mode recovery

- `sw.js` caches the app shell (including the functional page and the module set) and the
  vendored runtime, so the page reopens with the network off.
- The Service Worker `activate` handler preserves `avnivo-whisper-small-*`, so a version
  bump no longer wipes the downloaded model.
- During inference a worker fetch guard answers every model URL from device storage and
  fails fast otherwise, so no weights can be fetched while offline.
- The app shell cache is a runtime cache: the first offline reload works only after the
  page has been loaded online at least once so the shell is cached.

## 6. Reproducible Android device test procedure

Goal: prove or disprove offline STT on a real Android phone. Record raw output.

1. Deploy the branch to an HTTPS origin (the GitHub Pages URL, or any HTTPS host that
   serves the repository root). Plain `file://` will not work.
2. On the phone, open the functional page in **Chrome for Android**:
   `…/avnivo-v11-47-groq-sdk-exact-code-test.html`.
3. Open Sozlamalar → Offline. Confirm the language table renders.
4. Tap "Modelni yuklab olish". Keep the tab in the foreground.
   - Record: total download time, whether progress reached 100 %, and the final state.
   - If it fails, record the exact status message and `chrome://net-export` if possible.
5. Confirm every Whisper language shows "Tayyor" and Uzbek shows the custom-module state.
6. Turn on **Airplane mode**. Reload the page.
   - Record: did the page load? Did the model still show "Tayyor"?
7. Select English, tap the microphone, allow the permission, and speak a known sentence.
   - Record: the transcript, the on-screen latency, and whether the UI stayed responsive.
8. Repeat for Russian, Turkish, Hindi, Bengali and Turkmen, one clip each.
9. Open `chrome://inspect` from a desktop (USB debugging) and watch the Network panel
   during a transcription. Confirm there are **no** requests to `huggingface.co`.
10. Record memory: `chrome://tracing` or the Android developer "Memory" view while
    transcribing.

Report the raw results in the PR. Until this runs, Android stays NOT TESTED.

## 7. What is not claimed

- No APK.
- No real Android run.
- No WebGPU measurement.
- No multi-threaded WASM measurement (no COOP/COEP).
