"""Objective WAV integrity analysis for AVNIVO TTS samples.

Uses only the Python standard library so it can run anywhere without numpy.
No subjective quality (MOS, naturalness, pronunciation) is produced here;
those are explicitly out of scope and must be flagged for human review.
"""
from __future__ import annotations

import io
import math
import struct
import wave
from dataclasses import dataclass, asdict

# Threshold below which a sample is treated as silence, relative to peak.
_SILENCE_RATIO = 0.01
# Excess trailing/leading silence that is considered suspicious (seconds).
_MAX_EDGE_SILENCE_S = 1.0
# Maximum share of near-full-scale samples tolerated before flagging clipping.
_MAX_CLIP_FRACTION = 0.001


@dataclass
class AudioMetrics:
    valid_wav: bool
    error: str | None
    channels: int | None
    sampwidth: int | None
    framerate: int | None
    frames: int | None
    duration_s: float | None
    peak: int | None
    rms: float | None
    peak_dbfs: float | None
    rms_dbfs: float | None
    silence_ratio: float | None
    lead_silence_s: float | None
    trail_silence_s: float | None
    clipped_samples: int | None
    dc_offset: float | None
    all_silent: bool
    checks: dict

    def to_dict(self) -> dict:
        return asdict(self)


def _dbfs(amplitude: float, full_scale: float = 32768.0) -> float:
    if amplitude <= 0:
        return float("-inf")
    return 20.0 * math.log10(amplitude / full_scale)


def _edge_silence(samples, threshold: int) -> int:
    count = 0
    for s in samples:
        if abs(s) <= threshold:
            count += 1
        else:
            break
    return count


def analyze_wav_bytes(data: bytes) -> AudioMetrics:
    """Parse and objectively measure a WAV byte string.

    Returns metrics with ``valid_wav=False`` and an ``error`` string when the
    container cannot be parsed, rather than raising.
    """
    try:
        with wave.open(io.BytesIO(data), "rb") as w:
            channels = w.getnchannels()
            sampwidth = w.getsampwidth()
            framerate = w.getframerate()
            frames = w.getnframes()
            comptype = w.getcomptype()
            raw = w.readframes(frames)
    except (wave.Error, EOFError) as exc:
        return AudioMetrics(
            valid_wav=False, error=f"{type(exc).__name__}: {exc}",
            channels=None, sampwidth=None, framerate=None, frames=None,
            duration_s=None, peak=None, rms=None, peak_dbfs=None,
            rms_dbfs=None, silence_ratio=None, lead_silence_s=None,
            trail_silence_s=None, clipped_samples=None, dc_offset=None,
            all_silent=True, checks={},
        )

    if sampwidth != 2:
        return AudioMetrics(
            valid_wav=False, error=f"unsupported sample width {sampwidth} bytes",
            channels=channels, sampwidth=sampwidth, framerate=framerate,
            frames=frames, duration_s=frames / framerate if framerate else None,
            peak=None, rms=None, peak_dbfs=None, rms_dbfs=None,
            silence_ratio=None, lead_silence_s=None, trail_silence_s=None,
            clipped_samples=None, dc_offset=None, all_silent=True, checks={},
        )

    n = len(raw) // 2
    samples = struct.unpack("<%dh" % n, raw[: n * 2]) if n else ()
    duration = frames / framerate if framerate else 0.0

    if not samples:
        return AudioMetrics(
            valid_wav=True, error=None, channels=channels, sampwidth=sampwidth,
            framerate=framerate, frames=frames, duration_s=duration,
            peak=0, rms=0.0, peak_dbfs=float("-inf"), rms_dbfs=float("-inf"),
            silence_ratio=1.0, lead_silence_s=duration, trail_silence_s=duration,
            clipped_samples=0, dc_offset=0.0, all_silent=True,
            checks={"has_audio": False, "not_all_silent": False},
        )

    peak = max(abs(s) for s in samples)
    rms = math.sqrt(sum(s * s for s in samples) / len(samples))
    dc = sum(samples) / len(samples)
    threshold = max(1, int(peak * _SILENCE_RATIO))
    non_silent = sum(1 for s in samples if abs(s) > threshold)
    silence_ratio = 1.0 - non_silent / len(samples)
    lead = _edge_silence(samples, threshold) / framerate
    trail = _edge_silence(reversed(samples), threshold) / framerate
    clipped = sum(1 for s in samples if abs(s) >= 32767)
    clip_fraction = clipped / len(samples)

    checks = {
        "has_audio": len(samples) > 0,
        "not_all_silent": peak > 0,
        "silence_ratio_ok": silence_ratio < 0.95,
        "lead_silence_ok": lead <= _MAX_EDGE_SILENCE_S,
        "trail_silence_ok": trail <= _MAX_EDGE_SILENCE_S,
        "clipping_ok": clip_fraction <= _MAX_CLIP_FRACTION,
        "dc_offset_ok": abs(dc) < 500,
    }

    return AudioMetrics(
        valid_wav=True, error=None, channels=channels, sampwidth=sampwidth,
        framerate=framerate, frames=frames, duration_s=round(duration, 4),
        peak=peak, rms=round(rms, 3), peak_dbfs=round(_dbfs(peak), 3),
        rms_dbfs=round(_dbfs(rms), 3), silence_ratio=round(silence_ratio, 4),
        lead_silence_s=round(lead, 4), trail_silence_s=round(trail, 4),
        clipped_samples=clipped, dc_offset=round(dc, 3),
        all_silent=peak == 0, checks=checks,
    )


def analyze_wav_file(path: str) -> AudioMetrics:
    with open(path, "rb") as fh:
        return analyze_wav_bytes(fh.read())
