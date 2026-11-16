# Verified Whisper GCS upload

Run `bash scripts/upload-whisper-models-gcs.sh` **inside authenticated Google Cloud Shell** after confirming that the bucket `avnivo-offline-models-792624108019` belongs to the intended AVNIVO project. The script downloads both official Hugging Face GGML binaries, checks their exact sizes and SHA-256 values, uploads only into the `multilingual-stt/` prefix, and re-downloads each object for verification. It never modifies `stt-uzbek/`. It requires object write/read permission and network access; cloud egress/storage costs may apply.

Expected upstream files:
- `ggml-small-q8_0.bin`: 264464607 bytes; SHA-256 `49c8fb02b65e6049d5fa6c04f81f53b867b5ec9540406812c643f177317f779f`
- `ggml-large-v3-turbo-q5_0.bin`: 574041195 bytes; SHA-256 `394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2`

The script is not yet executed in Google Cloud. It does not change API ticket readiness or native runtime activation. Source: ggerganov/whisper.cpp model repository; hashes cross-checked against published file manifests. For production, pin the upstream commit after verifying its provenance.
