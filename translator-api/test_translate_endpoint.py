"""Offline tests for the public /api/translate route and the capability endpoint.

No Google or Groq request is ever made. The Google HTTP call is replaced with a
stub transport so request construction, routing and error mapping are exercised
on the real code path.
"""
import sys
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parent))

import main as api_main  # noqa: E402
from main import app  # noqa: E402
import translation_languages as registry  # noqa: E402

client = TestClient(app)


@pytest.fixture(autouse=True)
def _isolate(monkeypatch):
    from google.auth.exceptions import DefaultCredentialsError

    def no_credentials(scopes=None):
        raise DefaultCredentialsError("offline test: no credentials")

    monkeypatch.setattr("main.google.auth.default", no_credentials)
    api_main.recent.clear()
    yield


def _fake_credentials(monkeypatch):
    class _Creds:
        valid = True
        token = "test-token"

    monkeypatch.setattr("main.google.auth.default", lambda scopes=None: (_Creds(), "project"))


def _capture_transport(monkeypatch, status=200, body=None, record=None):
    captured = record if record is not None else {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["url"] = str(request.url)
        captured["json"] = __import__("json").loads(request.content)
        return httpx.Response(status, json=body if body is not None
                              else {"translations": [{"translatedText": "OK"}]})

    transport = httpx.MockTransport(handler)
    real_client = httpx.AsyncClient

    def patched(*args, **kwargs):
        kwargs["transport"] = transport
        return real_client(*args, **kwargs)

    monkeypatch.setattr("main.httpx.AsyncClient", patched)
    return captured


def test_capability_endpoint_lists_all_locales():
    r = client.get("/api/translation-languages")
    assert r.status_code == 200
    data = r.json()
    assert data["model"] == "general/translation-llm"
    assert data["count"] == 117
    assert data["languages"]["uz-UZ"] == {"translation_code": "uz", "status": "official"}
    assert data["languages"]["yue-Hant-HK"]["status"] == "unsupported"


def test_bcp47_locale_routes_to_audited_code(monkeypatch):
    _fake_credentials(monkeypatch)
    captured = _capture_transport(monkeypatch)
    r = client.post("/api/translate", json={"text": "Salom", "source": "uz-UZ", "target": "ru-RU"})
    assert r.status_code == 200
    assert captured["json"]["sourceLanguageCode"] == "uz"
    assert captured["json"]["targetLanguageCode"] == "ru"
    assert captured["json"]["model"].endswith("/models/general/translation-llm")


def test_legacy_short_codes_still_work(monkeypatch):
    _fake_credentials(monkeypatch)
    captured = _capture_transport(monkeypatch)
    r = client.post("/api/translate", json={"text": "Salom", "source": "uz", "target": "en"})
    assert r.status_code == 200
    assert captured["json"]["sourceLanguageCode"] == "uz"
    assert captured["json"]["targetLanguageCode"] == "en-US"


def test_script_variant_preserved(monkeypatch):
    _fake_credentials(monkeypatch)
    captured = _capture_transport(monkeypatch)
    r = client.post("/api/translate", json={"text": "你好", "source": "cmn-Hant-TW", "target": "en-US"})
    assert r.status_code == 200
    assert captured["json"]["sourceLanguageCode"] == "zh-TW"


def test_unsupported_locale_refused_without_call(monkeypatch):
    _fake_credentials(monkeypatch)
    captured = _capture_transport(monkeypatch)
    r = client.post("/api/translate", json={"text": "Salom", "source": "uz-UZ", "target": "yue-Hant-HK"})
    assert r.status_code == 400
    assert "url" not in captured  # the model must never be called


def test_unknown_locale_refused(monkeypatch):
    _fake_credentials(monkeypatch)
    r = client.post("/api/translate", json={"text": "Salom", "source": "uz-UZ", "target": "xx-YY"})
    assert r.status_code == 400


def test_same_resolved_language_refused(monkeypatch):
    _fake_credentials(monkeypatch)
    # sw and sw-KE both resolve to the same Translation LLM code "sw"; the
    # request must be rejected instead of asking the model to translate a
    # language into itself.
    assert registry.resolve_translation_code("sw") == registry.resolve_translation_code("sw-KE")
    r = client.post("/api/translate", json={"text": "Habari", "source": "sw", "target": "sw-KE"})
    assert r.status_code == 400


def test_empty_and_oversized_text_rejected(monkeypatch):
    _fake_credentials(monkeypatch)
    for text in ("", "   ", "x" * 501, 123):
        r = client.post("/api/translate", json={"text": text, "source": "uz-UZ", "target": "ru-RU"})
        assert r.status_code == 400


def test_upstream_400_maps_to_client_rejection(monkeypatch):
    _fake_credentials(monkeypatch)
    _capture_transport(monkeypatch, status=400, body={"error": {"message": "unsupported"}})
    r = client.post("/api/translate", json={"text": "Salom", "source": "uz-UZ", "target": "ru-RU"})
    assert r.status_code == 400


def test_upstream_429_maps_to_rate_limit(monkeypatch):
    _fake_credentials(monkeypatch)
    _capture_transport(monkeypatch, status=429, body={"error": {}})
    r = client.post("/api/translate", json={"text": "Salom", "source": "uz-UZ", "target": "ru-RU"})
    assert r.status_code == 429


def test_upstream_500_maps_to_provider_error(monkeypatch):
    _fake_credentials(monkeypatch)
    _capture_transport(monkeypatch, status=500, body={"error": {}})
    r = client.post("/api/translate", json={"text": "Salom", "source": "uz-UZ", "target": "ru-RU"})
    assert r.status_code == 502


def test_transport_error_maps_to_502(monkeypatch):
    _fake_credentials(monkeypatch)

    def handler(request):
        raise httpx.ConnectError("down")

    real_client = httpx.AsyncClient

    def patched(*args, **kwargs):
        kwargs["transport"] = httpx.MockTransport(handler)
        return real_client(*args, **kwargs)

    monkeypatch.setattr("main.httpx.AsyncClient", patched)
    r = client.post("/api/translate", json={"text": "Salom", "source": "uz-UZ", "target": "ru-RU"})
    assert r.status_code == 502


def test_missing_credentials_returns_503():
    r = client.post("/api/translate", json={"text": "Salom", "source": "uz-UZ", "target": "ru-RU"})
    assert r.status_code == 503


def test_malformed_response_maps_to_502(monkeypatch):
    _fake_credentials(monkeypatch)
    _capture_transport(monkeypatch, status=200, body={"unexpected": True})
    r = client.post("/api/translate", json={"text": "Salom", "source": "uz-UZ", "target": "ru-RU"})
    assert r.status_code == 502


def test_origin_guard_rejects_foreign_host():
    r = client.post("/api/translate", headers={"origin": "https://evil.example"},
                    json={"text": "Salom", "source": "uz-UZ", "target": "ru-RU"})
    assert r.status_code == 403


def test_experimental_locale_is_routable(monkeypatch):
    _fake_credentials(monkeypatch)
    captured = _capture_transport(monkeypatch)
    r = client.post("/api/translate", json={"text": "Salom", "source": "uz-UZ", "target": "am-ET"})
    assert r.status_code == 200
    assert captured["json"]["targetLanguageCode"] == "am"


def test_every_supported_locale_routes_to_a_nonempty_code():
    caps = registry.capabilities()
    for locale, entry in caps.items():
        if entry["status"] == "unsupported":
            assert registry.resolve_translation_code(locale) is None
        else:
            assert isinstance(registry.resolve_translation_code(locale), str)
            assert registry.resolve_translation_code(locale)
