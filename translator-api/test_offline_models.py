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
