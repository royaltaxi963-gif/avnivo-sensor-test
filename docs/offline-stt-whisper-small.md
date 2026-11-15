# AVNIVO offline STT — Whisper Small Multilingual

**Status:** Download/install manifest prepared; runtime and APK integration not yet implemented.

Model selection: **Whisper Small multilingual (not small.en)**, 466 MiB original. Optional q8_0 (252 MiB) and q5_1 (181 MiB) variants are for quality/performance comparison only. Source: https://huggingface.co/ggerganov/whisper.cpp

Manifest: `models/offline-stt-whisper-small.json`.

## APK integration
1. Bundle HTML/CSS/JS locally in Android assets; do not rely on a remote WebView URL.
2. Add a Settings → Offline → Speech recognition download control reading this manifest.
3. Download selected model with progress, cancellation, retry/resume, storage-space checks, and integrity verification; use a temporary file, verify hash, then atomically rename to app-private files storage.
4. Implement native whisper.cpp inference and an explicitly scoped WebView JS bridge; resample microphone audio to the runtime's required PCM format. Never treat the downloaded model as a functioning STT engine without inference.
5. Keep the existing dedicated Uzbek offline STT module and its routing untouched. Whisper Small is for other languages.
6. Verify each requested language with native-speaker recordings; some languages may be unsupported or weak. Do not promise Turkmen support without a successful engine test.
7. Test flight mode after app restart, interrupted downloads, corrupt model rejection, and uninstall cleanup.

**Security:** Do not download executable code or expose arbitrary local files through WebView. Model downloads are data only. Verify publisher and file digest.

**Boundary:** No APK or local STT inference has been produced in this change. This is a pinned model catalogue and installation acceptance plan.
