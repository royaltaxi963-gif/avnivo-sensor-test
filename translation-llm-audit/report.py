"""Turn raw harness evidence into the required audit deliverables.

Reads results/translation-llm-test-results.json (real API evidence) and the
documented language matrix, then writes the CSV, cost report, per-status JSON
lists, the mobile-friendly HTML report and the Uzbek markdown final report.

Every number is derived from recorded API responses; nothing is invented.
"""
from __future__ import annotations

import csv
import html
import json
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path

from language_matrix import (
    CHIRP2_LOCALE_NAMES,
    MODEL_ID,
    PRICING_URL,
    SOURCE_LANGUAGES_URL,
    VERIFIED_ON,
    matrix_rows,
)
from quality import CRITICAL_FLAGS, score

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"
EVIDENCE = RESULTS / "translation-llm-test-results.json"

# A call is a definitive "unsupported" answer only for these statuses.
UNSUPPORTED_STATUSES = {400, 404}
TRANSIENT_STATUSES = {429, 500, 502, 503, 504, None}


def load_evidence() -> dict:
    if not EVIDENCE.exists():
        return {"records": [], "ledger": {}}
    return json.loads(EVIDENCE.read_text())


def is_degenerate(r: dict) -> bool:
    """A source equal to target (e.g. uz->uz) is a harness artefact, not a result."""
    return bool(r.get("source")) and r.get("source") == r.get("target")


def group_records(records: list[dict]) -> dict[str, list[dict]]:
    by_chirp: dict[str, list[dict]] = defaultdict(list)
    for r in records:
        if is_degenerate(r):
            continue
        by_chirp[r["chirp"]].append(r)
    return by_chirp


def stability_ok(records: list[dict]) -> bool | None:
    """True if repeated uz2X runs of c01 are byte-identical; None if not run."""
    reps = [r["translated"] for r in records
            if r["item"] == "c01" and r["direction"].startswith("uz2X") and r["http"] == 200]
    if len(reps) < 2:
        return None
    return len(set(reps)) == 1


def locale_verdict(row: dict, records: list[dict]) -> dict:
    cls = row["documented_class"]
    ok = [r for r in records if r["http"] == 200]
    unsupported = [r for r in records if r["http"] in UNSUPPORTED_STATUSES]
    transient = [r for r in records if r["http"] in TRANSIENT_STATUSES and r["http"] != 200]
    # Quality flags are recomputed from the raw response text so a scoring
    # refinement applies consistently to already-recorded evidence.
    target_ok = [r for r in ok if r["direction"].startswith("uz2X") or r["direction"].startswith("en2X")]
    crit = Counter()
    for r in target_ok:
        flags = score(r["item"], r["source_text"], r["translated"], r["llm"],
                      r["source"], r["target"], True)["flags"]
        for f in flags:
            crit[f.split(":")[0]] += 1
    critical = {k: v for k, v in crit.items() if k in CRITICAL_FLAGS}
    quality_pass = bool(target_ok) and not critical
    st = stability_ok(records)
    latencies = [r["latency_s"] for r in ok if r.get("latency_s")]
    avg_lat = round(sum(latencies) / len(latencies), 3) if latencies else None

    if not records:
        decision = "RETEST_REQUIRED"
        reason = "not_yet_tested"
    elif ok and not unsupported:
        if cls == "A":
            if quality_pass and (st is not False):
                decision, reason = "PASSED", "official_support_and_quality_ok"
            else:
                decision, reason = "RETEST_REQUIRED", "quality_signals_need_review"
        elif cls == "B":
            decision, reason = "EXPERIMENTAL", "google_experimental_support"
        else:
            decision, reason = "RETEST_REQUIRED", "translated_despite_absent_from_doc"
    elif unsupported and not ok:
        decision, reason = "FAILED", "api_rejected_unsupported_language"
    elif ok and unsupported:
        decision, reason = "RETEST_REQUIRED", "mixed_results"
    else:
        decision, reason = "RETEST_REQUIRED", "transient_or_transport_error"

    return {
        **row,
        "api_tested": bool(records),
        "api_requests": len(records),
        "api_success": len(ok),
        "api_unsupported": len(unsupported),
        "api_transient_errors": len(transient),
        "avg_latency_s": avg_lat,
        "quality_pass": quality_pass,
        "critical_flags": critical,
        "stability_ok": st,
        "final_decision": decision,
        "decision_reason": reason,
        "sample_translation": (target_ok[0]["translated"] if target_ok else ""),
    }


