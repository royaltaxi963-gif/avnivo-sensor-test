"""Build the final HTML / CSV / JSON report from the campaign evidence.

Reads ``evidence.jsonl`` (one row per real API call) and ``reliability.json``,
aggregates per locale, and writes ``report.html``, ``report.csv`` and
``report.json``. Only measured values are reported; a locale with no real audio
result is marked NOT TESTED, never PASS.
"""
from __future__ import annotations

import csv
import json
import statistics
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
import sys
sys.path.insert(0, str(REPO / "translator-api"))
import chirp2_languages as registry

EVIDENCE = HERE / "evidence.jsonl"
WEAK_EVIDENCE = HERE / "weak_locales.jsonl"
RELIABILITY = HERE / "reliability.json"
PRICE_PER_SECOND = 0.016 / 60.0
HARD_CAP_USD = 30.0


def load_rows():
    rows = []
    for path in (EVIDENCE, WEAK_EVIDENCE):
        if not path.exists():
            continue
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                rows.append(json.loads(line))
    # Rows explicitly invalidated (e.g. a sample that turned out to be the
    # wrong language) are kept in the file for audit but excluded from scoring.
    return [r for r in rows if not r.get("invalidated")]


def p95(values):
    if not values:
        return None
    values = sorted(values)
    index = max(0, int(round(0.95 * (len(values) - 1))))
    return round(values[index], 3)


def aggregate(rows):
    by_locale = defaultdict(list)
    for row in rows:
        by_locale[row["locale"]].append(row)

    table = []
    for code in sorted(registry.CHIRP2_LOCALES):
        samples = by_locale.get(code, [])
        attempted = [s for s in samples if s.get("http_status") is not None and s.get("status") != "NOT_TESTED"]
        passed = [s for s in attempted if s.get("http_status") == 200 and str(s.get("hypothesis", "")).strip()]
        wers = [s["wer"] for s in passed if s.get("wer") is not None]
        cers = [s["cer"] for s in passed if s.get("cer") is not None]
        latencies = [s["latency_seconds"] for s in attempted if s.get("latency_seconds") is not None]
        exact = sum(1 for s in passed if s.get("exact_match"))
        human = sum(1 for s in attempted if not s.get("synthetic"))
        synthetic = sum(1 for s in attempted if s.get("synthetic"))
        duration = round(sum(s.get("duration_seconds", 0) for s in attempted), 2)

        # Execution status reflects whether the API transcribed the audio; it is
        # deliberately separate from how accurate that transcript was.
        if not attempted:
            execution = "NOT TESTED"
        elif len(passed) == len(attempted):
            execution = "PASS"
        else:
            execution = "FAIL"

        mean_cer = statistics.mean(cers) if cers else None
        mean_wer = statistics.mean(wers) if wers else None
        if mean_cer is None:
            accuracy = "NOT MEASURED"
        elif mean_cer < 0.10:
            accuracy = "EXCELLENT"
        elif mean_cer < 0.20:
            accuracy = "GOOD"
        elif mean_cer < 0.35:
            accuracy = "FAIR"
        else:
            accuracy = "POOR"

        table.append({
            "locale": code,
            "name": registry.CHIRP2_LOCALE_NAMES.get(code, ""),
            "samples": len(attempted),
            "human_samples": human,
            "synthetic_samples": synthetic,
            "duration_seconds": duration,
            "wer": round(statistics.mean(wers), 4) if wers else None,
            "cer": round(statistics.mean(cers), 4) if cers else None,
            "word_accuracy": round(1 - statistics.mean(wers), 4) if wers else None,
            "wer_is_char_level": any(s.get("wer_is_char_level") for s in passed),
            "exact_match_rate": round(exact / len(passed), 4) if passed else None,
            "api_success_rate": round(len([s for s in attempted if s.get("http_status") == 200]) / len(attempted), 4) if attempted else None,
            "latency_avg": round(statistics.mean(latencies), 3) if latencies else None,
            "latency_p95": p95(latencies),
            "status": execution,
            "accuracy": accuracy,
            "sources": sorted({s.get("source") for s in attempted}),
            "errors": [s.get("error") for s in attempted if s.get("error")],
        })
    return table


