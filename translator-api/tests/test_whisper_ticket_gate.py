"""Offline model API safety gates: no paid signing or GCS calls for unverified models."""
from fastapi.testclient import TestClient
from main import app

client = TestClient(app)


def test_whisper_small_unverified_returns_503():
    response = client.get("/api/offline-models/whisper-small-q8_0/download-ticket")
    assert response.status_code == 503
    assert "not uploaded and verified" in response.json()["detail"]


def test_whisper_turbo_unverified_returns_503():
    response = client.get("/api/offline-models/whisper-large-v3-turbo-q5_0/download-ticket")
    assert response.status_code == 503


def test_unknown_model_returns_404():
    assert client.get("/api/offline-models/other/download-ticket").status_code == 404


def test_existing_uzbek_allowlist_is_preserved():
    from offline_models import OBJECTS
    assert "uzbek-stt" in OBJECTS
    assert [file["name"] for file in OBJECTS["uzbek-stt"]] == [
        "model.int8.onnx", "tokens.txt"
    ]
