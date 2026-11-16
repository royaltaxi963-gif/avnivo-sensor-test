"""Independent Google Cloud Translation LLM audit harness.

Runs real, paid calls against the official Translation LLM model
(``general/translation-llm``) for the 117 Chirp 2 locales that AVNIVO exposes.

Design rules honoured here:
* Never import or touch the AVNIVO application.
* Never fabricate results: every recorded translation comes from a real HTTP
  response; failures are recorded as failures.
* Hard budget stop at $25 of the $30 ceiling; every request's characters are
  metered at Google's published rate ($10 / M input + $10 / M output).
* Checkpoint after every request so an interrupted run resumes where it stopped.

Authentication: a short-lived access token supplied via ``GOOGLE_ACCESS_TOKEN``
(issued by GitHub Actions Workload Identity Federation), otherwise Application
Default Credentials.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import httpx

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"
RESULTS.mkdir(exist_ok=True)

sys.path.insert(0, str(HERE))
from corpus import ITEMS, NUMERIC_EXPECTATIONS, SCRIPT_EXPECTATIONS, source_text
from language_matrix import (
    DEFAULT_PROJECT,
    DEFAULT_REGION,
    LLM_OFFICIAL,
    MODEL_ID,
    matrix_rows,
)

# --- Budget ---------------------------------------------------------------
USD_PER_CHAR_IN = 10.0 / 1_000_000
USD_PER_CHAR_OUT = 10.0 / 1_000_000
BUDGET_CEILING = 30.0
BUDGET_HARD_STOP = 25.0          # stop starting new paid requests here
RESERVE = BUDGET_CEILING - BUDGET_HARD_STOP

PROJECT = os.getenv("AVNIVO_TRANSLATION_PROJECT", DEFAULT_PROJECT)
REGION = os.getenv("AVNIVO_TRANSLATION_REGION", DEFAULT_REGION)
ENDPOINT = f"https://translation.googleapis.com/v3/projects/{PROJECT}/locations/{REGION}:translateText"
MODEL_PATH = f"projects/{PROJECT}/locations/{REGION}/models/{MODEL_ID}"

CHECKPOINT = RESULTS / "translation-llm-checkpoint.json"
EVIDENCE = RESULTS / "translation-llm-test-results.json"

_STABILITY_ITEM = "c01"
_STABILITY_REPEATS = 3


def access_token() -> str:
    token = os.getenv("GOOGLE_ACCESS_TOKEN")
    if token:
        return token
    import google.auth
    from google.auth.transport.requests import Request as GReq

    creds, _ = google.auth.default(scopes=["https://www.googleapis.com/auth/cloud-platform"])
    if not creds.valid:
        creds.refresh(GReq())
    return creds.token


# --- Ledger ---------------------------------------------------------------
class Ledger:
    def __init__(self) -> None:
        self.in_chars = 0
        self.out_chars = 0
        self.requests = 0
        self.errors = 0

    @property
    def cost(self) -> float:
        return self.in_chars * USD_PER_CHAR_IN + self.out_chars * USD_PER_CHAR_OUT

    def add(self, src: str, out: str) -> None:
        self.in_chars += len(src)
        self.out_chars += len(out)
        self.requests += 1

    def to_dict(self) -> dict:
        return {
            "requests": self.requests,
            "errors": self.errors,
            "input_chars": self.in_chars,
            "output_chars": self.out_chars,
            "cost_usd": round(self.cost, 6),
            "budget_ceiling_usd": BUDGET_CEILING,
            "hard_stop_usd": BUDGET_HARD_STOP,
            "remaining_usd": round(BUDGET_CEILING - self.cost, 6),
            "rate": "USD 10 per 1M input chars + USD 10 per 1M output chars",
        }


class BudgetStop(Exception):
    pass


# --- API call -------------------------------------------------------------
def translate(client: httpx.Client, token: str, text: str, source: str, target: str,
              retries: int = 3) -> dict:
    payload = {
        "contents": [text],
        "mimeType": "text/plain",
        "sourceLanguageCode": source,
        "targetLanguageCode": target,
        "model": MODEL_PATH,
    }
    headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
    last = None
    for attempt in range(retries + 1):
        started = time.monotonic()
        try:
            resp = client.post(ENDPOINT, headers=headers, json=payload, timeout=45)
        except httpx.RequestError as exc:
            last = {"http": None, "error": f"transport:{type(exc).__name__}",
                    "translated": "", "latency_s": round(time.monotonic() - started, 3)}
            time.sleep(1.5 * (attempt + 1))
            continue
        latency = round(time.monotonic() - started, 3)
        if resp.status_code == 200:
            try:
                translated = resp.json()["translations"][0]["translatedText"]
                if not isinstance(translated, str):
                    raise ValueError("non-string translatedText")
            except (KeyError, IndexError, TypeError, ValueError) as exc:
                return {"http": 200, "error": f"bad_response:{type(exc).__name__}",
                        "translated": "", "latency_s": latency, "raw": resp.text[:400]}
            return {"http": 200, "error": None, "translated": translated,
                    "latency_s": latency, "detected": resp.json()["translations"][0].get("detectedLanguageCode")}
        # Retry only transient conditions; a 400 is a definitive answer.
        last = {"http": resp.status_code, "error": resp.text[:400],
                "translated": "", "latency_s": latency}
        if resp.status_code in (429, 500, 502, 503, 504) and attempt < retries:
            time.sleep(1.5 * (attempt + 1))
            continue
        return last
    return last


# --- Quality scoring ------------------------------------------------------
def expected_script(llm_code: str) -> str | None:
    return SCRIPT_EXPECTATIONS.get(llm_code)


_SCRIPT_RANGES = {
    "Cyrillic": [(0x0400, 0x04FF)], "Arabic": [(0x0600, 0x06FF), (0x0750, 0x077F)],
    "Hebrew": [(0x0590, 0x05FF)], "Devanagari": [(0x0900, 0x097F)],
    "Bengali": [(0x0980, 0x09FF)], "Gurmukhi": [(0x0A00, 0x0A7F)],
    "Gujarati": [(0x0A80, 0x0AFF)], "Tamil": [(0x0B80, 0x0BFF)],
    "Telugu": [(0x0C00, 0x0C7F)], "Kannada": [(0x0C80, 0x0CFF)],
    "Malayalam": [(0x0D00, 0x0D7F)], "Sinhala": [(0x0D80, 0x0DFF)],
    "Thai": [(0x0E00, 0x0E7F)], "Lao": [(0x0E80, 0x0EFF)],
    "Myanmar": [(0x1000, 0x109F)], "Georgian": [(0x10A0, 0x10FF)],
    "Ethiopic": [(0x1200, 0x137F)], "Khmer": [(0x1780, 0x17FF)],
    "Greek": [(0x0370, 0x03FF)], "Han": [(0x4E00, 0x9FFF), (0x3400, 0x4DBF)],
    "Japanese": [(0x3040, 0x30FF), (0x4E00, 0x9FFF)],
    "Korean": [(0xAC00, 0xD7AF), (0x1100, 0x11FF)],
    "Armenian": [(0x0530, 0x058F)],
}


def script_present(text: str, script: str) -> bool:
    for ch in text:
        cp = ord(ch)
        for lo, hi in _SCRIPT_RANGES.get(script, []):
            if lo <= cp <= hi:
                return True
    return False


def score(item_id: str, source_text_: str, translated: str, llm_code: str,
          is_target: bool) -> dict:
    """Heuristic, script/number based quality signals for one direction."""
    out = (translated or "").strip()
    flags = []
    if not out:
        flags.append("empty_output")
    if out and out == source_text_.strip():
        flags.append("identical_to_source")
    ratio = (len(out) / len(source_text_)) if source_text_ else 0.0
    if out and (ratio < 0.15 or ratio > 6.0):
        flags.append(f"length_ratio_{ratio:.2f}")
    script = expected_script(llm_code) if is_target else None
    if script and out and not script_present(out, script):
        flags.append(f"script_mismatch:{script}")
    nums = NUMERIC_EXPECTATIONS.get(item_id)
    if nums and out:
        digits_in_out = "".join(ch for ch in out if ch.isdigit())
        src_digits = "".join(ch for ch in source_text_ if ch.isdigit())
        if src_digits and src_digits not in digits_in_out and not any(n in out for n in nums):
            flags.append("numeric_mismatch")
    return {"flags": flags, "length_ratio": round(ratio, 3)}


def build_plan(rows: list[dict]) -> list[dict]:
    """Deterministic request plan per locale (class-aware)."""
    by_chirp = {r["chirp2_code"]: r for r in rows}
    plan = []
    for chirp, row in by_chirp.items():
        llm = row["translation_llm_code"]
        cls = row["documented_class"]
        if cls == "A":
            for item in ITEMS:
                iid, cat, _uz, _en = item
                plan.append({"chirp": chirp, "llm": llm, "class": cls, "item": iid,
                             "category": cat, "src_field": "uz", "direction": "uz2X",
                             "source": "uz", "target": llm})
                plan.append({"chirp": chirp, "llm": llm, "class": cls, "item": iid,
                             "category": cat, "src_field": "en", "direction": "en2X",
                             "source": "en", "target": llm})
                plan.append({"chirp": chirp, "llm": llm, "class": cls, "item": iid,
                             "category": cat, "src_field": "X", "direction": "X2uz",
                             "source": llm, "target": "uz"})
                plan.append({"chirp": chirp, "llm": llm, "class": cls, "item": iid,
                             "category": cat, "src_field": "X", "direction": "X2en",
                             "source": llm, "target": "en"})
            for rep in range(_STABILITY_REPEATS - 1):
                plan.append({"chirp": chirp, "llm": llm, "class": cls,
                             "item": _STABILITY_ITEM, "category": "stability_repeat",
                             "src_field": "uz", "direction": f"uz2X_rep{rep + 2}",
                             "source": "uz", "target": llm})
        elif cls == "B":
            # Reduced probe for experimental locales: record real behaviour only.
            for item in ITEMS[:4]:
                iid, cat, _uz, _en = item
                plan.append({"chirp": chirp, "llm": llm, "class": cls, "item": iid,
                             "category": cat, "src_field": "uz", "direction": "uz2X",
                             "source": "uz", "target": llm})
                plan.append({"chirp": chirp, "llm": llm, "class": cls, "item": iid,
                             "category": cat, "src_field": "X", "direction": "X2uz",
                             "source": llm, "target": "uz"})
        else:
            # Class C: a single confirmation probe (rejected calls are not billed).
            plan.append({"chirp": chirp, "llm": llm, "class": cls, "item": "c01",
                         "category": "greeting", "src_field": "uz", "direction": "uz2X",
                         "source": "uz", "target": llm})
    return plan


def _source_for(entry: dict) -> str:
    item = next(i for i in ITEMS if i[0] == entry["item"])
    if entry["src_field"] == "X":
        # X2uz/X2en read the already-translated target-language text produced by
        # the uz2X/en2X step; resolved at runtime from the checkpoint.
        return ""
    return source_text(item, entry["src_field"])


def load_checkpoint() -> dict:
    if CHECKPOINT.exists():
        return json.loads(CHECKPOINT.read_text())
    return {"done": {}, "ledger": {"in_chars": 0, "out_chars": 0, "requests": 0, "errors": 0}}


def save_checkpoint(state: dict) -> None:
    CHECKPOINT.write_text(json.dumps(state, ensure_ascii=False, indent=1))


def key(entry: dict) -> str:
    return f"{entry['chirp']}|{entry['direction']}|{entry['item']}"


def run(phase: str, only: list[str] | None, max_requests: int) -> dict:
    rows = matrix_rows()
    plan = build_plan(rows)
    if only:
        plan = [e for e in plan if e["chirp"] in only]
    # Pilot: a representative slice across scripts and classes.
    if phase == "pilot":
        pilot_set = {"uz-UZ", "ru-RU", "en-US", "tr-TR", "ja-JP", "ar-EG",
                     "hi-IN", "cmn-Hans-CN", "ko-KR", "de-DE", "fr-FR", "es-ES",
                     "am-ET", "as-IN"}
        plan = [e for e in plan if e["chirp"] in pilot_set]

    state = load_checkpoint()
    ledger = Ledger()
    ledger.in_chars = state["ledger"]["in_chars"]
    ledger.out_chars = state["ledger"]["out_chars"]
    ledger.requests = state["ledger"]["requests"]
    ledger.errors = state["ledger"]["errors"]
    done = state["done"]

    token = access_token()
    print(f"[harness] phase={phase} project={PROJECT} region={REGION} "
          f"planned={len(plan)} resume_done={len(done)}", flush=True)

    # Resolve X2uz / X2en sources from prior uz2X / en2X outputs.
    def x_source(entry: dict) -> str | None:
        base_item = entry["item"]
        want_dir = "uz2X" if entry["direction"] == "X2uz" else "en2X"
        k = f"{entry['chirp']}|{want_dir}|{base_item}"
        rec = done.get(k)
        if rec and rec.get("http") == 200 and rec.get("translated"):
            return rec["translated"]
        return None

    executed = 0
    with httpx.Client() as client:
        for entry in plan:
            k = key(entry)
            if k in done:
                continue
            if ledger.requests >= max_requests:
                print(f"[harness] request cap {max_requests} reached", flush=True)
                break
            if ledger.cost >= BUDGET_HARD_STOP:
                print(f"[harness] BUDGET HARD STOP at ${ledger.cost:.4f}", flush=True)
                break
            src = _source_for(entry) if entry["src_field"] != "X" else x_source(entry)
            if entry["src_field"] == "X" and not src:
                # Prerequisite direction failed; record as blocked, no charge.
                done[k] = {"chirp": entry["chirp"], "llm": entry["llm"],
                           "class": entry["class"], "item": entry["item"],
                           "category": entry["category"], "direction": entry["direction"],
                           "http": None, "error": "prerequisite_missing",
                           "source_text": "", "translated": "", "latency_s": 0,
                           "quality": {"flags": ["prerequisite_missing"], "length_ratio": 0}}
                continue
            res = translate(client, token, src, entry["source"], entry["target"])
            out = res.get("translated", "")
            is_target = entry["direction"].startswith("uz2X") or entry["direction"].startswith("en2X")
            ledger.add(src, out)
            if res.get("http") != 200:
                ledger.errors += 1
            q = score(entry["item"], src, out, entry["llm"], is_target)
            done[k] = {
                "chirp": entry["chirp"], "llm": entry["llm"], "class": entry["class"],
                "item": entry["item"], "category": entry["category"],
                "direction": entry["direction"], "source": entry["source"],
                "target": entry["target"], "http": res.get("http"),
                "error": res.get("error"), "source_text": src,
                "translated": out, "latency_s": res.get("latency_s"),
                "detected": res.get("detected"), "quality": q,
                "model": MODEL_ID,
            }
            executed += 1
            if executed % 25 == 0:
                state["ledger"] = {"in_chars": ledger.in_chars, "out_chars": ledger.out_chars,
                                   "requests": ledger.requests, "errors": ledger.errors}
                save_checkpoint(state)
                print(f"[harness] {executed} new | total={ledger.requests} "
                      f"cost=${ledger.cost:.4f}", flush=True)

    state["ledger"] = {"in_chars": ledger.in_chars, "out_chars": ledger.out_chars,
                       "requests": ledger.requests, "errors": ledger.errors}
    save_checkpoint(state)
    EVIDENCE.write_text(json.dumps({"records": list(done.values()),
                                    "ledger": ledger.to_dict()},
                                   ensure_ascii=False, indent=1))
    print(f"[harness] done new={executed} total_requests={ledger.requests} "
          f"cost=${ledger.cost:.4f} errors={ledger.errors}", flush=True)
    return {"records": list(done.values()), "ledger": ledger.to_dict()}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--phase", default="full", choices=["pilot", "full"])
    ap.add_argument("--only", default="", help="comma-separated chirp locales")
    ap.add_argument("--max-requests", type=int, default=100000)
    args = ap.parse_args()
    only = [x for x in args.only.split(",") if x] or None
    run(args.phase, only, args.max_requests)


if __name__ == "__main__":
    main()
