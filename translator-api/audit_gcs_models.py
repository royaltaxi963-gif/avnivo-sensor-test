#!/usr/bin/env python3
"""Operator-only manifest audit for private AVNIVO GCS models.

Uses ADC with storage.objectViewer; never publishes object URLs or secrets.
Run with --output to create a manifest for AVNIVO_AUDITED_MODEL_MANIFEST.
No model is enabled unless all its objects are present and hashed.
"""
import argparse
import hashlib
import json
import re
import sys
from google.cloud import storage

BUCKET = "avnivo-offline-models-792624108019"
PREFIXES = {
    "whisper-small-q8": "multilingual-stt/whisper-small-q8_0/",
    "whisper-large-v3-turbo": "multilingual-stt/whisper-large-v3-turbo-q5_0/",
    "nllb-200-600m-int8": "multilingual-translation/nllb-200-600m-int8/",
    "navoiy-tts": "tts/uzbek/navoiy/",
    "mms-tts": "tts/mms/",
}
def audit(client, bucket, prefixes=PREFIXES):
    result = {}
    for model, prefix in prefixes.items():
        files = []
        blobs = list(client.list_blobs(bucket, prefix=prefix))
        for blob in sorted(blobs, key=lambda b: b.name):
            if blob.name.endswith("/") or not blob.size:
                continue
            relative = blob.name[len(prefix):]
            if not relative or ".." in relative or not re.fullmatch(r"[A-Za-z0-9_.\\/-]{1,180}", relative):
                raise ValueError(f"Unsafe object path for {model}")
            sha = hashlib.sha256()
            with blob.open("rb", chunk_size=4*1024*1024) as stream:
                while True:
                    block = stream.read(4*1024*1024)
                    if not block:
                        break
                    sha.update(block)
            files.append({"object": blob.name, "name": relative,
                          "bytes": blob.size, "sha256": sha.hexdigest()})
        if files:
            result[model] = files
        else:
            print(f"SKIPPED {model}: no verified files under {prefix}", file=sys.stderr)
    return result

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--bucket", default=BUCKET)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    manifest = audit(storage.Client(), args.bucket)
    with open(args.output, "w", encoding="utf-8") as file:
        json.dump(manifest, file, indent=2, ensure_ascii=False)
    print(f"Audited {len(manifest)} model groups. Review before configuring deployment.")

if __name__ == "__main__":
    main()