def summary(rows, table):
    spend = round(sum(r.get("billable_seconds", 0) for r in rows) * PRICE_PER_SECOND, 4)
    tested = [t for t in table if t["status"] in ("PASS", "FAIL")]
    scored = [t for t in table if t["wer"] is not None]
    ranked = sorted(scored, key=lambda t: t["wer"])
    status_counts = defaultdict(int)
    accuracy_counts = defaultdict(int)
    for t in table:
        status_counts[t["status"]] += 1
        accuracy_counts[t["accuracy"]] += 1
    return {
        "accuracy_counts": dict(accuracy_counts),
        "total_locales": len(table),
        "tested_locales": len(tested),
        "samples": len(rows),
        "real_api_calls": len([r for r in rows if r.get("http_status") is not None]),
        "status_counts": dict(status_counts),
        "avg_wer": round(statistics.mean([t["wer"] for t in scored]), 4) if scored else None,
        "avg_cer": round(statistics.mean([t["cer"] for t in scored if t["cer"] is not None]), 4) if scored else None,
        "exact_match_rate": round(statistics.mean([t["exact_match_rate"] for t in scored if t["exact_match_rate"] is not None]), 4) if scored else None,
        "best": [(t["locale"], t["wer"]) for t in ranked[:5]],
        "worst": [(t["locale"], t["wer"]) for t in ranked[-5:]],
        "spend_usd": spend,
        "budget_usd": HARD_CAP_USD,
        "remaining_usd": round(HARD_CAP_USD - spend, 4),
        "human_samples": len([r for r in rows if not r.get("synthetic")]),
        "synthetic_samples": len([r for r in rows if r.get("synthetic")]),
    }


def write_csv(table):
    path = HERE / "report.csv"
    fields = ["locale", "name", "samples", "human_samples", "synthetic_samples",
              "duration_seconds", "wer", "cer", "word_accuracy", "exact_match_rate",
              "api_success_rate", "latency_avg", "latency_p95", "status", "sources"]
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        for row in table:
            row = dict(row)
            row["sources"] = ";".join(row.get("sources") or [])
            writer.writerow(row)
    return path


def write_html(table, stats, reliability):
    def pct(value):
        return "—" if value is None else f"{value * 100:.1f}%"

    rows_html = "\n".join(
        f"<tr class='{t['status'].replace(' ', '_').lower()}'>"
        f"<td>{t['name']}</td><td><code>{t['locale']}</code></td>"
        f"<td>{t['samples']}</td><td>{t['duration_seconds']}s</td>"
        f"<td>{pct(t['wer'])}{'*' if t['wer_is_char_level'] else ''}</td>"
        f"<td>{pct(t['cer'])}</td><td>{pct(t['word_accuracy'])}</td>"
        f"<td>{pct(t['exact_match_rate'])}</td><td>{pct(t['api_success_rate'])}</td>"
        f"<td>{t['latency_avg'] or '—'}s</td><td>{t['latency_p95'] or '—'}s</td>"
        f"<td>{t['status']}</td><td class='acc-{t['accuracy'].split()[0].lower()}'>{t['accuracy']}</td>"
        f"<td>{'; '.join(t['sources'])}</td></tr>"
        for t in table
    )
    rel_html = "\n".join(
        f"<tr><td>{r['probe']}</td><td>{r['observed']['http_status']}</td>"
        f"<td><code>{r['observed']['body'][:120]}</code></td></tr>"
        for r in reliability
    )
    counts = stats["status_counts"]
    acc = stats.get("accuracy_counts", {})
    html = f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<title>AVNIVO Chirp 2 — 117-locale verification</title>
