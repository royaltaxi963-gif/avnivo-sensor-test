# AVNIVO offline translation — integration contract

Status: implementation groundwork, NOT a verified working translator.

## Selected model
- facebook/nllb-200-distilled-600M, quantized ONNX INT8.
- Expected download roughly 0.9–1.2 GB depending on selected repository and auxiliary files. Verify exact manifest sizes before displaying progress.
- Model license CC BY-NC 4.0; commercial distribution requires separate rights review.
- Language codes (NLLB): uzn_Latn, rus_Cyrl, eng_Latn, hin_Deva, ben_Beng, tur_Latn, tuk_Latn.
- Verify tokenizer support and direction-specific quality before enabling each language pair.

## Offline architecture
1. Keep current frontend and online/offline STT unchanged.
2. APK must bundle HTML/CSS/JS and runtime assets locally, not point its WebView at GitHub Pages.
3. Download model files from a pinned, reviewed manifest (exact revision, URLs, byte sizes, SHA-256). Do not fetch an unpinned model automatically.
4. Persist files in Android app-private storage; use native download manager and atomic write/rename, with resume, checksum, available-space checks and delete control.
5. Expose a narrow JS bridge to WebView: status(), download(), cancel(), delete(), translate(text, source, target). Never expose arbitrary file paths or shell access.
6. Execute quantized ONNX with ONNX Runtime Android; verify NLLB encoder/decoder architecture, tokenizer, autoregressive decoding, forced target-language token and CPU performance on target devices.
7. For HTML-only prototype, IndexedDB/OPFS caching is experimental and subject to browser quota/eviction; do not claim reliable APK persistence.
8. Run airplane-mode acceptance test after fully downloading model and closing/restarting app.

## Acceptance gates
- All seven language identifiers accepted, and source/target swap works.
- Verify actual text quality for Uzbek <-> each other language; test remaining pairs separately.
- First download progress, interruption/resume, corruption rejection, restart persistence, insufficient storage and deletion.
- APK opens with Wi-Fi/mobile data off; translations require no network.
- No demo translation masquerading as real model inference.

## Current work boundary
This file is a design and verification contract only. No model weights have been uploaded, no ONNX inference runtime is integrated, and no offline translation has passed device testing.
