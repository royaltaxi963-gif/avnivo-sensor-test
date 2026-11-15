"""Real Google Cloud Chirp 2 verification harness.

This file performs **real** paid Speech-to-Text V2 calls and therefore never
runs by accident. It is skipped unless every one of these is true:

* ``AVNIVO_CHIRP2_LIVE=1`` is set (explicit operator authorization), and
* a speech sample is supplied via ``AVNIVO_CHIRP2_SAMPLE`` (path to a WAV/WebM
  file), and
* Application Default Credentials are available.

Spend is bounded by ``AVNIVO_CHIRP2_LIVE_LOCALES`` (a comma-separated allowlist
of BCP-47 codes; defaults to the four locales AVNIVO currently exposes). Every
call is recorded to ``docs/chirp2-live-verification.json`` as evidence: locale,
sample provenance, model, method, region, HTTP result, transcript, latency and
pass/fail. Mocked tests are never reported as real recognition results.

Usage::

    AVNIVO_CHIRP2_LIVE=1 \\
    AVNIVO_CHIRP2_SAMPLE=/path/to/speech.wav \\
    AVNIVO_CHIRP2_LIVE_LOCALES=uz-UZ,ru-RU,en-US,tr-TR \\
    python -m pytest -q translator-api/test_chirp2_live.py -s

Two modes are supported:

* **Local** (default): calls ``main.app`` through FastAPI's ``TestClient`` using
  Application Default Credentials.
* **Remote**: set ``AVNIVO_CHIRP2_BASE_URL`` to a deployed base URL (for example
  a zero-traffic Cloud Run candidate tag) and the harness POSTs to
  ``<base>/api/transcribe-google`` instead. No local credentials are needed
  because the deployed service holds them.

A single sample may be reused for every locale via ``AVNIVO_CHIRP2_SAMPLE``, or
one sample per locale may be supplied in a directory via
``AVNIVO_CHIRP2_SAMPLE_DIR`` (files named ``<locale>.<ext>``, e.g.
``uz-UZ.webm``).
"""
import json
import os
import time
from pathlib import Path

import pytest

API_DIR = Path(__file__).resolve().parent
REPO_ROOT = API_DIR.parent
REPORT_PATH = REPO_ROOT / "docs" / "chirp2-live-verification.json"

DEFAULT_LOCALES = ("uz-UZ", "ru-RU", "en-US", "tr-TR")


def _live_enabled() -> bool:
    return os.getenv("AVNIVO_CHIRP2_LIVE") == "1"


# Collection must never touch the network. The metadata server is pointed at a
# dead port so a missing credential fails fast instead of hanging on a GCE
# metadata lookup, and the credential probe runs inside the test, not at import.
os.environ.setdefault("GCE_METADATA_HOST", "127.0.0.1:1")


def _credentials_available() -> bool:
    try:
        import google.auth

        google.auth.default(scopes=["https://www.googleapis.com/auth/cloud-platform"])
        return True
    except Exception:
        return False


pytestmark = pytest.mark.skipif(
    not _live_enabled(),
    reason="Set AVNIVO_CHIRP2_LIVE=1 (and supply AVNIVO_CHIRP2_SAMPLE) to run real API calls",
)


def _read_report() -> list:
    if REPORT_PATH.exists():
        try:
            return json.loads(REPORT_PATH.read_text(encoding="utf-8"))
        except ValueError:
            return []
    return []


def _write_report(rows: list) -> None:
    REPORT_PATH.parent.mkdir(parents=True, exist_ok=True)
    REPORT_PATH.write_text(json.dumps(rows, indent=2, ensure_ascii=False), encoding="utf-8")


def _locales() -> list[str]:
    raw = os.getenv("AVNIVO_CHIRP2_LIVE_LOCALES", "")
    codes = [code.strip() for code in raw.split(",") if code.strip()]
    return codes or list(DEFAULT_LOCALES)


def _sample_for(locale: str, sample_dir: str | None):
    """Resolve the speech sample for one locale (per-locale dir or single file)."""
    if sample_dir:
        directory = Path(sample_dir)
        for extension in ("webm", "wav", "ogg", "mp4", "flac"):
            candidate = directory / f"{locale}.{extension}"
            if candidate.is_file():
                return candidate
        return None
    sample = os.getenv("AVNIVO_CHIRP2_SAMPLE")
    return Path(sample) if sample else None


def test_real_chirp2_recognition_per_locale():
    base_url = os.getenv("AVNIVO_CHIRP2_BASE_URL", "").rstrip("/")
    sample_dir = os.getenv("AVNIVO_CHIRP2_SAMPLE_DIR")
    if not base_url and not _credentials_available():
        pytest.skip("No Google Application Default Credentials")
    if not base_url:
        assert os.getenv("AVNIVO_CHIRP2_SAMPLE"), "AVNIVO_CHIRP2_SAMPLE must point at a real speech sample"

    import hashlib

    import chirp2_languages as registry

    if base_url:
        import httpx

        def call(locale: str, sample_path: Path, content_type: str, index: int):
            with httpx.Client(timeout=120) as client:
                return client.post(
                    f"{base_url}/api/transcribe-google",
                    files={"audio": (sample_path.name, sample_path.read_bytes(), content_type)},
                    data={"language": locale},
                    headers={"x-forwarded-for": f"198.18.0.{index}"},
                )
    else:
        from fastapi.testclient import TestClient

        import main

        client = TestClient(main.app)

        def call(locale: str, sample_path: Path, content_type: str, index: int):
            return client.post(
                "/api/transcribe-google",
                files={"audio": (sample_path.name, sample_path.read_bytes(), content_type)},
                data={"language": locale},
                headers={"x-forwarded-for": f"198.18.0.{index}"},
            )

    rows = _read_report()
    failures = []

    for index, locale in enumerate(_locales()):
        assert registry.is_supported_locale(locale), f"{locale} is not a Chirp 2 locale"
        sample_path = _sample_for(locale, sample_dir)
        assert sample_path and sample_path.is_file(), f"no sample for {locale}"
        audio_bytes = sample_path.read_bytes()
        content_type = "audio/wav" if sample_path.suffix.lower() == ".wav" else "audio/webm"
        started = time.perf_counter()
        response = call(locale, sample_path, content_type, index)
        elapsed = round(time.perf_counter() - started, 3)
        transcript = ""
        if response.status_code == 200:
            transcript = response.json().get("text", "")
        row = {
            "locale": locale,
            "sample": sample_path.name,
            "sample_sha256": hashlib.sha256(audio_bytes).hexdigest(),
            "model": registry.GOOGLE_MODEL,
            "method": "Speech.Recognize (sync)",
            "region": registry.GOOGLE_REGION,
            "base_url": base_url or "local TestClient",
            "http_status": response.status_code,
            "transcript": transcript,
            "latency_seconds": elapsed,
            "pass": response.status_code == 200 and bool(transcript.strip()),
            "error": None if response.status_code == 200 else response.text[:300],
        }
        rows = [r for r in rows if r.get("locale") != locale] + [row]
        if not row["pass"]:
            failures.append(row)
        print(f"[live] {locale}: HTTP {response.status_code}, {elapsed}s, text={transcript!r}")

    _write_report(rows)
    assert not failures, f"Real Chirp 2 recognition failed for: {[f['locale'] for f in failures]}"
