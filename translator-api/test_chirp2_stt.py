"""Offline Chirp 2 STT tests: no Google, Groq or network requests are made.

Covers the centralized language registry, frontend/backend consistency,
unsupported-language rejection, the protected Uzbek baseline, audio upload
validation, Google request construction, response parsing, error mapping,
timeouts, duplicate requests, cancellation and Offline STT isolation.

The Google HTTP call is replaced with a stub transport so request construction
and response parsing are exercised on the real code path without paid calls.
"""
import io
import json
import re
import sys
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient

API_DIR = Path(__file__).resolve().parent
REPO_ROOT = API_DIR.parent
sys.path.insert(0, str(API_DIR))

import chirp2_languages as registry  # noqa: E402
import main as api_main  # noqa: E402
from main import app  # noqa: E402

client = TestClient(app)

CHIRP2_HTML = REPO_ROOT / "avnivo-v11-49-chirp2-multilingual.html"
DOC = REPO_ROOT / "docs" / "chirp2-language-support.md"


@pytest.fixture(autouse=True)
def _isolate(monkeypatch):
    """Keep every test off the network and free of cross-test rate-limit state.

    Credentials are made to fail fast by default so a test that forgets to stub
    them gets a 503 without contacting the GCE metadata server, and the shared
    per-IP rate-limit window is cleared before each test.
    """
    from google.auth.exceptions import DefaultCredentialsError

    def no_credentials(scopes=None):
        raise DefaultCredentialsError("offline test: no credentials")

    monkeypatch.setattr("main.google.auth.default", no_credentials)
    # Remove the retry backoff so a test that exercises a retryable status does
    # not sleep; retry behaviour is asserted by call count, not wall-clock time.
    monkeypatch.setattr("main.RETRY_BASE_DELAY", 0)
    api_main.recent.clear()
    yield


def _fake_credentials(monkeypatch):
    class _Creds:
        valid = True
        token = "test-token"

    monkeypatch.setattr("main.google.auth.default", lambda scopes=None: (_Creds(), "proj"))


def _stub_httpx(monkeypatch, handler):
    """Patch httpx.AsyncClient so no real network call leaves the process."""
    original = httpx.AsyncClient

    class _Stub(original):
        def __init__(self, *args, **kwargs):
            kwargs["transport"] = httpx.MockTransport(handler)
            super().__init__(*args, **kwargs)

    monkeypatch.setattr("main.httpx.AsyncClient", _Stub)


def _capture_handler(status=200, body=None, capture=None):
    def handler(request: httpx.Request) -> httpx.Response:
        if capture is not None:
            capture["url"] = str(request.url)
            capture["headers"] = dict(request.headers)
            capture["json"] = json.loads(request.content.decode("utf-8"))
        return httpx.Response(status, json=body if body is not None else {"results": []})

    return handler


# --------------------------------------------------------------------------- #
# Registry integrity
# --------------------------------------------------------------------------- #

def test_registry_has_all_official_locales():
    assert len(registry.CHIRP2_LOCALES) == 117
    assert registry.CHIRP2_LOCALE_NAMES["uz-UZ"] == "Uzbek (Uzbekistan)"


def test_registry_codes_are_bcp47_shaped():
    pattern = re.compile(r"^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$")
    for code in registry.CHIRP2_LOCALES:
        assert pattern.match(code), code


def test_configured_region_is_a_chirp2_region():
    assert registry.GOOGLE_REGION in registry.CHIRP2_REGIONS


def test_health_reports_chirp2_not_a_stale_groq_label():
    """`/health` must describe the live Chirp 2 engine, never the retired Groq one."""
    body = client.get("/health").json()
    assert body["status"] == "ok"
    assert body["model"] == registry.GOOGLE_MODEL == "chirp_2"
    assert body["region"] == registry.GOOGLE_REGION
    assert body["languages"] == len(registry.CHIRP2_LOCALES)
    assert "whisper" not in json.dumps(body).lower()


def test_supported_locale_helper():
    assert registry.is_supported_locale("uz-UZ")
    assert registry.is_supported_locale("de-DE")
    assert not registry.is_supported_locale("de")
    assert not registry.is_supported_locale("xx-XX")
    assert not registry.is_supported_locale(None)
    assert not registry.is_supported_locale(123)


