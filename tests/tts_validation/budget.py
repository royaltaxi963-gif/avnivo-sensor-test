"""Cost model and durable expenditure ledger for AVNIVO TTS paid testing.

Pricing source (verified 2026-11-16, official Google Cloud pricing page):
  Gemini 3.8 Flash TTS (Preview), through 2026-12-31
    - Input text tokens:  $0.50 per 1,000,000 text tokens  (sku 4CB8-5200-1246)
    - Output audio tokens: $9.00 per 1,000,000 audio tokens (sku 5FD7-DAE7-9FBA)
    - 1 second of audio == 25 audio tokens (per Google pricing footnote)

We cannot read Google's billing cap from here, so the ledger enforces a
conservative LOCAL ceiling. It stops paid work at STOP_THRESHOLD_USD and
never exceeds HARD_CEILING_USD.
"""
from __future__ import annotations

import json
import os
from dataclasses import dataclass, asdict
from datetime import datetime, timezone

# --- Verified pricing constants (USD) -------------------------------------
USD_PER_M_INPUT_TOKENS = 0.50
USD_PER_M_AUDIO_TOKENS = 9.00
AUDIO_TOKENS_PER_SECOND = 25

# --- Conservative local budget controls -----------------------------------
HARD_CEILING_USD = 20.00
STOP_THRESHOLD_USD = 18.00   # stop paid testing once conservative spend reaches this
RESERVE_USD = 2.00           # held back for uncertainty / billing discrepancies

# Worst-case per-request bound used for pre-flight gating. Derived from the
# 350-char server cap: assume a very slow delivery of ~45 s of audio.
WORST_CASE_REQUEST_USD = round(
    (350 / 4 * USD_PER_M_INPUT_TOKENS + 45 * AUDIO_TOKENS_PER_SECOND * USD_PER_M_AUDIO_TOKENS) / 1_000_000,
    6,
)


def estimate_request_cost_usd(text_chars: int, audio_seconds: float) -> float:
    """Estimate billed cost for one successful request.

    Failed/non-200 requests are assumed non-billable for audio output but are
    still recorded; the ledger charges the conservative estimate so the ceiling
    is never under-stated.
    """
    input_tokens = max(1, text_chars) / 4.0
    audio_tokens = max(0.0, audio_seconds) * AUDIO_TOKENS_PER_SECOND
    return (input_tokens * USD_PER_M_INPUT_TOKENS + audio_tokens * USD_PER_M_AUDIO_TOKENS) / 1_000_000


@dataclass
class LedgerEntry:
    test_id: str
    timestamp: str
    language: str
    voice: str
    style: str | None
    text_chars: int
    http_status: int | None
    latency_s: float | None
    audio_seconds: float | None
    estimated_cost_usd: float
    artifact: str | None
    notes: str = ""


class BudgetLedger:
    """Append-only ledger with checkpointing.

    The JSON file is rewritten atomically after every entry so an interruption
    never loses recorded spend.
    """

    def __init__(self, path: str):
        self.path = path
        self.entries: list[LedgerEntry] = []
        if os.path.exists(path):
            with open(path, "r", encoding="utf-8") as fh:
                for row in json.load(fh):
                    self.entries.append(LedgerEntry(**row))

    @property
    def spent_usd(self) -> float:
        return round(sum(e.estimated_cost_usd for e in self.entries), 6)

    @property
    def paid_request_count(self) -> int:
        return sum(1 for e in self.entries if e.http_status is not None)

    def remaining_usd(self) -> float:
        return round(HARD_CEILING_USD - self.spent_usd, 6)

    def can_spend(self, estimated_usd: float = WORST_CASE_REQUEST_USD) -> bool:
        """True only if the next request keeps us at or below the stop threshold."""
        return self.spent_usd + estimated_usd <= STOP_THRESHOLD_USD

    def record(self, entry: LedgerEntry) -> None:
        self.entries.append(entry)
        self._flush()

    def _flush(self) -> None:
        os.makedirs(os.path.dirname(self.path), exist_ok=True)
        tmp = self.path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as fh:
            json.dump([asdict(e) for e in self.entries], fh, indent=2)
        os.replace(tmp, self.path)

    def summary(self) -> dict:
        return {
            "paid_requests": self.paid_request_count,
            "total_entries": len(self.entries),
            "estimated_spent_usd": self.spent_usd,
            "hard_ceiling_usd": HARD_CEILING_USD,
            "stop_threshold_usd": STOP_THRESHOLD_USD,
            "reserve_usd": RESERVE_USD,
            "remaining_to_ceiling_usd": self.remaining_usd(),
            "worst_case_request_usd": WORST_CASE_REQUEST_USD,
        }


def utcnow() -> str:
    return datetime.now(timezone.utc).isoformat()
