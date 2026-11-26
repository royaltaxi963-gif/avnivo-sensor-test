#!/usr/bin/env python3
"""Reproducible real-inference check for the AVNIVO Uzbek STT model.

This is the ground-truth verification behind the app's engine smoke test. It downloads the
*actual* model from the same signed-ticket endpoint the Android app uses, verifies the published
SHA-256 values, then decodes real Uzbek audio with the same runtime family and configuration
(sherpa-onnx NeMo CTC, int8, 16 kHz, 80-dim features) that ``OfflineSpeechEngine.kt`` uses.

The model itself is user-supplied with no public hosting URL or redistribution license, so this
script never uploads anything and only fetches from the existing authenticated endpoint.

Usage:
    python verify_uzbek_stt_inference.py --audio calm_intro.wav --expected "..."

Environment:
    AVNIVO_TICKET_URL  override the ticket endpoint (defaults to the staging Cloud Run service).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import sys
import tempfile
import urllib.request
from math import gcd

DEFAULT_TICKET = (
    "https://avnivo-translation-test-792624108019.europe-west1.run.app"
    "/api/offline-models/uzbek-stt/download-ticket"
)
EXPECTED = {
    "model.int8.onnx": (591649160, "8bff17ef8d7da04369a559b99b71c1155a90fc604d35d84c6d4e44159c1b0873"),
    "tokens.txt": (411, "2022129a0c61784d4a1838da59e5875f9b29d2c6ad12413cbedc98632f98710f"),
}


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def fetch_model(target: str) -> None:
    url = os.environ.get("AVNIVO_TICKET_URL", DEFAULT_TICKET)
    with urllib.request.urlopen(url, timeout=30) as response:
        ticket = json.load(response)
    assert ticket["model_id"] == "uzbek-stt", ticket.get("model_id")
    for entry in ticket["files"]:
        name = entry["name"]
        size, digest = EXPECTED[name]
        assert entry["bytes"] == size, f"{name}: size mismatch in ticket"
        assert entry["sha256"].lower() == digest, f"{name}: hash mismatch in ticket"
        destination = os.path.join(target, name)
        print(f"downloading {name} ({size} bytes)")
        urllib.request.urlretrieve(entry["url"], destination)
        actual = sha256(destination)
        assert actual == digest, f"{name}: downloaded SHA-256 {actual} != {digest}"
        print(f"  verified SHA-256 {actual}")


def resample_to_16k(samples, sample_rate):
    import numpy as np
    from scipy.signal import resample_poly

    if sample_rate == 16000:
        return samples.astype("float32")
    factor = gcd(int(sample_rate), 16000)
    return resample_poly(samples, 16000 // factor, int(sample_rate) // factor).astype("float32")


def decode(model_dir: str, audio: str, expected: str) -> int:
    import numpy as np
    import sherpa_onnx
    import soundfile as sf

    recognizer = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(
        model=os.path.join(model_dir, "model.int8.onnx"),
        tokens=os.path.join(model_dir, "tokens.txt"),
        num_threads=2,
        provider="cpu",
        sample_rate=16000,
        feature_dim=80,
        decoding_method="greedy_search",
    )

    # 1-second silence: exactly what the Android engine runs before granting READY.
    silence = np.zeros(16000, dtype="float32")
    stream = recognizer.create_stream()
    stream.accept_waveform(16000, silence)
    recognizer.decode_stream(stream)
    print(f"engine smoke test (silence) ok: {stream.result.text!r}")

    samples, rate = sf.read(audio, dtype="float32")
    if getattr(samples, "ndim", 1) > 1:
        samples = samples.mean(axis=1)
    samples = resample_to_16k(samples, rate)
    stream = recognizer.create_stream()
    stream.accept_waveform(16000, samples)
    recognizer.decode_stream(stream)
    actual = stream.result.text.strip()
    print(f"expected: {expected}")
    print(f"actual  : {actual}")

    expected_words = expected.lower().split()
    actual_words = actual.lower().split()
    hits = sum(1 for w in expected_words if w in actual_words)
    print(f"word recall: {hits}/{len(expected_words)} = {hits / max(1, len(expected_words)):.2f}")
    return 0 if actual else 1


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--audio", required=True, help="16-bit or resampled WAV of real Uzbek speech")
    parser.add_argument("--expected", required=True, help="documented transcript of the audio")
    parser.add_argument("--model-dir", help="reuse an already-downloaded model directory")
    args = parser.parse_args()

    if args.model_dir:
        model_dir = args.model_dir
    else:
        tmp = tempfile.mkdtemp(prefix="avnivo-uzstt-")
        fetch_model(tmp)
        model_dir = tmp

    return decode(model_dir, args.audio, args.expected)


if __name__ == "__main__":
    sys.exit(main())