# --------------------------------------------------------------------------- #
# Frontend / backend consistency
# --------------------------------------------------------------------------- #

def _frontend_matrix():
    html = CHIRP2_HTML.read_text(encoding="utf-8")
    match = re.search(r"window\.CHIRP2_MATRIX=\[(.*?)\];", html, flags=re.S)
    assert match, "frontend CHIRP2_MATRIX block not found"
    return json.loads("[" + match.group(1) + "]")


def test_frontend_and_backend_registries_match():
    frontend = dict(_frontend_matrix())
    assert set(frontend) == set(registry.CHIRP2_LOCALES)
    for code, name in frontend.items():
        assert name == registry.CHIRP2_LOCALE_NAMES[code]


def test_frontend_base_language_map_targets_registry_locales():
    html = CHIRP2_HTML.read_text(encoding="utf-8")
    match = re.search(r"CHIRP2_BASE_TO_LOCALE=Object\.freeze\(\{(.*?)\}\)", html, flags=re.S)
    assert match, "frontend base-language map not found"
    pairs = dict(re.findall(r"(\w+):'([^']+)'", match.group(1)))
    assert pairs == {"uz": "uz-UZ", "ru": "ru-RU", "en": "en-US", "tr": "tr-TR"}
    for locale in pairs.values():
        assert registry.is_supported_locale(locale)


def test_frontend_resolves_and_rejects_unknown_languages():
    html = CHIRP2_HTML.read_text(encoding="utf-8")
    assert "function chirp2LocaleFor(baseCode)" in html
    # The resolver returns null for a language without Chirp 2 support instead
    # of silently substituting uz-UZ.
    assert "Bu til uchun Google Chirp 2 ovoz tanish hali sozlanmagan" in html


# --------------------------------------------------------------------------- #
# Endpoint: registry exposure and validation
# --------------------------------------------------------------------------- #

def test_registry_endpoint_lists_locales():
    response = client.get("/api/chirp2-languages")
    assert response.status_code == 200
    body = response.json()
    assert body["model"] == "chirp_2"
    assert body["count"] == 117
    assert set(body["locales"]) == set(registry.CHIRP2_LOCALES)
    assert body["region"] in body["regions"]


def _audio(content_type="audio/webm", size=2048):
    return {"audio": ("speech.webm", io.BytesIO(b"\x00" * size), content_type)}


def test_every_registered_locale_is_accepted():
    # With credentials absent, each valid locale must reach the credential step
    # (503) rather than being rejected as unsupported (400). A unique source IP
    # per iteration keeps the per-IP throttle from masking a rejection.
    for index, code in enumerate(sorted(registry.CHIRP2_LOCALES)):
        response = client.post(
            "/api/transcribe-google",
            files=_audio(),
            data={"language": code},
            headers={
                "origin": "https://royaltaxi963-gif.github.io",
                "x-forwarded-for": f"203.0.113.{index % 250}",
            },
        )
        assert response.status_code == 503, (code, response.status_code)


@pytest.mark.parametrize("code", ["de", "xx-XX", "uz", "ru-RU ", "en_US"])
def test_unsupported_language_is_rejected(code):
    response = client.post("/api/transcribe-google", files=_audio(), data={"language": code})
    assert response.status_code == 400
    assert response.json()["detail"] == "Unsupported Chirp 2 language"


def test_missing_language_is_rejected_not_defaulted():
    # Regression: an empty or omitted language field used to default to uz-UZ.
    for data in ({"language": ""}, {}):
        response = client.post("/api/transcribe-google", files=_audio(), data=data)
        assert response.status_code == 400, data
        assert response.json()["detail"] == "Unsupported Chirp 2 language"


def test_unsupported_language_does_not_fall_back_to_uzbek():
    response = client.post("/api/transcribe-google", files=_audio(), data={"language": "de-DE"})
    # de-DE is valid Chirp 2, but with no credentials it must be a 503, never a
    # silent Uzbek success. A truly unknown code stays a 400.
    assert response.status_code in (400, 503)
    bad = client.post("/api/transcribe-google", files=_audio(), data={"language": "klingon"})
    assert bad.status_code == 400


