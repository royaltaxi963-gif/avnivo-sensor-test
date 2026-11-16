# AVNIVO — Whisper multilingual models in private GCS

This is a **pre-upload configuration**, not evidence of upload, runtime integration, or successful inference.

- Keep the existing Uzbek Sherpa-ONNX STT model, storage objects, and download route unchanged.
- Default choice: Whisper Small Multilingual Q8_0 (~252 MiB). Optional quality choice: Whisper Large V3 Turbo Q5_0 (~547 MiB).
- Users may install both, select one active runtime, and remove either independently.
- Do not enable download until the actual existing GCS bucket, upstream model identity, license, SHA-256 and bytes are verified and the private objects are uploaded.
- Extend the backend ticket allowlist for each model ID; return signed HTTPS URLs for private objects. Enforce authentication, quota/rate limits, and short TTL. Never publish service-account keys.
- Android: resumable app-private download; streaming SHA-256; disk-space check; atomic install; native whisper.cpp initialization and a real offline transcription self-test before `active`.
- Never report an installed or active model based solely on a download finishing. Do not alter Online/Offline routing until tested.
- Verify installation, airplane mode, app restart, interrupted download, hash mismatch, removal, and switching between both installed models.

Current blocker: no Google Cloud Storage write connector is available in this chat, and no bucket/object upload has been performed.
