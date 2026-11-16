#!/usr/bin/env bash
# Execute in authenticated Google Cloud Shell with bucket objectCreator/viewer permissions.
set -Eeuo pipefail
BUCKET="${AVNIVO_MODEL_BUCKET:-avnivo-offline-models-792624108019}"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT
declare -a FILES=(
  "ggml-small-q8_0.bin|264464607|49c8fb02b65e6049d5fa6c04f81f53b867b5ec9540406812c643f177317f779f|multilingual-stt/whisper-small-q8_0/ggml-small-q8_0.bin"
  "ggml-large-v3-turbo-q5_0.bin|574041195|394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2|multilingual-stt/whisper-large-v3-turbo-q5_0/ggml-large-v3-turbo-q5_0.bin"
)
command -v gcloud >/dev/null || { echo "gcloud CLI required"; exit 1; }
command -v curl >/dev/null || { echo "curl required"; exit 1; }
gcloud storage buckets describe "gs://$BUCKET" >/dev/null
for entry in "${FILES[@]}"; do
  IFS='|' read -r filename bytes sha object <<< "$entry"
  target="$WORKDIR/$filename"
  echo "Downloading verified upstream $filename"
  curl --fail --location --retry 4 --retry-all-errors --output "$target" "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$filename"
  actual_bytes="$(wc -c < "$target" | tr -d ' ')"
  [[ "$actual_bytes" == "$bytes" ]] || { echo "Byte count mismatch for $filename: $actual_bytes"; exit 1; }
  echo "$sha  $target" | sha256sum --check --status || { echo "SHA256 mismatch: $filename"; exit 1; }
  echo "Uploading gs://$BUCKET/$object"
  gcloud storage cp --no-clobber "$target" "gs://$BUCKET/$object"
  remote="$WORKDIR/verify-$filename"
  gcloud storage cp "gs://$BUCKET/$object" "$remote"
  echo "$sha  $remote" | sha256sum --check --status || { echo "GCS object mismatch: $object"; exit 1; }
  [[ "$(wc -c < "$remote" | tr -d ' ')" == "$bytes" ]] || { echo "GCS size mismatch: $object"; exit 1; }
  echo "VERIFIED: $object ($bytes bytes)"
done
echo "Both Whisper model objects uploaded and independently verified."