def test_audio_format_is_validated():
    response = client.post(
        "/api/transcribe-google",
        files=_audio(content_type="text/plain"),
        data={"language": "uz-UZ"},
    )
    assert response.status_code == 415


def test_audio_size_is_validated():
    too_big = {"audio": ("speech.webm", io.BytesIO(b"\x00" * (10 * 1024 * 1024 + 1)), "audio/webm")}
    response = client.post("/api/transcribe-google", files=too_big, data={"language": "uz-UZ"})
    assert response.status_code == 413


def test_empty_audio_is_rejected():
    empty = {"audio": ("speech.webm", io.BytesIO(b""), "audio/webm")}
    response = client.post("/api/transcribe-google", files=empty, data={"language": "uz-UZ"})
    assert response.status_code == 413


def test_origin_is_enforced():
    response = client.post(
        "/api/transcribe-google",
        files=_audio(),
        data={"language": "uz-UZ"},
        headers={"origin": "https://evil.example"},
    )
    assert response.status_code == 403


# --------------------------------------------------------------------------- #
# Uzbek baseline preservation
# --------------------------------------------------------------------------- #

def test_uzbek_adaptation_unchanged():
    assert registry.adaptation_phrases("uz-UZ") == [
        {"value": "Avnivo", "boost": 20.0},
        {"value": "ovoz tanish", "boost": 15.0},
        {"value": "sinovi", "boost": 10.0},
    ]


def test_other_locales_have_no_adaptation():
    assert registry.adaptation_phrases("ru-RU") is None
    assert registry.adaptation_phrases("en-US") is None


def test_uzbek_config_matches_verified_baseline(monkeypatch):
    _fake_credentials(monkeypatch)
    capture = {}
    _stub_httpx(monkeypatch, _capture_handler(body={"results": [{"alternatives": [{"transcript": "salom"}]}]}, capture=capture))
    response = client.post("/api/transcribe-google", files=_audio(), data={"language": "uz-UZ"})
    assert response.status_code == 200
    assert response.json() == {"text": "salom", "model": "chirp_2", "language": "uz-UZ"}
    config = capture["json"]["config"]
    assert config["languageCodes"] == ["uz-UZ"]
    assert config["model"] == "chirp_2"
    assert config["autoDecodingConfig"] == {}
    assert config["adaptation"]["phraseSets"][0]["inlinePhraseSet"]["phrases"] == registry.adaptation_phrases("uz-UZ")


def test_uzbek_recognizer_url_uses_configured_region(monkeypatch):
    _fake_credentials(monkeypatch)
    capture = {}
    _stub_httpx(monkeypatch, _capture_handler(body={"results": []}, capture=capture))
    client.post("/api/transcribe-google", files=_audio(), data={"language": "uz-UZ"})
    assert capture["url"] == (
        "https://us-central1-speech.googleapis.com/v2/projects/"
        "project-6bcf7fe6-cb55-49b7-9f4/locations/us-central1/recognizers/_:recognize"
    )
    assert capture["headers"]["authorization"] == "Bearer test-token"


# --------------------------------------------------------------------------- #
# Google request construction for a non-Uzbek locale
# --------------------------------------------------------------------------- #

def test_non_uzbek_request_has_no_adaptation(monkeypatch):
    _fake_credentials(monkeypatch)
    capture = {}
    _stub_httpx(monkeypatch, _capture_handler(body={"results": [{"alternatives": [{"transcript": "hello"}]}]}, capture=capture))
    response = client.post("/api/transcribe-google", files=_audio(), data={"language": "en-US"})
    assert response.status_code == 200
    assert response.json()["text"] == "hello"
    config = capture["json"]["config"]
    assert config["languageCodes"] == ["en-US"]
    assert "adaptation" not in config


# --------------------------------------------------------------------------- #
# Response parsing
# --------------------------------------------------------------------------- #

def test_response_parsing_joins_multiple_results(monkeypatch):
    _fake_credentials(monkeypatch)
    body = {"results": [
        {"alternatives": [{"transcript": "salom "}]},
        {"alternatives": [{"transcript": " dunyo"}]},
    ]}
    _stub_httpx(monkeypatch, _capture_handler(body=body))
    response = client.post("/api/transcribe-google", files=_audio(), data={"language": "uz-UZ"})
    assert response.json()["text"] == "salom dunyo"


