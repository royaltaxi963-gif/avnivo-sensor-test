"""One-off checkpoint repair before the targeted retry run.

Two issues are corrected so the retry only re-issues the requests that truly
need a second attempt:

* Degenerate directions where source == target (e.g. uz->uz for the uz-UZ
  locale itself). These are harness artefacts, not translation results, and are
  dropped so they are never reported.
* Transient failures (HTTP 429 / transport errors) and the X2uz / X2en requests
  that were skipped because their prerequisite direction failed. Dropping them
  lets the harness re-issue exactly those calls; accepted calls are kept.
"""
from __future__ import annotations

import json
from pathlib import Path

RESULTS = Path(__file__).resolve().parent / "results"
CHECKPOINT = RESULTS / "translation-llm-checkpoint.json"


def main() -> None:
    state = json.loads(CHECKPOINT.read_text())
    done = state["done"]
    kept, dropped_degenerate, dropped_transient = {}, 0, 0
    for k, r in done.items():
        if r.get("source") and r.get("source") == r.get("target"):
            dropped_degenerate += 1
            continue
        if r.get("http") in (None, 429, 500, 502, 503, 504):
            dropped_transient += 1
            continue
        kept[k] = r
    state["done"] = kept
    CHECKPOINT.write_text(json.dumps(state, ensure_ascii=False, indent=1))
    print(f"kept={len(kept)} dropped_degenerate={dropped_degenerate} "
          f"dropped_transient_or_prereq={dropped_transient}")


if __name__ == "__main__":
    main()
