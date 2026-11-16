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
    """A valid 16-bit PCM mono WAV of silence.

    Chirp 2 auto-decodes only containerised 16-bit PCM (or webm/ogg/mp3). A bare
    ``b"\\x00" * n`` blob is headerless and Google rejects it with 400, so the
    silent-audio probe must send a real WAV header to reach recognition.
    """
    frames = int(16000 * 2 * seconds)
    header = (
        b"RIFF" + (36 + frames).to_bytes(4, "little") + b"WAVE"
        + b"fmt " + (16).to_bytes(4, "little") + (1).to_bytes(2, "little")
        + (1).to_bytes(2, "little") + (16000).to_bytes(4, "little")
        + (32000).to_bytes(4, "little") + (2).to_bytes(2, "little")
        + (16).to_bytes(2, "little")
        + b"data" + frames.to_bytes(4, "little")
    )
    return header + b"\x00" * frames


def _tone(seconds=2.0, amplitude=1):
    """A valid 16-bit PCM mono WAV carrying a low-energy tone (near-silence).

    Distinct from true silence: a quiet but non-empty signal exercises the
    low-energy path the adaptation boost could otherwise turn into a
    hallucinated word.
    """
    import math
    frames = int(16000 * seconds)
    samples = bytearray()
    for n in range(frames):
        value = int(amplitude * math.sin(2 * math.pi * 220 * n / 16000))
        samples += value.to_bytes(2, "little", signed=True)
    header = (
        b"RIFF" + (36 + len(samples)).to_bytes(4, "little") + b"WAVE"
        + b"fmt " + (16).to_bytes(4, "little") + (1).to_bytes(2, "little")
        + (1).to_bytes(2, "little") + (16000).to_bytes(4, "little")
        + (32000).to_bytes(4, "little") + (2).to_bytes(2, "little")
        + (16).to_bytes(2, "little")
        + b"data" + len(samples).to_bytes(4, "little")
    )
    return header + bytes(samples)


def _known_good_wav():
    """Reuse a cached real clip (with its locale) so repeats test real audio."""
    from pathlib import Path
    base = Path("/tmp/chirp2_samples")
    if base.exists():
        preferred = base / "en-US"
        search = preferred if preferred.exists() else base
        for wav in sorted(search.glob("*.16k.wav")):
            return ("en-US" if preferred.exists() else wav.parent.name, wav.read_bytes())
    return None


def _rate_limit_burst(limit=12, count=14):
    """Send `count` requests from one IP; the tail must be 429.

    Rate-limited requests are rejected before the Google call, so this costs
    nothing and never reaches the paid API.
    """
    codes = []
    for n in range(count):
        observed = _call(blob=b"", ip="203.0.113.77")
        codes.append(observed["http_status"])
    return codes


def run():
    silence = _silence()
    low_energy = _tone()
    good = _known_good_wav()
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
        ("low_energy_tone", dict(blob=low_energy)),
        ("unreachable_host", dict(base="https://avnivo-does-not-exist.invalid")),
    ]
    if good:
        # Same audio, same locale, three times: recognition must be stable.
        locale, blob = good
        for repeat in range(3):
            probes.append((f"repeat_recognition_{repeat + 1}", dict(blob=blob, locale=locale)))
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

    # Rate-limit burst: the tail of the window must be rejected with 429 and
    # never reach Google, so this costs nothing.
    burst = _rate_limit_burst()
    results.append({
        "probe": "rate_limit_burst",
        "request": {"count": len(burst), "ip": "203.0.113.77"},
        "observed": {
            "codes": burst,
            "http_status": burst[-1],
            "body": f"first 429 at request {burst.index(429) + 1}" if 429 in burst else "no 429 seen",
        },
    })
    print(f"{'rate_limit_burst':32s} {burst}")

    OUT.write_text(json.dumps(results, indent=1, ensure_ascii=False), encoding="utf-8")
    return results


if __name__ == "__main__":
    run()