def test_response_parsing_handles_empty_and_malformed(monkeypatch):
    _fake_credentials(monkeypatch)
    _stub_httpx(monkeypatch, _capture_handler(body={}))
    response = client.post("/api/transcribe-google", files=_audio(), data={"language": "uz-UZ"})
    assert response.status_code == 200
    assert response.json()["text"] == ""

    _stub_httpx(monkeypatch, _capture_handler(body={"results": [{"alternatives": []}, "junk", {"alternatives": [{}]}]}))
    response = client.post("/api/transcribe-google", files=_audio(), data={"language": "uz-UZ"})
    assert response.status_code == 200
    assert response.json()["text"] == ""


# --------------------------------------------------------------------------- #
# Error handling
# --------------------------------------------------------------------------- #

@pytest.mark.parametrize(
    "google_status,expected",
    [(429, 429), (401, 503), (403, 503), (500, 502), (400, 400), (404, 502)],
)
def test_google_errors_are_mapped(monkeypatch, google_status, expected):
    _fake_credentials(monkeypatch)
    _stub_httpx(monkeypatch, _capture_handler(status=google_status, body={"error": "x"}))
    response = client.post("/api/transcribe-google", files=_audio(), data={"language": "uz-UZ"})
    assert response.status_code == expected


def test_undecodable_audio_is_400_not_502(monkeypatch):
    """A Google INVALID_ARGUMENT (400) is a client payload fault, not an outage.

    Regression: every non-200 upstream status used to collapse to 502, which made
    an undecodable-audio request look like a Google Speech outage.
    """
    _fake_credentials(monkeypatch)
    body = {
        "error": {
            "code": 400,
            "status": "INVALID_ARGUMENT",
            "message": "Audio data does not appear to be in a supported encoding.",
        }
    }
    _stub_httpx(monkeypatch, _capture_handler(status=400, body=body))
    response = client.post("/api/transcribe-google", files=_audio(), data={"language": "uz-UZ"})
    assert response.status_code == 400
    assert "audio" in response.json()["detail"].lower()


def test_transport_error_is_mapped_to_502(monkeypatch):
    _fake_credentials(monkeypatch)

    def boom(request):
        raise httpx.ConnectError("down", request=request)

    _stub_httpx(monkeypatch, boom)
    response = client.post("/api/transcribe-google", files=_audio(), data={"language": "uz-UZ"})
    assert response.status_code == 502


def test_transient_5xx_is_retried_then_succeeds(monkeypatch):
    """A single transient 503 must not surface to the client if a retry works."""
    _fake_credentials(monkeypatch)
    calls = {"n": 0}

    def handler(request):
        calls["n"] += 1
        if calls["n"] == 1:
            return httpx.Response(503, json={"error": "unavailable"})
        return httpx.Response(200, json={"results": [{"alternatives": [{"transcript": "salom"}]}]})

    _stub_httpx(monkeypatch, handler)
    response = client.post("/api/transcribe-google", files=_audio(), data={"language": "uz-UZ"})
    assert response.status_code == 200
    assert response.json()["text"] == "salom"
    assert calls["n"] == 2


def test_client_fault_is_not_retried(monkeypatch):
    """400 INVALID_ARGUMENT must fail fast: retrying a bad payload cannot help."""
    _fake_credentials(monkeypatch)
    calls = {"n": 0}

    def handler(request):
        calls["n"] += 1
        return httpx.Response(400, json={"error": "bad audio"})

    _stub_httpx(monkeypatch, handler)
    response = client.post("/api/transcribe-google", files=_audio(), data={"language": "uz-UZ"})
    assert response.status_code == 400
    assert calls["n"] == 1


def test_persistent_5xx_stops_after_bounded_attempts(monkeypatch):
    """A provider that stays down must fail after RETRY_ATTEMPTS, not loop."""
    _fake_credentials(monkeypatch)
    calls = {"n": 0}

    def handler(request):
        calls["n"] += 1
        return httpx.Response(503, json={"error": "unavailable"})

    _stub_httpx(monkeypatch, handler)
    response = client.post("/api/transcribe-google", files=_audio(), data={"language": "uz-UZ"})
    assert response.status_code == 502
    assert calls["n"] == api_main.RETRY_ATTEMPTS