<style>
 body{{font:14px/1.5 system-ui;margin:24px;color:#1c1917;background:#fafaf9}}
 h1{{font-size:22px}} h2{{font-size:17px;margin-top:28px}}
 table{{border-collapse:collapse;width:100%;font-size:13px}}
 th,td{{border:1px solid #d6d3d1;padding:4px 7px;text-align:left}}
 th{{background:#f5f5f4;position:sticky;top:0}}
 tr.pass td:nth-child(12){{color:#15803d;font-weight:600}}
 tr.fail td:nth-child(12){{color:#b91c1c;font-weight:600}}
 tr.not_tested td:nth-child(12){{color:#78716c}}
 tr td:nth-child(13){{font-weight:600}}
 .acc-excellent{{color:#15803d}} .acc-good{{color:#4d7c0f}}
 .acc-fair{{color:#b45309}} .acc-poor{{color:#b91c1c}}
 .cards{{display:flex;flex-wrap:wrap;gap:12px;margin:12px 0}}
 .card{{border:1px solid #d6d3d1;border-radius:8px;padding:10px 14px;background:#fff;min-width:150px}}
 .card b{{display:block;font-size:20px}}
 code{{background:#f5f5f4;padding:1px 4px;border-radius:3px}}
 small{{color:#57534e}}
</style></head><body>
<h1>AVNIVO Chirp 2 — 117-locale real-audio verification</h1>
<small>Model <code>chirp_2</code> · region us-central1 · endpoint <code>{stats.get('base_url','')}</code></small>
<div class="cards">
 <div class="card"><b>{stats['tested_locales']}/{stats['total_locales']}</b>locales tested</div>
 <div class="card"><b>{stats['samples']}</b>real audio samples</div>
 <div class="card"><b>{counts.get('PASS',0)}</b>PASS (execution)</div>
 <div class="card"><b>{counts.get('FAIL',0)}</b>FAIL</div>
 <div class="card"><b>{counts.get('NOT TESTED',0)}</b>NOT TESTED</div>
 <div class="card"><b>{acc.get('EXCELLENT',0)}</b>accuracy excellent</div>
 <div class="card"><b>{acc.get('GOOD',0)}</b>accuracy good</div>
 <div class="card"><b>{acc.get('FAIR',0)}</b>accuracy fair</div>
 <div class="card"><b>{acc.get('POOR',0)}</b>accuracy poor</div>
 <div class="card"><b>{pct(stats['avg_wer'])}</b>mean WER (scored)</div>
 <div class="card"><b>{pct(stats['avg_cer'])}</b>mean CER</div>
 <div class="card"><b>${stats['spend_usd']}</b>spent of ${stats['budget_usd']}</div>
</div>
<p><small>Human speech: {stats['human_samples']} · synthetic (espeak-ng, flagged): {stats['synthetic_samples']}.
WER marked <code>*</code> is character-level (scripts without word spacing).</small></p>
<h2>Per-locale results</h2>
<table><thead><tr><th>Language</th><th>Locale</th><th>Samples</th><th>Duration</th>
<th>WER</th><th>CER</th><th>Word acc.</th><th>Exact</th><th>API ok</th>
<th>Avg latency</th><th>P95</th><th>Execution</th><th>Accuracy</th><th>Sources</th></tr></thead>
<tbody>{rows_html}</tbody></table>
<h2>Reliability probes</h2>
<table><thead><tr><th>Probe</th><th>HTTP</th><th>Response</th></tr></thead><tbody>{rel_html}</tbody></table>
</body></html>"""
    path = HERE / "report.html"
    path.write_text(html, encoding="utf-8")
    return path


def main():
    rows = load_rows()
    reliability = json.loads(RELIABILITY.read_text(encoding="utf-8")) if RELIABILITY.exists() else []
    table = aggregate(rows)
    stats = summary(rows, table)
    stats["base_url"] = "https://chirp2-test---avnivo-sensor-test-5xwrf7netq-ew.a.run.app"
    json_path = HERE / "report.json"
    json_path.write_text(json.dumps({"summary": stats, "locales": table, "reliability": reliability},
                                    indent=1, ensure_ascii=False), encoding="utf-8")
    csv_path = write_csv(table)
    html_path = write_html(table, stats, reliability)
    print(json.dumps(stats, indent=1, ensure_ascii=False))
    print("wrote", json_path, csv_path, html_path)


if __name__ == "__main__":
    main()
