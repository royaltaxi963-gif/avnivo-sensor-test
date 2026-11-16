"""Local, non-billable reliability and security tests for POST /api/tts.

Every provider interaction is mocked, so these tests never spend money and
never touch the live Google endpoint. They exercise the real FastAPI app and
its real validation, throttling, error-mapping and audio-wrapping code paths.
"""
from __future__ import annotations

import base64
import io
import json
import struct
import sys
import wave
from pathlib import Path
from unittest.mock import patch

import httpx
import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "translator-api"))
import main  # noqa: E402
from main import app  # noqa: E402

client = TestClient(app)

VALID_ORIGIN = "https://raw.githack.com"


def _pcm_wav(seconds: float = 0.3, freq: int = 440, rate: int = 24000) -> bytes:
    """Build a valid mono 16-bit WAV containing a non-silent tone."""
    frames = int(seconds * rate)
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        samples = struct.pack(
            "<%dh" % frames,
            *[int(12000 * (1 if i % 2 else -1)) for i in range(frames)],
        )
        w.writeframes(samples)
    return buf.getvalue()


def _gemini_ok_payload(wav: bytes) -> dict:
    return {"candidates": [{"content": {"parts": [
        {"inlineData": {"data": base64.b64encode(wav).decode()}}
    ]}}]}


class _FakeResponse:
    def __init__(self, status_code: int, payload: dict | None = None, text: str = ""):
        self.status_code = status_code
        self._payload = payload
        self.text = text or json.dumps(payload or {})

    def json(self):
        if self._payload is None:
            raise ValueError("no json")
        return self._payload


class _FakeAsyncClient:
    """Mimics httpx.AsyncClient enough for main.py's usage."""

    def __init__(self, responder):
        self._responder = responder

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False

    async def post(self, url, headers=None, json=None):  # noqa: A002
        result = self._responder(url, headers, json)
        if isinstance(result, Exception):
            raise result
        return result


@pytest.fixture(autouse=True)
def _reset_rate_limit():
    """The throttle store is module-global; isolate every test."""
    main.recent.clear()
    yield
    main.recent.clear()


@pytest.fixture(autouse=True)
def _valid_credentials(monkeypatch):
    """Replace real Google auth with a stub so tests never reach the network."""
    monkeypatch.setattr(main, "google", type("G", (), {
        "auth": type("A", (), {"default": staticmethod(
            lambda scopes=None: (type("C", (), {"valid": True, "token": "stub", "refresh": lambda self, r: None})(), "proj")
        )})()
    }))


def _post(body, headers=None, responder=None):
    headers = {"Content-Type": "application/json", **(headers or {})}
    if responder is None:
        responder = lambda *a, **k: _FakeResponse(200, _gemini_ok_payload(_pcm_wav()))
    with patch.object(main.httpx, "AsyncClient", lambda **kw: _FakeAsyncClient(responder)):
        return client.post("/api/tts", content=json.dumps(body), headers=headers)


# --- Input validation (never reaches the provider) ------------------------

def test_rejects_empty_text():
    assert _post({"text": "", "voice": "Kore"}).status_code == 400


def test_rejects_whitespace_only_text():
    assert _post({"text": "   \n\t ", "voice": "Kore"}).status_code == 400


def test_rejects_text_over_350_chars():
    assert _post({"text": "x" * 351, "voice": "Kore"}).status_code == 400


def test_accepts_text_exactly_350_chars():
    r = _post({"text": "x" * 350, "voice": "Kore"})
    assert r.status_code == 200


def test_rejects_invalid_json():
    with patch.object(main.httpx, "AsyncClient", lambda **kw: _FakeAsyncClient(lambda *a, **k: _FakeResponse(200))):
        r = client.post("/api/tts", content="{not json", headers={"Content-Type": "application/json"})
    assert r.status_code == 400


def test_rejects_non_object_json():
    r = _post(["not", "a", "dict"])
    assert r.status_code == 400


