"""Targeted diagnosis of FAILED locales and deep retest of disputed ones.

Answers two questions with real API calls only:

1. For each locale the main matrix rejected, is the language code entirely
   invalid, or is only the uz source pair unsupported? It probes en->X and
   X->en, which distinguishes "target language is unsupported" from
   "unsupported language pair".
2. For the disputed (si-LK, xh-ZA, yo-NG) and experimental locales, run
   independent sentences in several directions with native-script checks.

Every response is appended to the shared evidence file; nothing is assumed.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import httpx

import harness
from harness import Ledger, translate
from quality import score

RESULTS = harness.RESULTS
EVIDENCE = RESULTS / "translation-llm-test-results.json"


def diag_path(mode: str) -> Path:
    return RESULTS / f"translation-llm-diagnosis-{mode}.json"

# Independent sentences (not reused from the main corpus), so a retest cannot
# merely reproduce the earlier answer.
PROBES = {
    "c01": ("uz", "Yaxshi tong, bugun ishlaringiz yaxshimi?", "en", "Good morning, is your work going well today?"),
    "c02": ("uz", "Men Toshkentdan kelganman va yangi loyihada ishlayman.", "en", "I come from Tashkent and I work on a new project."),
    "c03": ("uz", "Kechirasiz, eng yaqin bekat qayerda joylashgan?", "en", "Excuse me, where is the nearest station located?"),
    "c04": ("uz", "Bu mahsulotning narxi qancha va chegirma bormi?", "en", "How much does this product cost and is there a discount?"),
    "c05": ("uz", "Uchrashuv ertaga soat o'n beshda boshlanadi.", "en", "The meeting starts tomorrow at fifteen o'clock."),
}

# Locales the main run rejected, to be diagnosed.
FAILED_LOCALES = [
    "as-IN", "ast-ES", "ceb-PH", "ckb-IQ", "ff-SN", "jv-ID", "kam-KE", "kea-CV",
    "kk-KZ", "lg-UG", "luo-KE", "mi-NZ", "nso-ZA", "ny-MW", "oc-FR", "om-ET",
    "ps-AF", "rup-BG", "sd-IN", "sn-ZW", "su-ID", "umb-AO", "wo-SN", "yue-Hant-HK",
]
DISPUTED = ["si-LK", "xh-ZA", "yo-NG"]


def load_done() -> dict:
    if not EVIDENCE.exists():
        return {}
    d = json.loads(EVIDENCE.read_text())
    out = {}
    for r in d["records"]:
        tag = r.get("tag")
        key = (f"{r['chirp']}|{r['direction']}@{tag}|{r['item']}" if tag
               else f"{r['chirp']}|{r['direction']}|{r['item']}")
        out[key] = r
    return out


def record(client, token, ledger, done, chirp, llm, cls, item, cat, direction,
           source_code, target_code, text, is_target, tag) -> dict:
    # ``tag`` namespaces the key per run mode. Without it a retest would reuse a
    # key from an earlier run and be served from cache instead of being sent,
    # which would silently turn a retest into a replay of the original result.
    key = f"{chirp}|{direction}@{tag}|{item}"
    if key in done:
        return done[key]
    res = translate(client, token, text, source_code, target_code)
    out = res.get("translated", "")
    ledger.add(text, out)
    if res.get("http") != 200:
        ledger.errors += 1
    q = score(item, text, out, llm, source_code, target_code, is_target)
    rec = {
        "chirp": chirp, "llm": llm, "class": cls, "item": item, "category": cat,
        "direction": direction, "tag": tag, "source": source_code, "target": target_code,
        "source_text": text, "translated": out, "http": res.get("http"),
        "error": res.get("error"), "latency_s": res.get("latency_s"), "quality": q,
    }
    done[key] = rec
    return rec


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", choices=["failed", "disputed", "experimental", "regression"],
                    required=True)
    ap.add_argument("--max-requests", type=int, default=300)
    args = ap.parse_args()

    rows = {r["chirp2_code"]: r for r in harness.matrix_rows()}
    done = load_done()
    ledger = Ledger()
    executed = 0

    with httpx.Client() as client:
        token = harness.access_token()

        if args.mode == "failed":
            diagnosis = []
            for chirp in FAILED_LOCALES:
                row = rows[chirp]
                llm = row["translation_llm_code"]
                item = "c01"
                _s, uz_text, _t, en_text = PROBES[item]
                # Probe the locale as a *target* from two different sources: the
                # distinction between "target language is unsupported" and
                # "unsupported language pair" is exactly what en vs uz reveals.
                probes = [
                    ("uz2X", "uz", llm, uz_text, True),
                    ("en2X", "en", llm, en_text, True),
                ]
                outcome = {}
                for direction, sc, tc, text, is_target in probes:
                    if executed >= args.max_requests or ledger.cost >= harness.BUDGET_HARD_STOP:
                        break
                    r = record(client, token, ledger, done, chirp, llm, row["documented_class"],
                               item, "diagnosis", direction, sc, tc, text, is_target, "diag-failed")
                    executed += 1
                    outcome[direction] = {"http": r["http"],
                                          "msg": _short(r.get("error")),
                                          "out": r["translated"][:60]}
                en_ok = outcome.get("en2X", {}).get("http") == 200
                uz_ok = outcome.get("uz2X", {}).get("http") == 200
                if en_ok and not uz_ok:
                    cause = "pair_unsupported_from_uz_only"
                elif en_ok and uz_ok:
                    cause = "supported_contradicts_earlier_reject"
                else:
                    cause = "language_code_unsupported"
                diagnosis.append({"chirp2_code": chirp, "llm_code": llm,
                                  "language_name": row["language_name"],
                                  "cause": cause, "probes": outcome})

        elif args.mode == "disputed":
            diagnosis = []
            for chirp in DISPUTED:
                row = rows[chirp]
                llm = row["translation_llm_code"]
                per_item = []
                for item, (sc, uz_text, _t2, en_text) in PROBES.items():
                    for direction, src, tgt, text, is_target in [
                        ("uz2X", "uz", llm, uz_text, True),
                        ("en2X", "en", llm, en_text, True),
                        ("X2uz", llm, "uz", uz_text, False),
                        ("X2en", llm, "en", en_text, False),
                    ]:
                        if executed >= args.max_requests or ledger.cost >= harness.BUDGET_HARD_STOP:
                            break
                        r = record(client, token, ledger, done, chirp, llm, row["documented_class"],
                                   item, "disputed_retest", direction, src, tgt, text, is_target, "diag-disputed")
                        executed += 1
                        per_item.append({"item": item, "direction": direction, "http": r["http"],
                                         "out": r["translated"], "flags": r["quality"]["flags"]})
                ok = [x for x in per_item if x["http"] == 200]
                diagnosis.append({"chirp2_code": chirp, "llm_code": llm,
                                  "language_name": row["language_name"],
                                  "success": len(ok), "attempted": len(per_item),
                                  "reproduced": len(ok) == len(per_item) and len(per_item) > 0,
                                  "results": per_item})

        elif args.mode == "experimental":
            diagnosis = []
            for chirp, row in rows.items():
                if row["documented_class"] != "B":
                    continue
                llm = row["translation_llm_code"]
                per_item = []
                for item, (sc, uz_text, _t2, en_text) in list(PROBES.items())[:3]:
                    for direction, src, tgt, text, is_target in [
                        ("uz2X", "uz", llm, uz_text, True),
                        ("X2uz", llm, "uz", uz_text, False),
                    ]:
                        if executed >= args.max_requests or ledger.cost >= harness.BUDGET_HARD_STOP:
                            break
                        r = record(client, token, ledger, done, chirp, llm, "B",
                                   item, "experimental_retest", direction, src, tgt, text, is_target, "diag-experimental")
                        executed += 1
                        per_item.append({"item": item, "direction": direction, "http": r["http"],
                                         "out": r["translated"], "flags": r["quality"]["flags"]})
                ok = [x for x in per_item if x["http"] == 200]
                crit = [x for x in per_item if any(f.split(":")[0] in
                        {"empty_output", "script_mismatch", "identical_to_source", "numeric_mismatch"}
                        for f in x["flags"])]
                diagnosis.append({"chirp2_code": chirp, "llm_code": llm,
                                  "language_name": row["language_name"],
                                  "success": len(ok), "attempted": len(per_item),
                                  "quality_flags": len(crit), "results": per_item})

        else:  # regression
            sample = ["af-ZA", "ar-EG", "de-DE", "ja-JP", "ru-RU", "tr-TR",
                      "cmn-Hans-CN", "hi-IN", "ko-KR", "pt-BR"]
            diagnosis = []
            for chirp in sample:
                row = rows[chirp]
                llm = row["translation_llm_code"]
                item, (sc, uz_text, _t2, en_text) = "c01", PROBES["c01"]
                per = []
                for direction, src, tgt, text, is_target in [
                    ("uz2X", "uz", llm, uz_text, True),
                    ("X2uz", llm, "uz", uz_text, False),
                ]:
                    if executed >= args.max_requests or ledger.cost >= harness.BUDGET_HARD_STOP:
                        break
                    r = record(client, token, ledger, done, chirp, llm, row["documented_class"],
                               item, "regression", direction, src, tgt, text, is_target, "diag-regression")
                    executed += 1
                    per.append({"direction": direction, "http": r["http"],
                                "out": r["translated"], "flags": r["quality"]["flags"]})
                diagnosis.append({"chirp2_code": chirp, "llm_code": llm,
                                  "language_name": row["language_name"], "results": per})

    # Persist diagnosis and refreshed evidence/ledger.
    diag_path(args.mode).write_text(
        json.dumps({"mode": args.mode, "diagnosis": diagnosis},
                   ensure_ascii=False, indent=1))
    merged_ledger = _merge_ledger(ledger)
    EVIDENCE.write_text(json.dumps({"records": list(done.values()), "ledger": merged_ledger},
                                   ensure_ascii=False, indent=1))
    print(f"[diagnose] mode={args.mode} new_requests={executed} "
          f"total_requests={merged_ledger['requests']} cost=${merged_ledger['cost_usd']}")


def _short(err):
    if not err:
        return None
    import re
    m = re.search(r'"message":\s*"([^"]+)"', str(err))
    return m.group(1) if m else str(err)[:80]


def _merge_ledger(session: Ledger) -> dict:
    """Combine this session's spend with the preserved ledger from prior runs."""
    if EVIDENCE.exists():
        prev = json.loads(EVIDENCE.read_text()).get("ledger", {})
    else:
        prev = {}
    in_chars = prev.get("input_chars", 0) + session.in_chars
    out_chars = prev.get("output_chars", 0) + session.out_chars
    requests = prev.get("requests", 0) + session.requests
    cost = in_chars * harness.USD_PER_CHAR_IN + out_chars * harness.USD_PER_CHAR_OUT
    return {
        "requests": requests,
        "errors": prev.get("errors", 0) + session.errors,
        "input_chars": in_chars,
        "output_chars": out_chars,
        "cost_usd": round(cost, 6),
        "budget_ceiling_usd": harness.BUDGET_CEILING,
        "hard_stop_usd": harness.BUDGET_HARD_STOP,
        "remaining_usd": round(harness.BUDGET_CEILING - cost, 6),
        "rate": "USD 10 per 1M input chars + USD 10 per 1M output chars",
    }


if __name__ == "__main__":
    main()