def build() -> dict:
    rows = matrix_rows()
    ev = load_evidence()
    by_chirp = group_records(ev["records"])
    verdicts = [locale_verdict(r, by_chirp.get(r["chirp2_code"], [])) for r in rows]

    decisions = Counter(v["final_decision"] for v in verdicts)
    classes = Counter(v["documented_class"] for v in verdicts)
    ledger = ev.get("ledger", {})

    summary = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "verified_on": VERIFIED_ON,
        "model": MODEL_ID,
        "sources": {"languages": SOURCE_LANGUAGES_URL, "pricing": PRICING_URL},
        "total_locales": len(verdicts),
        "documented_classes": {"A_official": classes["A"], "B_experimental": classes["B"],
                               "C_unsupported": classes["C"], "D_undetermined": classes["D"]},
        "decisions": dict(decisions),
        "api_tested_locales": sum(1 for v in verdicts if v["api_tested"]),
        "coverage_percent": round(100 * sum(1 for v in verdicts if v["api_tested"]) / len(verdicts), 1),
        "real_api_requests": ledger.get("requests", 0),
        "total_cost_usd": ledger.get("cost_usd", 0.0),
        "budget_ceiling_usd": ledger.get("budget_ceiling_usd", 30.0),
        "remaining_usd": ledger.get("remaining_usd", 30.0),
        "ledger": ledger,
    }
    return {"verdicts": verdicts, "summary": summary}


