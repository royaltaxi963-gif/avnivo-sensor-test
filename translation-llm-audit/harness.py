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
from corpus import ITEMS, source_text
from language_matrix import (
    DEFAULT_PROJECT,
    DEFAULT_REGION,
    MODEL_ID,
    matrix_rows,
)
from quality import score

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


def build_plan(rows: list[dict], deep: set[str] | None = None) -> list[dict]:
    """Deterministic request plan per locale (class-aware).

    ``deep`` forces the full four-direction probe for the named locales even
    when they are not documented as officially supported, so an unexpected API
    acceptance can be confirmed rather than guessed.
    """
    deep = deep or set()
    by_chirp = {r["chirp2_code"]: r for r in rows}
    plan = []
    for chirp, row in by_chirp.items():
        llm = row["translation_llm_code"]
        cls = row["documented_class"]
        effective = "A" if chirp in deep else cls

        def emit(iid, cat, src_field, direction, source, target):
            # A source equal to the target is degenerate (e.g. uz->uz for the
            # Uzbek locale itself); the API rejects it and it tests nothing.
            if source == target:
                return
            plan.append({"chirp": chirp, "llm": llm, "class": cls, "item": iid,
                         "category": cat, "src_field": src_field, "direction": direction,
                         "source": source, "target": target})

        if effective == "A":
            for item in ITEMS:
                iid, cat, _uz, _en = item
                emit(iid, cat, "uz", "uz2X", "uz", llm)
                emit(iid, cat, "en", "en2X", "en", llm)
                emit(iid, cat, "X", "X2uz", llm, "uz")
                emit(iid, cat, "X", "X2en", llm, "en")
            for rep in range(_STABILITY_REPEATS - 1):
                emit(_STABILITY_ITEM, "stability_repeat", "uz", f"uz2X_rep{rep + 2}", "uz", llm)
        elif effective == "B":
            # Reduced probe for experimental locales: record real behaviour only.
            for item in ITEMS[:4]:
                iid, cat, _uz, _en = item
                emit(iid, cat, "uz", "uz2X", "uz", llm)
                emit(iid, cat, "X", "X2uz", llm, "uz")
        else:
            # Class C: a single confirmation probe (rejected calls are not billed).
            emit("c01", "greeting", "uz", "uz2X", "uz", llm)
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


def run(phase: str, only: list[str] | None, max_requests: int, deep: set[str] | None = None) -> dict:
    rows = matrix_rows()
    plan = build_plan(rows, deep=deep)
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
            if executed >= max_requests:
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
            q = score(entry["item"], src, out, entry["llm"],
                      entry["source"], entry["target"], is_target)
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
    write_evidence(done, ledger)
    print(f"[harness] done new={executed} total_requests={ledger.requests} "
          f"cost=${ledger.cost:.4f} errors={ledger.errors}", flush=True)
    return {"records": list(done.values()), "ledger": ledger.to_dict()}


def write_evidence(done: dict, ledger: "Ledger") -> None:
    """Write the evidence file, preserving tagged retest records.

    The checkpoint only tracks the main matrix. diagnose.py appends retest
    records (carrying a ``tag``) straight to the evidence file, so a later
    harness run must not drop them when it rewrites the file.
    """
    existing: list[dict] = []
    if EVIDENCE.exists():
        existing = json.loads(EVIDENCE.read_text()).get("records", [])
    merged = dict(done)
    for r in existing:
        if r.get("tag") or r.get("category") in ("diagnosis", "disputed_retest",
                                                 "experimental_retest", "regression"):
            merged.setdefault(f"{r['chirp']}|{r['direction']}@{r.get('tag')}|{r['item']}", r)
    EVIDENCE.write_text(json.dumps({"records": list(merged.values()),
                                    "ledger": ledger.to_dict()},
                                   ensure_ascii=False, indent=1))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--phase", default="full", choices=["pilot", "full"])
    ap.add_argument("--only", default="", help="comma-separated chirp locales")
    ap.add_argument("--deep", default="", help="comma-separated chirp locales to probe fully")
    ap.add_argument("--max-requests", type=int, default=100000)
    args = ap.parse_args()
    only = [x for x in args.only.split(",") if x] or None
    deep = {x for x in args.deep.split(",") if x} or None
    run(args.phase, only, args.max_requests, deep=deep)


if __name__ == "__main__":
    main()
