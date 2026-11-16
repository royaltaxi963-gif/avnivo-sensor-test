"""Phase 2 recovery smoke test: re-send known-good audio across script groups.

After the HTTP 502 fix, confirm the deployed endpoint still recognises the
previously successful locales. Reuses cached 16 kHz PCM samples so no new
licence downloads are needed; each call is a few seconds of audio (~$0.003).

Usage::

    python recovery_smoke.py            # free: show the plan and cost estimate
    python recovery_smoke.py --run      # execute (small, bounded spend)
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import metrics  # noqa: E402

from run_campaign import BASE_URL, PRICE_PER_SECOND, _post, load_evidence  # noqa: E402

OUT = HERE / "recovery_smoke.jsonl"

# One representative locale per script family, plus the weak locales the
# directive flags. Latin, Cyrillic, Arabic, Devanagari, Han, Kana, Hangul,
# Thai, Ethiopic-adjacent and the low-accuracy set.
GROUPS = {
    "latin": "en-US",
    "latin-uz": "uz-UZ",
    "cyrillic": "ru-RU",
    "arabic": "ar-EG",
    "devanagari": "hi-IN",
    "han": "cmn-Hans-CN",
    "kana": "ja-JP",
    "hangul": "ko-KR",
    "thai": "th-TH",
    "swahili": "sw-KE",
    "weak-ff": "ff-SN",
    "weak-ig": "ig-NG",
    "weak-ga": "ga-IE",
}


def _cached_sample(locale: str) -> dict | None:
    """Return the first PASS evidence row for a locale whose file still exists."""
    for row in load_evidence():
        if row.get("locale") == locale and row.get("status") == "PASS":
            path = Path("/tmp/chirp2_samples") / locale / row["sample"]
            if path.exists():
                return {**row, "audio": str(path)}
    return None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--run", action="store_true")
    args = parser.parse_args()

    plan = []
    for group, locale in GROUPS.items():
        sample = _cached_sample(locale)
        if sample:
            plan.append((group, locale, sample))
        else:
            print(f"  ! {group} ({locale}): no cached sample")
    total_seconds = sum(s["duration_seconds"] for _, _, s in plan)
    estimate = total_seconds * PRICE_PER_SECOND
    print(f"{len(plan)} locales, {total_seconds:.1f}s audio, est. ${estimate:.4f}")
    if not args.run:
        return 0

    results = []
    for index, (group, locale, sample) in enumerate(plan):
        path = Path(sample["audio"])
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        status, payload, latency = _post(locale, path, 500 + index)
        try:
            text = str(json.loads(payload).get("text", ""))
        except ValueError:
            text = ""
        row = {
            "group": group,
            "locale": locale,
            "sample_sha256": digest,
            "reference": sample["transcript"],
            "http_status": status,
            "hypothesis": text,
            "latency_seconds": latency,
            "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        }
        if status == 200:
            row.update(metrics.score(locale, sample["transcript"], text))
            row["status"] = "PASS" if text.strip() else "FAIL"
        else:
            row["status"] = "FAIL"
            row["error"] = payload[:300]
        results.append(row)
        print(f"  {locale:12s} {row['status']:4s} http={status} "
              f"wer={row.get('wer')} cer={row.get('cer')} lat={latency}s")
        with OUT.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")

    passed = sum(1 for r in results if r["status"] == "PASS")
    print(f"recovery: {passed}/{len(results)} PASS")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
