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
        blob.size = 591649160
        # Distinct audited sizes for the model and its tokenizer.
        storage.return_value.bucket.return_value.blob.side_effect = [blob, MagicMock(size=411, generate_signed_url=MagicMock(return_value="https://example.invalid/tokens"))]
        blob.generate_signed_url.side_effect = ["https://example.invalid/model", "https://example.invalid/tokens"]
        result = client.get("/api/offline-models/uzbek-stt/download-ticket")
    assert result.status_code == 200
    data = result.json()
    assert data["expires_in_seconds"] == 900
    assert len(data["files"]) == 2
    assert data["files"][0]["sha256"] == "8bff17ef8d7da04369a559b99b71c1155a90fc604d35d84c6d4e44159c1b0873"
    assert data["files"][1]["bytes"] == 411
    assert all(f["url"].startswith("https://") for f in data["files"])


def test_extra_models_fail_closed_without_audited_manifest(monkeypatch):
    monkeypatch.delenv("AVNIVO_AUDITED_MODEL_MANIFEST", raising=False)
    for model in ("whisper-small-q8", "whisper-large-v3-turbo",
                  "nllb-200-600m-int8", "navoiy-tts", "mms-tts"):
        assert client.get(f"/api/offline-models/{model}/download-ticket").status_code == 404

def test_audited_manifest_rejects_path_escape(monkeypatch):
    import offline_models
    manifest = {"navoiy-tts": [{"object": "tts/uzbek/navoiy/../../private.key",
                                "name": "model.pt", "bytes": 10, "sha256": "a"*64}]}
    monkeypatch.setenv("AVNIVO_AUDITED_MODEL_MANIFEST", __import__("json").dumps(manifest))
    assert "navoiy-tts" not in offline_models._load_audited_models()

def test_audited_manifest_accepts_fixed_prefix(monkeypatch):
    import offline_models
    manifest = {"navoiy-tts": [{"object": "tts/uzbek/navoiy/model.pt",
                                "name": "model.pt", "bytes": 10, "sha256": "a"*64}]}
    monkeypatch.setenv("AVNIVO_AUDITED_MODEL_MANIFEST", __import__("json").dumps(manifest))
    assert offline_models._load_audited_models()["navoiy-tts"][0]["bytes"] == 10

def test_ticket_rejects_untrusted_browser_origin():
    result = client.get("/api/offline-models/uzbek-stt/download-ticket",
                        headers={"Origin": "https://evil.example"})
    assert result.status_code == 403

def test_ticket_allows_preview_origin_to_reach_signer_check(monkeypatch):
    monkeypatch.delenv("AVNIVO_SIGNER_SERVICE_ACCOUNT", raising=False)
    with patch("offline_models.google_auth_default") as auth:
        auth.return_value = (MagicMock(valid=True, token="test"), "project")
        result = client.get("/api/offline-models/uzbek-stt/download-ticket",
                            headers={"Origin": "https://raw.githack.com"})
    assert result.status_code == 503

def test_ticket_rejects_resized_cloud_object(monkeypatch):
    monkeypatch.setenv("AVNIVO_SIGNER_SERVICE_ACCOUNT", "test@example.iam.gserviceaccount.com")
    with patch("offline_models.google_auth_default") as auth, patch("offline_models.storage.Client") as storage:
        auth.return_value = (MagicMock(valid=True, token="test"), "project")
        storage.return_value.bucket.return_value.blob.return_value.size = 123
        result = client.get("/api/offline-models/uzbek-stt/download-ticket")
    assert result.status_code == 503
    assert "url" not in result.text

def test_ticket_allows_android_asset_origin_and_reports_missing_signer(monkeypatch):
    monkeypatch.delenv("AVNIVO_SIGNER_SERVICE_ACCOUNT", raising=False)
    result = client.get("/api/offline-models/uzbek-stt/download-ticket",
                        headers={"Origin": "https://appassets.androidplatform.net"})
    assert result.status_code == 503
    assert result.json()["detail"] == "Model download signer is not configured"
    assert "url" not in result.text
