"""Phase 3: deepen verification of the 11 FAIR/POOR locales.

The first campaign used 3 samples per locale. Several weak locales scored
poorly on single-word or very short clips, which inflates WER/CER and is a
measurement artifact, not a recognition failure. This run pulls more samples
per weak locale, preferring full-sentence FLEURS clips (multiple speakers),
and keeps Tatoeba/Commons as the fallback where FLEURS has no config.

Every request is a real paid Chirp 2 call; the harness enforces the $30 cap.

Usage::

    python weak_locales.py --plan     # free: coverage + cost estimate
    python weak_locales.py --run      # execute
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
import samples  # noqa: E402

from run_campaign import (  # noqa: E402
    FALLBACK,
    FLEURS_CONFIG,
    PRICE_PER_SECOND,
    STOP_AT_USD,
    _gather,
    _post,
    load_evidence,
    spend,
)

OUT = HERE / "weak_locales.jsonl"

# The 11 FAIR/POOR locales, POOR first as the directive prioritises them.
WEAK = [
    "rup-BG", "ig-NG", "ga-IE", "cmn-Hant-TW", "bn-BD", "su-ID",
    "ff-SN", "sq-AL", "wo-SN", "yo-NG", "yue-Hant-HK",
]
SAMPLES_PER_LOCALE = 5


def _score(locale, reference, text):
    return metrics.score(locale, reference, text)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--run", action="store_true")
    parser.add_argument("--per-locale", type=int, default=SAMPLES_PER_LOCALE)
    args = parser.parse_args()

    existing = {(r["locale"], r.get("sample_sha256")) for r in load_evidence()
                if r.get("sample_sha256")}
    prior = spend(load_evidence())

    # Gather first (free: network downloads, no STT spend) so the estimate is real.
    gathered = {}
    total_seconds = 0.0
    for locale in WEAK:
        try:
            found = _gather(locale, args.per_locale)
        except Exception as error:
            print(f"  ! {locale}: {type(error).__name__}: {error}")
            found = []
        fresh = [s for s in found
                 if (locale, hashlib.sha256(Path(s["audio"]).read_bytes()).hexdigest())
                 not in existing]
        gathered[locale] = fresh
        total_seconds += sum(s["duration_seconds"] for s in fresh)
        src = {s["source"] for s in fresh}
        print(f"  {locale:12s} {len(fresh)} new samples  {sorted(src)}")

    estimate = total_seconds * PRICE_PER_SECOND
    print(f"\n{sum(len(v) for v in gathered.values())} new samples, "
          f"{total_seconds:.1f}s, est. ${estimate:.4f} "
          f"(prior spend ${prior:.4f}, cap ${STOP_AT_USD:.2f})")
    if not args.run:
        return 0

    total = prior
    results = []
    for locale in WEAK:
        for sample in gathered[locale]:
            if total >= STOP_AT_USD:
                print(f"STOP: ${total:.4f} reached the safety threshold")
                break
            path = Path(sample["audio"])
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            status, payload, latency = _post(locale, path, len(results))
            try:
                text = str(json.loads(payload).get("text", ""))
            except ValueError:
                text = ""
            total = round(total + sample["duration_seconds"] * PRICE_PER_SECOND, 6)
            row = {
                "locale": locale,
                "sample_sha256": digest,
                "sample": path.name,
                "transcript": sample["transcript"],
                "source": sample["source"],
                "source_detail": sample["source_detail"],
                "license": sample["license"],
                "synthetic": sample["synthetic"],
                "duration_seconds": sample["duration_seconds"],
                "billable_seconds": sample["duration_seconds"],
                "http_status": status,
                "hypothesis": text,
                "latency_seconds": latency,
                "model": json.loads(payload).get("model") if status == 200 else None,
                "error": None if status == 200 else payload[:300],
                "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            }
            if status == 200:
                row.update(_score(locale, sample["transcript"], text))
                row["status"] = "PASS" if text.strip() else "FAIL"
            else:
                row["status"] = "FAIL"
            results.append(row)
            with OUT.open("a", encoding="utf-8") as handle:
                handle.write(json.dumps(row, ensure_ascii=False) + "\n")
            print(f"  {locale:12s} {row['status']:4s} http={status} "
                  f"wer={row.get('wer')} cer={row.get('cer')} "
                  f"dur={sample['duration_seconds']}s ${total:.4f}")

    passed = sum(1 for r in results if r["status"] == "PASS")
    print(f"\nweak-locale pass: {passed}/{len(results)}  spend ${total:.4f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
