"""Offline Whisper distribution must fail closed until verified uploads exist."""
from dataclasses import replace

from whisper_model_catalog import WHISPER_MODELS, ready_model_files


def test_selected_models_are_known_but_not_downloadable():
    assert set(WHISPER_MODELS) == {
        "whisper-small-q8_0",
        "whisper-large-v3-turbo-q5_0",
    }
    for model_id in WHISPER_MODELS:
        assert ready_model_files(model_id) is None


def test_unknown_model_is_not_downloadable():
    assert ready_model_files("unknown") is None


def test_complete_verified_metadata_is_required():
    model = WHISPER_MODELS["whisper-small-q8_0"]
    assert not replace(model, uploaded_and_verified=True).ready
    assert not replace(model, uploaded_and_verified=True, size_bytes=123).ready
    assert not replace(model, uploaded_and_verified=True, size_bytes=123, sha256="bad").ready
    assert replace(model, uploaded_and_verified=True, size_bytes=123, sha256="a" * 64).ready