def test_rejects_unsupported_voice():
    assert _post({"text": "Salom", "voice": "Nope"}).status_code == 400


def test_rejects_non_string_voice():
    assert _post({"text": "Salom", "voice": 123}).status_code == 400


@pytest.mark.parametrize("voice", [
    "Kore", "Aoede", "Leda", "Zephyr", "Achernar",
    "Puck", "Charon", "Orus", "Achird", "Iapetus",
])
def test_all_ten_configured_voices_accepted(voice):
    assert _post({"text": "Salom", "voice": voice}).status_code == 200


def test_rejects_oversized_body():
    big = json.dumps({"text": "x" * 5000, "voice": "Kore"})
    r = client.post("/api/tts", content=big, headers={"Content-Type": "application/json"})
    assert r.status_code == 413


def test_unknown_extra_fields_are_ignored():
    # The endpoint reads only known keys; extra fields must not break it.
    assert _post({"text": "Salom", "voice": "Kore", "evil": {"x": 1}}).status_code == 200


def test_language_field_is_ignored_by_backend():
    # The public contract accepts `language`, but the backend does not use it
    # for voice/voice selection: the model auto-detects language.
    assert _post({"text": "Salom", "voice": "Kore", "language": "uz"}).status_code == 200


# --- Origin / CORS --------------------------------------------------------

def test_rejects_disallowed_origin():
    r = _post({"text": "Salom", "voice": "Kore"}, headers={"Origin": "https://evil.example"})
    assert r.status_code == 403


def test_allows_configured_origin():
    r = _post({"text": "Salom", "voice": "Kore"}, headers={"Origin": VALID_ORIGIN})
    assert r.status_code == 200


def test_cors_preflight_allows_configured_origin():
    r = client.options("/api/tts", headers={
        "Origin": VALID_ORIGIN,
        "Access-Control-Request-Method": "POST",
        "Access-Control-Request-Headers": "Content-Type",
    })
    assert r.status_code == 200
    assert r.headers.get("access-control-allow-origin") == VALID_ORIGIN


def test_cors_preflight_rejects_unknown_origin():
    r = client.options("/api/tts", headers={
        "Origin": "https://evil.example",
        "Access-Control-Request-Method": "POST",
    })
    assert r.headers.get("access-control-allow-origin") is None


# --- Provider failure mapping --------------------------------------------

def test_provider_timeout_maps_to_502():
    responder = lambda *a, **k: httpx.ReadTimeout("timeout")
    assert _post({"text": "Salom", "voice": "Kore"}, responder=responder).status_code == 502


def test_provider_connection_error_maps_to_502():
    responder = lambda *a, **k: httpx.ConnectError("down")
    assert _post({"text": "Salom", "voice": "Kore"}, responder=responder).status_code == 502


@pytest.mark.parametrize("status,expected", [(429, 429), (401, 503), (403, 503), (500, 502), (503, 502)])
def test_provider_error_status_mapping(status, expected):
    responder = lambda *a, **k: _FakeResponse(status, text="err")
    assert _post({"text": "Salom", "voice": "Kore"}, responder=responder).status_code == expected


def test_invalid_audio_response_missing_parts():
    responder = lambda *a, **k: _FakeResponse(200, {"candidates": []})
    assert _post({"text": "Salom", "voice": "Kore"}, responder=responder).status_code == 502


def test_malformed_base64_maps_to_502():
    payload = {"candidates": [{"content": {"parts": [
        {"inlineData": {"data": "!!!not-base64!!!"}}
    ]}}]}
    responder = lambda *a, **k: _FakeResponse(200, payload)
    assert _post({"text": "Salom", "voice": "Kore"}, responder=responder).status_code == 502


def test_empty_audio_maps_to_502():
    payload = {"candidates": [{"content": {"parts": [{"inlineData": {"data": ""}}]}}]}
    responder = lambda *a, **k: _FakeResponse(200, payload)
    assert _post({"text": "Salom", "voice": "Kore"}, responder=responder).status_code == 502


