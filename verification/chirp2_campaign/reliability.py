"""Reliability probes against the deployed Chirp 2 endpoint.

Covers the failure modes the brief lists: bad locale, bad audio, wrong content
type, oversized/empty audio, CORS origin enforcement, unreachable host and empty
speech. Requests rejected before recognition are free; the few that reach Google
carry only a couple of seconds of audio and are counted in the budget.
"""
from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
BASE_URL = os.getenv(
    "AVNIVO_CHIRP2_BASE_URL",
    "https://chirp2-test---avnivo-sensor-test-5xwrf7netq-ew.a.run.app",
)
OUT = HERE / "reliability.json"


def _multipart(locale, blob, filename, content_type):
    boundary = uuid.uuid4().hex
    parts = []
    if locale is not None:
        parts.append(
            f'--{boundary}\r\nContent-Disposition: form-data; name="language"\r\n\r\n{locale}\r\n'.encode()
        )
    parts.append(
        f'--{boundary}\r\nContent-Disposition: form-data; name="audio"; filename="{filename}"\r\n'
        f"Content-Type: {content_type}\r\n\r\n".encode()
    )
    parts.append(blob + b"\r\n")
    parts.append(f"--{boundary}--\r\n".encode())
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def _call(path="/api/transcribe-google", locale="uz-UZ", blob=b"\x00" * 4096,
          filename="s.wav", content_type="audio/wav", origin=None,
          base=BASE_URL, ip="198.18.250.1"):
    body, ctype = _multipart(locale, blob, filename, content_type)
    headers = {"Content-Type": ctype, "x-forwarded-for": ip}
    if origin:
        headers["Origin"] = origin
    request = urllib.request.Request(base + path, data=body, headers=headers)
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            payload = response.read().decode("utf-8", "replace")
            status = response.status
    except urllib.error.HTTPError as error:
        payload = error.read().decode("utf-8", "replace")
        status = error.code
    except Exception as error:
        payload = json.dumps({"error": f"{type(error).__name__}: {error}"})
        status = 0
    return {"http_status": status, "body": payload[:400],
            "latency_seconds": round(time.perf_counter() - started, 3)}


def _silence(seconds=2.0):
    return b"\x00" * int(16000 * 2 * seconds)


def run():
    silence = _silence()
    probes = [
        ("unsupported_locale_xx", dict(locale="xx-XX")),
        ("unsupported_locale_ar-AE", dict(locale="ar-AE")),
        ("base_code_not_locale", dict(locale="uz")),
        ("missing_language_field", dict(locale=None)),
        ("wrong_content_type", dict(content_type="text/plain", filename="s.txt")),
        ("empty_audio", dict(blob=b"")),
        ("oversized_audio", dict(blob=b"\x00" * (10 * 1024 * 1024 + 1))),
        ("cors_disallowed_origin", dict(origin="https://evil.example")),
        ("cors_allowed_origin", dict(origin="https://royaltaxi963-gif.github.io", blob=silence)),
        ("garbage_bytes_as_wav", dict(blob=os.urandom(8192), content_type="audio/wav")),
        ("silent_audio_empty_result", dict(blob=silence)),
        ("unreachable_host", dict(base="https://avnivo-does-not-exist.invalid")),
    ]
    results = []
    for index, (name, kwargs) in enumerate(probes):
        kwargs.setdefault("ip", f"198.19.{index // 250}.{index % 250}")
        observed = _call(**kwargs)
        results.append({
            "probe": name,
            "request": {k: (v if not isinstance(v, bytes) else f"<{len(v)} bytes>") for k, v in kwargs.items()},
            "observed": observed,
        })
        print(f"{name:32s} HTTP {observed['http_status']} {observed['body'][:70]}")
    OUT.write_text(json.dumps(results, indent=1, ensure_ascii=False), encoding="utf-8")
    return results


if __name__ == "__main__":
    run()
