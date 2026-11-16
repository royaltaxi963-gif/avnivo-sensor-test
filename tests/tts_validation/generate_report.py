"""Aggregate the TTS validation ledger and per-sample metrics into a report.

Reads only local artifacts and the ledger; makes no network calls.
"""
from __future__ import annotations

import json
import os
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
ARTIFACTS = os.path.join(HERE, "artifacts")
LEDGER = os.path.join(HERE, "ledger", "expenditure.json")

VOICES = ["Kore", "Aoede", "Leda", "Zephyr", "Achernar",
          "Puck", "Charon", "Orus", "Achird", "Iapetus"]
PRIORITY = ["uz", "ru", "en", "tr"]


def load_ledger() -> list[dict]:
    if not os.path.exists(LEDGER):
        return []
    with open(LEDGER, "r", encoding="utf-8") as fh:
        return json.load(fh)


def load_metrics(test_id: str) -> dict | None:
    path = os.path.join(ARTIFACTS, f"{test_id}.metrics.json")
    if not os.path.exists(path):
        return None
    with open(path, "r", encoding="utf-8") as fh:
        return json.load(fh)


def main() -> None:
    entries = load_ledger()
    ok = [e for e in entries if e["http_status"] == 200]
    failed = [e for e in entries if e["http_status"] != 200]

    total_cost = round(sum(e["estimated_cost_usd"] for e in entries), 6)

    # Voice-language matrix from HTTP 200 responses.
    matrix: dict[str, dict[str, bool]] = defaultdict(dict)
    for e in ok:
        matrix[e["voice"]][e["language"]] = True

    # Audio integrity summary.
    issues = []
    durations = []
    latencies = []
    for e in ok:
        m = load_metrics(e["test_id"])
        if m is None:
            continue
        durations.append(m["duration_s"] or 0.0)
        latencies.append(e["latency_s"] or 0.0)
        bad = [k for k, v in m["checks"].items() if not v]
        if bad or not m["valid_wav"]:
            issues.append({"test_id": e["test_id"], "problems": bad, "valid": m["valid_wav"]})

    langs = sorted({e["language"] for e in ok})
    voices_tested = sorted({e["voice"] for e in ok})

    report = {
        "paid_requests": len([e for e in entries if e["http_status"] is not None]),
        "successful": len(ok),
        "failed_or_throttled": len(failed),
        "estimated_cost_usd": total_cost,
        "languages_tested": langs,
        "language_count": len(langs),
        "voices_tested": voices_tested,
        "priority_languages_ok": {l: any(e["language"] == l for e in ok) for l in PRIORITY},
        "audio": {
            "samples_measured": len(durations),
            "duration_min_s": round(min(durations), 3) if durations else None,
            "duration_max_s": round(max(durations), 3) if durations else None,
            "duration_mean_s": round(sum(durations) / len(durations), 3) if durations else None,
            "latency_min_s": round(min(latencies), 3) if latencies else None,
            "latency_max_s": round(max(latencies), 3) if latencies else None,
            "integrity_issues": issues,
        },
        "voice_language_matrix": {v: sorted(matrix[v]) for v in voices_tested},
    }
    out = os.path.join(HERE, "ledger", "report_data.json")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8") as fh:
        json.dump(report, fh, indent=2)
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
