# GCS Whisper upload and release gate

The backend already declares `AVNIVO_MODEL_BUCKET` with default `avnivo-offline-models-792624108019`. This is a **code-configured bucket name**, not confirmation that GCS access or objects are available.

The two Whisper objects are defined in `translator-api/whisper_model_catalog.py`. They are deliberately **not downloadable** until the following are completed:

1. Verify model licensing and official upstream source for both binaries.
2. Download binaries in a trusted cloud environment, verify format and exact byte size, compute SHA-256.
3. Upload to the existing private GCS bucket at the object names in the catalog. Do not modify `stt-uzbek/`.
4. Verify GCS metadata and independently re-download/check hashes.
5. Set exact bytes, hashes and `uploaded_and_verified=True` in the model catalog, review and deploy.
6. Add ready-only model entries to the existing signed-ticket endpoint, with authentication, distributed quotas, and signed-URL expiry.
7. Integrate Android downloader and whisper.cpp runtime; require offline self-test before marking active.

This change is a gated catalog and regression tests only. It does not claim GCS upload or runtime integration.