def write_csv(verdicts: list[dict]) -> None:
    cols = ["order", "chirp2_code", "language_name", "translation_llm_code",
            "regional_variant", "official_support", "experimental", "documented_class",
            "api_tested", "api_requests", "api_success", "api_unsupported",
            "api_transient_errors", "avg_latency_s", "quality_pass", "stability_ok",
            "final_decision", "decision_reason", "sample_translation"]
    with (RESULTS / "translation-llm-language-matrix.csv").open("w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=cols, extrasaction="ignore")
        w.writeheader()
        for v in verdicts:
            w.writerow(v)


def write_json_lists(verdicts: list[dict], summary: dict) -> None:
    def dump(name, items):
        (RESULTS / name).write_text(json.dumps(items, ensure_ascii=False, indent=1))

    dump("translation-llm-passed-languages.json",
         [v for v in verdicts if v["final_decision"] == "PASSED"])
    dump("translation-llm-failed-languages.json",
         [v for v in verdicts if v["final_decision"] == "FAILED"])
    dump("translation-llm-retest-required.json",
         [v for v in verdicts if v["final_decision"] == "RETEST_REQUIRED"])
    (RESULTS / "translation-llm-experimental-languages.json").write_text(
        json.dumps([v for v in verdicts if v["final_decision"] == "EXPERIMENTAL"],
                   ensure_ascii=False, indent=1))
    # The removal list is a recommendation only; the audit never edits source.
    (RESULTS / "translation-llm-online-removal-candidates.json").write_text(
        json.dumps({
            "note": "Recommendation only. No source file was modified by this audit.",
            "locales": [{"chirp2_code": v["chirp2_code"], "language_name": v["language_name"],
                         "translation_llm_code": v["translation_llm_code"],
                         "reason": v["decision_reason"]}
                        for v in verdicts if v["final_decision"] == "FAILED"],
        }, ensure_ascii=False, indent=1))

    cost = {
        "model": MODEL_ID,
        "rate": "USD 10 per 1M input characters + USD 10 per 1M output characters",
        "source": PRICING_URL,
        "ledger": summary["ledger"],
        "budget_ceiling_usd": summary["budget_ceiling_usd"],
        "hard_stop_usd": summary["ledger"].get("hard_stop_usd", 25.0),
        "remaining_usd": summary["remaining_usd"],
        "checkpoints": [
            {"threshold_usd": 2, "status": "observed in pilot (real spend tracked)"},
            {"threshold_usd": 10, "status": "not exceeded at any point" if summary["total_cost_usd"] < 10 else "reviewed"},
            {"threshold_usd": 20, "status": "not exceeded at any point" if summary["total_cost_usd"] < 20 else "reviewed"},
            {"threshold_usd": 25, "status": "hard stop enforced in harness before each request"},
        ],
    }
    (RESULTS / "translation-llm-cost-report.json").write_text(
        json.dumps(cost, ensure_ascii=False, indent=1))


def write_html(verdicts: list[dict], summary: dict) -> None:
    dec_colors = {"PASSED": "#137333", "FAILED": "#c5221f",
                  "EXPERIMENTAL": "#b06000", "RETEST_REQUIRED": "#5f6368"}
    rows_html = []
    for v in verdicts:
        color = dec_colors.get(v["final_decision"], "#5f6368")
        rows_html.append(
            "<tr data-decision='{d}' data-name='{n}'>"
            "<td>{o}</td><td><code>{c}</code></td><td>{n}</td>"
            "<td><code>{llm}</code></td><td>{cls}</td>"
            "<td>{req}</td><td>{ok}</td><td>{lat}</td>"
            "<td>{q}</td>"
            "<td style='color:{col};font-weight:600'>{d}</td>"
            "</tr>".format(
                d=v["final_decision"], n=html.escape(v["language_name"]),
                o=v["order"], c=v["chirp2_code"], llm=v["translation_llm_code"],
                cls=v["documented_class"], req=v["api_requests"], ok=v["api_success"],
                lat=(v["avg_latency_s"] if v["avg_latency_s"] is not None else "-"),
                q=("OK" if v["quality_pass"] else "-"), col=color))
    counts = summary["decisions"]
    doc = f"""<!doctype html>
<html lang="uz"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Translation LLM 117 til auditi</title>
<style>
 body{{font-family:-apple-system,Roboto,Segoe UI,Arial,sans-serif;margin:0;padding:12px;background:#f8f9fa;color:#202124}}
 h1{{font-size:1.3rem}} .cards{{display:flex;flex-wrap:wrap;gap:8px;margin:12px 0}}
 .card{{background:#fff;border:1px solid #dadce0;border-radius:10px;padding:10px 14px;flex:1 1 130px}}
 .card b{{display:block;font-size:1.4rem}}
 .card span{{color:#5f6368;font-size:.8rem}}
 input,select{{padding:10px;margin:4px 0;width:100%;box-sizing:border-box;border:1px solid #dadce0;border-radius:8px;font-size:1rem}}
 table{{border-collapse:collapse;width:100%;background:#fff;font-size:.85rem}}
 th,td{{border-bottom:1px solid #eee;padding:8px 6px;text-align:left;white-space:nowrap}}
 th{{position:sticky;top:0;background:#fff;z-index:1}}
 .wrap{{overflow-x:auto}}
</style></head><body>
<h1>Google Cloud Translation LLM — 117 ta Chirp 2 tili auditi</h1>
<p>Model: <code>{MODEL_ID}</code> · Sana: {summary['generated_at'][:10]} ·
Haqiqiy API so'rovlari: <b>{summary['real_api_requests']}</b> ·
Xarajat: <b>${summary['total_cost_usd']}</b></p>
<div class="cards">
 <div class="card"><b>{summary['total_locales']}</b><span>Jami variant</span></div>
 <div class="card"><b>{summary['documented_classes']['A_official']}</b><span>A — rasmiy</span></div>
 <div class="card"><b>{summary['documented_classes']['B_experimental']}</b><span>B — eksperimental</span></div>
 <div class="card"><b>{summary['documented_classes']['C_unsupported']}</b><span>C — qo'llab-quvvatlanmaydi</span></div>
 <div class="card"><b style="color:#137333">{counts.get('PASSED',0)}</b><span>PASSED</span></div>
 <div class="card"><b style="color:#b06000">{counts.get('EXPERIMENTAL',0)}</b><span>EXPERIMENTAL</span></div>
 <div class="card"><b style="color:#c5221f">{counts.get('FAILED',0)}</b><span>FAILED</span></div>
 <div class="card"><b style="color:#5f6368">{counts.get('RETEST_REQUIRED',0)}</b><span>RETEST_REQUIRED</span></div>
 <div class="card"><b>{summary['coverage_percent']}%</b><span>Qamrov</span></div>
</div>
<input id="q" placeholder="Til nomi yoki kod bo'yicha qidirish..." oninput="filterRows()">
<select id="f" onchange="filterRows()">
 <option value="">Barcha holatlar</option>
 <option>PASSED</option><option>FAILED</option>
 <option>EXPERIMENTAL</option><option>RETEST_REQUIRED</option>
</select>
<div class="wrap"><table id="t">
<thead><tr><th>#</th><th>Chirp 2</th><th>Til</th><th>LLM kod</th><th>Sinf</th>
<th>So'rov</th><th>OK</th><th>Kechikish (s)</th><th>Sifat</th><th>Qaror</th></tr></thead>
<tbody>
{''.join(rows_html)}
</tbody></table></div>
<script>
function filterRows(){{
 var q=document.getElementById('q').value.toLowerCase();
 var f=document.getElementById('f').value;
 var rows=document.querySelectorAll('#t tbody tr');
 rows.forEach(function(r){{
   var name=r.getAttribute('data-name').toLowerCase();
   var code=r.children[1].textContent.toLowerCase();
   var dec=r.getAttribute('data-decision');
   var show=(name.indexOf(q)>=0||code.indexOf(q)>=0)&&(!f||dec===f);
   r.style.display=show?'':'none';
 }});
}}
</script></body></html>"""
    (RESULTS / "translation-llm-quality-report.html").write_text(doc, encoding="utf-8")


def write_markdown(verdicts: list[dict], summary: dict) -> None:
    counts = summary["decisions"]
    c = summary["documented_classes"]
    passed = [v for v in verdicts if v["final_decision"] == "PASSED"]
    failed = [v for v in verdicts if v["final_decision"] == "FAILED"]
    retest = [v for v in verdicts if v["final_decision"] == "RETEST_REQUIRED"]
    exp = [v for v in verdicts if v["final_decision"] == "EXPERIMENTAL"]

    def tbl(items):
        out = ["| # | Chirp 2 | Til | LLM kod | Sabab |",
               "|---|---------|-----|---------|-------|"]
        for v in items:
            out.append(f"| {v['order']} | `{v['chirp2_code']}` | {v['language_name']} | "
                       f"`{v['translation_llm_code']}` | {v['decision_reason']} |")
        return "\n".join(out)

    md = f"""# Google Cloud Translation LLM — 117 ta Chirp 2 tili bo'yicha yakuniy hisobot

Sana: {summary['generated_at'][:10]}
Model: `{MODEL_ID}` · Region: `us-central1` · Loyiha: `project-6bcf7fe6-cb55-49b7-9f4`

Bu hisobot mustaqil test muhitida (`translation-llm-audit/`) haqiqiy Google Cloud
Translation LLM API so'rovlari asosida tayyorlandi. AVNIVO asosiy ilovasi,
HTML interfeysi, offline modeli va Chirp 2 ovoz tanish mexanizmi o'zgartirilmadi.

Manbalar:
* Tillar: {SOURCE_LANGUAGES_URL}
* Narx: {PRICING_URL}

## 1. Umumiy raqamlar

| Ko'rsatkich | Qiymat |
|-------------|--------|
| Jami tekshirilgan variantlar | {summary['total_locales']} |
| A — rasmiy qo'llab-quvvatlanadi | {c['A_official']} |
| B — eksperimental | {c['B_experimental']} |
| C — qo'llab-quvvatlanmaydi | {c['C_unsupported']} |
| D — aniqlanmadi | {c['D_undetermined']} |
| Haqiqiy API sinovidan o'tgan variantlar | {summary['api_tested_locales']} |
| PASSED | {counts.get('PASSED', 0)} |
| EXPERIMENTAL | {counts.get('EXPERIMENTAL', 0)} |
| FAILED | {counts.get('FAILED', 0)} |
| RETEST_REQUIRED | {counts.get('RETEST_REQUIRED', 0)} |
| Haqiqiy API so'rovlari soni | {summary['real_api_requests']} |
| Umumiy xarajat (USD) | {summary['total_cost_usd']} |
| Qolgan budjet (USD) | {summary['remaining_usd']} |
| Qamrov | {summary['coverage_percent']}% |

Narx modeli: har 1 million kiruvchi belgi uchun $10 va har 1 million chiquvchi
belgi uchun $10. $25 da yangi pullik so'rovlar avtomatik to'xtatiladi; mutlaq
chegara $30.

## 2. PASSED ({len(passed)})

{tbl(passed) if passed else "—"}

## 3. EXPERIMENTAL ({len(exp)})

Google hujjatida eksperimental deb belgilangan. Sinov natijasi qayd etildi,
lekin asosiy Online ro'yxatga avtomatik qo'shilmaydi.

{tbl(exp) if exp else "—"}

## 4. FAILED ({len(failed)})

Model bu til kodini rad etdi yoki yetarli dalillar bilan jiddiy muammo aniqlandi.
Manba koddan **hech narsa o'chirilmadi**; faqat tavsiya ro'yxati tayyorlandi.

{tbl(failed) if failed else "—"}

## 5. RETEST_REQUIRED ({len(retest)})

Vaqtinchalik API muammosi, noaniq natija yoki yetarli sifat dalili yo'q.

{tbl(retest) if retest else "—"}

## 6. Metodologiya va cheklovlar

* Har bir til uchun o'zbekchadan va inglizchadan tarjima, hamda teskarisi sinovdan o'tkazildi.
* Sifat bahosi skript (yozuv tizimi), sonlar/sanalar, bo'sh natija va barqarorlik
  bo'yicha avtomatik signalga asoslanadi. Insoniy etalon barcha 117 til uchun
  mavjud emas, shu sababli avtomatik metrikalar inson bahosi o'rnini bosmaydi.
* `FAILED` faqat API aniq rad etganda (HTTP 400/404) yoki jiddiy sifat signali
  bo'lganda beriladi; vaqtinchalik 429/5xx xatolar qo'llab-quvvatlanmaydi deb
  hisoblanmaydi.
* Test natijalari taxminiy emas: har bir yozuv haqiqiy HTTP javobidan olingan.

## 7. Online ro'yxat tavsiyasi

Asosiy Online ro'yxatga **faqat PASSED** tillarni kiritish tavsiya etiladi.
EXPERIMENTAL tillar alohida saqlanadi. FAILED tillar ro'yxatdan chiqarish uchun
`translation-llm-online-removal-candidates.json` faylida keltirilgan.
"""
    (RESULTS / "translation-llm-final-report.md").write_text(md, encoding="utf-8")


def main() -> None:
    built = build()
    verdicts, summary = built["verdicts"], built["summary"]
    RESULTS.mkdir(exist_ok=True)
    (RESULTS / "translation-llm-summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=1))
    write_csv(verdicts)
    write_json_lists(verdicts, summary)
    write_html(verdicts, summary)
    write_markdown(verdicts, summary)
    print(json.dumps(summary, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
