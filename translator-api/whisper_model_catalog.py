"""Gated Whisper model metadata for private GCS distribution.

Never advertise unverified/unuploaded binaries as downloadable.
The Uzbek Sherpa-ONNX ticket endpoint remains unchanged.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Optional


@dataclass(frozen=True)
class WhisperModel:
    model_id: str
    object_name: str
    filename: str
    sha256: Optional[str] = None
    size_bytes: Optional[int] = None
    uploaded_and_verified: bool = False

    @property
    def ready(self) -> bool:
        return bool(
            self.uploaded_and_verified
            and self.size_bytes is not None
            and self.size_bytes > 0
            and self.sha256 is not None
            and len(self.sha256) == 64
            and all(c in "0123456789abcdef" for c in self.sha256)
        )


WHISPER_MODELS = {
    "whisper-small-q8_0": WhisperModel(
        model_id="whisper-small-q8_0",
        object_name="multilingual-stt/whisper-small-q8_0/ggml-small-q8_0.bin",
        filename="ggml-small-q8_0.bin",
    ),
    "whisper-large-v3-turbo-q5_0": WhisperModel(
        model_id="whisper-large-v3-turbo-q5_0",
        object_name="multilingual-stt/whisper-large-v3-turbo-q5_0/ggml-large-v3-turbo-q5_0.bin",
        filename="ggml-large-v3-turbo-q5_0.bin",
    ),
}


def ready_model_files(model_id: str) -> list[dict] | None:
    model = WHISPER_MODELS.get(model_id)
    if model is None or not model.ready:
        return None
    return [{
        "name": model.filename,
        "object": model.object_name,
        "bytes": model.size_bytes,
        "sha256": model.sha256,
    }]
