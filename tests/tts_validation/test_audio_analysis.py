"""Unit tests for the objective WAV analyzer and the budget ledger."""
from __future__ import annotations

import io
import os
import struct
import sys
import tempfile
import wave
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from audio_analysis import analyze_wav_bytes  # noqa: E402
from budget import (  # noqa: E402
    BudgetLedger, LedgerEntry, WORST_CASE_REQUEST_USD, estimate_request_cost_usd,
)


def _wav(samples, rate=24000):
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(struct.pack("<%dh" % len(samples), *samples))
    return buf.getvalue()


def test_rejects_non_wav_bytes():
    m = analyze_wav_bytes(b"not a wav at all")
    assert m.valid_wav is False
    assert m.error
    assert m.all_silent is True


def test_detects_valid_tone():
    samples = [12000 if i % 2 else -12000 for i in range(24000)]
    m = analyze_wav_bytes(_wav(samples))
    assert m.valid_wav
    assert m.framerate == 24000
    assert m.channels == 1
    assert m.duration_s == 1.0
    assert m.checks["has_audio"] and m.checks["not_all_silent"]
    assert m.checks["silence_ratio_ok"]
    assert not m.all_silent


def test_detects_all_silence():
    m = analyze_wav_bytes(_wav([0] * 24000))
    assert m.valid_wav
    assert m.all_silent
    assert not m.checks["not_all_silent"]
    assert m.silence_ratio == 1.0


def test_detects_excessive_leading_silence():
    samples = [0] * 48000 + [10000] * 24000
    m = analyze_wav_bytes(_wav(samples))
    assert not m.checks["lead_silence_ok"]
    assert m.lead_silence_s >= 2.0


def test_detects_clipping():
    samples = [32767] * 2000 + [100] * 1000
    m = analyze_wav_bytes(_wav(samples))
    assert not m.checks["clipping_ok"]


def test_detects_dc_offset():
    m = analyze_wav_bytes(_wav([3000] * 24000))
    assert not m.checks["dc_offset_ok"]


def test_empty_wav_reports_no_audio():
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(24000)
        w.writeframes(b"")
    m = analyze_wav_bytes(buf.getvalue())
    assert m.valid_wav
    assert not m.checks["has_audio"]
    assert m.all_silent


# --- Budget ---------------------------------------------------------------

def test_cost_estimate_matches_published_pricing():
    # 1 second of audio == 25 audio tokens == 25 * $9 / 1e6 = $0.000225
    # (plus one conservative input token: 0.25 * $0.50 / 1e6 = $1.25e-7)
    cost = estimate_request_cost_usd(0, 1.0)
    assert abs(cost - (0.000225 + 0.125 / 1e6)) < 1e-9


def test_worst_case_is_small():
    assert WORST_CASE_REQUEST_USD < 0.05


def test_ledger_enforces_stop_threshold():
    with tempfile.TemporaryDirectory() as d:
        led = BudgetLedger(os.path.join(d, "led.json"))
        assert led.can_spend(WORST_CASE_REQUEST_USD)
        led.record(LedgerEntry(
            test_id="t1", timestamp="now", language="en", voice="Kore", style=None,
            text_chars=10, http_status=200, latency_s=1.0, audio_seconds=1.0,
            estimated_cost_usd=17.99, artifact=None,
        ))
        assert not led.can_spend(WORST_CASE_REQUEST_USD)
        assert led.remaining_usd() == round(20.0 - 17.99, 6)


def test_ledger_persists_across_reload():
    with tempfile.TemporaryDirectory() as d:
        path = os.path.join(d, "led.json")
        led = BudgetLedger(path)
        led.record(LedgerEntry(
            test_id="t1", timestamp="now", language="en", voice="Kore", style=None,
            text_chars=10, http_status=200, latency_s=1.0, audio_seconds=1.0,
            estimated_cost_usd=0.001, artifact=None,
        ))
        reloaded = BudgetLedger(path)
        assert reloaded.spent_usd == 0.001
        assert reloaded.paid_request_count == 1


def test_ledger_never_exceeds_hard_ceiling_by_construction():
    with tempfile.TemporaryDirectory() as d:
        led = BudgetLedger(os.path.join(d, "led.json"))
        # Recording a huge single cost must not silently pass the ceiling check.
        led.record(LedgerEntry(
            test_id="t", timestamp="now", language="en", voice="Kore", style=None,
            text_chars=1, http_status=200, latency_s=1.0, audio_seconds=1.0,
            estimated_cost_usd=25.0, artifact=None,
        ))
        assert led.remaining_usd() < 0
        assert not led.can_spend(0.0)