def test_oversized_audio_maps_to_502():
    payload = {"candidates": [{"content": {"parts": [
        {"inlineData": {"data": base64.b64encode(b"\x00" * (9 * 1024 * 1024)).decode()}}
    ]}}]}
    responder = lambda *a, **k: _FakeResponse(200, payload)
    assert _post({"text": "Salom", "voice": "Kore"}, responder=responder).status_code == 502


# --- Output handling ------------------------------------------------------

def test_returns_valid_wav_for_unary_audio():
    r = _post({"text": "Salom", "voice": "Kore"})
    assert r.status_code == 200
    assert r.headers["content-type"].startswith("audio/wav")
    with wave.open(io.BytesIO(r.content), "rb") as w:
        assert w.getframerate() == 24000
        assert w.getnchannels() == 1
        assert w.getnframes() > 0


def test_wraps_raw_pcm_into_wav():
    raw_pcm = struct.pack("<%dh" % 2400, *[1000] * 2400)  # headerless PCM
    payload = {"candidates": [{"content": {"parts": [
        {"inlineData": {"data": base64.b64encode(raw_pcm).decode()}}
    ]}}]}
    r = _post({"text": "Salom", "voice": "Kore"}, responder=lambda *a, **k: _FakeResponse(200, payload))
    assert r.status_code == 200
    assert r.content.startswith(b"RIFF")


def test_style_header_reported():
    r = _post({"text": "Rahmat, katta rahmat!", "voice": "Kore"})
    assert r.headers.get("X-AVNIVO-TTS-Style") == "happy"


def test_no_store_cache_header():
    r = _post({"text": "Salom", "voice": "Kore"})
    assert r.headers.get("Cache-Control") == "no-store"


# --- Style detection ------------------------------------------------------

@pytest.mark.parametrize("text,expected", [
    ("Qayerda yurding? Men seni izlayapman.", "concerned"),
    ("Tabriklaymiz! Zo'r yangilik!", "excited"),
    ("Rahmat, juda xursandman.", "happy"),
    ("Afsus, juda achinarli.", "sad"),
    ("Bugun havo yaxshi.", "calm"),
])
def test_style_detection(text, expected):
    assert main._detect_tts_style(text) == expected


def test_all_styles_have_instructions():
    assert set(main._TTS_STYLES) == {"calm", "happy", "sad", "excited", "concerned"}
    assert all(isinstance(v, str) and v for v in main._TTS_STYLES.values())


# --- Rate limiting --------------------------------------------------------

def test_rate_limit_after_twelve_requests():
    main.recent.clear()
    ok = 0
    for _ in range(main.RATE_LIMIT):
        r = _post({"text": "Salom", "voice": "Kore"}, headers={"x-forwarded-for": "203.0.113.9"})
        ok += r.status_code == 200
    assert ok == main.RATE_LIMIT
    blocked = _post({"text": "Salom", "voice": "Kore"}, headers={"x-forwarded-for": "203.0.113.9"})
    assert blocked.status_code == 429
    main.recent.clear()


def test_rate_limit_is_per_ip():
    main.recent.clear()
    for _ in range(main.RATE_LIMIT):
        _post({"text": "Salom", "voice": "Kore"}, headers={"x-forwarded-for": "198.51.100.1"})
    other = _post({"text": "Salom", "voice": "Kore"}, headers={"x-forwarded-for": "198.51.100.2"})
    assert other.status_code == 200
    main.recent.clear()


def test_invalid_requests_do_not_consume_rate_limit():
    main.recent.clear()
    for _ in range(main.RATE_LIMIT + 3):
        _post({"text": "", "voice": "Kore"}, headers={"x-forwarded-for": "203.0.113.77"})
    # A valid request should still succeed because invalid ones were rejected
    # before the throttle counter was incremented.
    r = _post({"text": "Salom", "voice": "Kore"}, headers={"x-forwarded-for": "203.0.113.77"})
    assert r.status_code == 200
    main.recent.clear()