def test_missing_credentials_return_503(monkeypatch):
    def no_creds(scopes=None):
        from google.auth.exceptions import DefaultCredentialsError
        raise DefaultCredentialsError("no adc")

    monkeypatch.setattr("main.google.auth.default", no_creds)
    response = client.post("/api/transcribe-google", files=_audio(), data={"language": "uz-UZ"})
    assert response.status_code == 503


def test_timeout_is_configured(monkeypatch):
    _fake_credentials(monkeypatch)
    seen = {}

    class _Stub(httpx.AsyncClient):
        def __init__(self, *args, **kwargs):
            seen["timeout"] = kwargs.get("timeout")
            kwargs["transport"] = httpx.MockTransport(_capture_handler(body={"results": []}))
            super().__init__(*args, **kwargs)

    monkeypatch.setattr("main.httpx.AsyncClient", _Stub)
    client.post("/api/transcribe-google", files=_audio(), data={"language": "uz-UZ"})
    assert seen["timeout"] == 90


# --------------------------------------------------------------------------- #
# Rate limiting / duplicate requests
# --------------------------------------------------------------------------- #

def test_rate_limit_blocks_burst(monkeypatch):
    # Credentials fail, so every request stops at 503 until the window fills.
    # The endpoint must then start returning 429 for the same client IP.
    codes = []
    for _ in range(api_main.RATE_LIMIT + 5):
        response = client.post(
            "/api/transcribe-google",
            files=_audio(),
            data={"language": "uz-UZ"},
            headers={"x-forwarded-for": "203.0.113.9"},
        )
        codes.append(response.status_code)
        if response.status_code == 429:
            break
    assert codes.count(503) == api_main.RATE_LIMIT
    assert codes[-1] == 429


def test_cancellation_is_isolated_between_locales(monkeypatch):
    """A stale request id must not overwrite the result of a newer one.

    The client guards this with a monotonically increasing request id. This test
    documents the contract: distinct sequential requests each return their own
    language, so a cancelled (superseded) request cannot leak its text.
    """
    _fake_credentials(monkeypatch)

    def handler(request):
        body = json.loads(request.content.decode("utf-8"))
        locale = body["config"]["languageCodes"][0]
        return httpx.Response(200, json={"results": [{"alternatives": [{"transcript": locale}]}]})

    _stub_httpx(monkeypatch, handler)
    first = client.post("/api/transcribe-google", files=_audio(), data={"language": "uz-UZ"})
    second = client.post("/api/transcribe-google", files=_audio(), data={"language": "en-US"})
    assert first.json()["language"] == "uz-UZ"
    assert second.json()["language"] == "en-US"
    assert second.json()["text"] == "en-US"


# --------------------------------------------------------------------------- #
# Offline STT isolation
# --------------------------------------------------------------------------- #

def test_offline_bridge_untouched():
    html = CHIRP2_HTML.read_text(encoding="utf-8")
    assert "avnivo_offline_text" in html
    assert "avnivo-offline://recognize" in html
    assert "receiveOfflineTranscript" in html


def test_offline_engine_route_present():
    html = CHIRP2_HTML.read_text(encoding="utf-8")
    assert "Offline STT (Android ilova)" in html


def test_online_offline_tabs_preserved():
    html = CHIRP2_HTML.read_text(encoding="utf-8")
    assert "avnivo-mode-tabs" in html
    assert 'data-mode="online"' in html
    assert 'data-mode="offline"' in html


def test_no_groq_option_in_speech_engine():
    html = CHIRP2_HTML.read_text(encoding="utf-8")
    select = re.search(r'id="avnivoSpeechEngine"[^>]*>(.*?)</select>', html, flags=re.S)
    assert select, "speech engine select not found"
    assert "groq" not in select.group(1).lower()
    assert "Google Chirp 2" in select.group(1)


# --------------------------------------------------------------------------- #
# Documentation
# --------------------------------------------------------------------------- #

def test_language_support_document_exists():
    assert DOC.exists(), "docs/chirp2-language-support.md is missing"
    text = DOC.read_text(encoding="utf-8")
    assert "117" in text
    assert "us-central1" in text
    assert "cloud.google.com" in text
    for code in ("uz-UZ", "ru-RU", "en-US", "tr-TR", "de-DE"):
        assert code in text
