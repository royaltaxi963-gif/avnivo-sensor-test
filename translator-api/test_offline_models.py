"""No cloud calls: validate fixed allowlist, ticket metadata and error handling."""
from unittest.mock import patch, MagicMock
from fastapi.testclient import TestClient
from main import app

client = TestClient(app)

def test_unknown_model_rejected():
    assert client.get("/api/offline-models/../download-ticket").status_code in (404, 405)
    assert client.get("/api/offline-models/unknown/download-ticket").status_code == 404

def test_missing_signer_fails_closed(monkeypatch):
    monkeypatch.delenv("AVNIVO_SIGNER_SERVICE_ACCOUNT", raising=False)
    with patch("offline_models.google_auth_default") as auth:
        cred = MagicMock(valid=True, token="test")
        auth.return_value = (cred, "project")
        result = client.get("/api/offline-models/uzbek-stt/download-ticket")
    assert result.status_code == 503
    assert "url" not in result.text

def test_signed_ticket_contains_verified_hashes(monkeypatch):
    monkeypatch.setenv("AVNIVO_SIGNER_SERVICE_ACCOUNT", "test@example.iam.gserviceaccount.com")
    with patch("offline_models.google_auth_default") as auth, patch("offline_models.storage.Client") as storage:
        auth.return_value = (MagicMock(valid=True, token="test"), "project")
        blob = storage.return_value.bucket.return_value.blob.return_value
        blob.generate_signed_url.side_effect = ["https://example.invalid/model", "https://example.invalid/tokens"]
        result = client.get("/api/offline-models/uzbek-stt/download-ticket")
    assert result.status_code == 200
    data = result.json()
    assert data["expires_in_seconds"] == 900
    assert len(data["files"]) == 2
    assert data["files"][0]["sha256"] == "8bff17ef8d7da04369a559b99b71c1155a90fc604d35d84c6d4e44159c1b0873"
    assert data["files"][1]["bytes"] == 411
    assert all(f["url"].startswith("https://") for f in data["files"])


def test_verified_navoiy_and_mms_manifests_are_loadable():
    from offline_models import load_verified_models
    models = load_verified_models()
    assert len(models["navoiy-tts"]) == 22
    assert len(models["mms-tts"]) == 6
    for model in ("navoiy-tts", "mms-tts"):
        assert all(len(f["sha256"]) == 64 and f["object"] and f["bytes"] >= 0 for f in models[model])


def test_verified_model_ticket_signing(monkeypatch):
    monkeypatch.setenv("AVNIVO_SIGNER_SERVICE_ACCOUNT", "test@example.iam.gserviceaccount.com")
    from offline_models import load_verified_models
    with patch("offline_models.google_auth_default") as auth, patch("offline_models.storage.Client") as storage:
        auth.return_value = (MagicMock(valid=True, token="test"), "project")
        blob = storage.return_value.bucket.return_value.blob.return_value
        blob.generate_signed_url.return_value = "https://example.invalid/signed"
        for model_id in ("navoiy-tts", "mms-tts"):
            result = client.get(f"/api/offline-models/{model_id}/download-ticket")
            assert result.status_code == 200, result.text
            data = result.json()
            assert len(data["files"]) == len(load_verified_models()[model_id])
            assert all(f["url"].startswith("https://") and len(f["sha256"]) == 64 for f in data["files"])


def test_unverified_models_not_advertised():
    from offline_models import load_verified_models
    models = load_verified_models()
    for model in ("whisper-small-q8", "whisper-large-v3-turbo", "nllb-200-600m-int8"):
        assert model not in models


def test_browser_cors_allows_ticket_get():
    response = client.options(
        "/api/offline-models/uzbek-stt/download-ticket",
        headers={
            "Origin": "https://royaltaxi963-gif.github.io",
            "Access-Control-Request-Method": "GET",
        },
    )
    assert response.status_code == 200
    assert response.headers["access-control-allow-origin"] == "https://royaltaxi963-gif.github.io"
    assert "GET" in response.headers["access-control-allow-methods"]


def test_nested_model_file_disposition_uses_basename(monkeypatch):
    monkeypatch.setenv("AVNIVO_SIGNER_SERVICE_ACCOUNT", "test@example.iam.gserviceaccount.com")
    from offline_models import load_verified_models
    nested = [f for f in load_verified_models()["navoiy-tts"] if "/" in f["name"]]
    assert nested, "Navoiy manifest must include nested assets for this regression"
    with patch("offline_models.google_auth_default") as auth, patch("offline_models.storage.Client") as storage:
        auth.return_value = (MagicMock(valid=True, token="test"), "project")
        blob = storage.return_value.bucket.return_value.blob.return_value
        blob.generate_signed_url.return_value = "https://example.invalid/signed"
        response = client.get("/api/offline-models/navoiy-tts/download-ticket")
        assert response.status_code == 200
        dispositions = [call.kwargs["response_disposition"] for call in blob.generate_signed_url.call_args_list]
        assert all("/" not in value for value in dispositions)
        assert len(dispositions) == len(load_verified_models()["navoiy-tts"])
