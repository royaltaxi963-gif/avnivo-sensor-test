# Translation LLM audit — 117 Chirp 2 locales

Independent, real-API audit of Google Cloud Translation LLM
(`general/translation-llm`) coverage for the 117 Chirp 2 locales AVNIVO's
Online STT uses. This directory is self-contained: it never imports or edits
the application, its HTML UI, the offline model, or the Chirp 2 recogniser.

## What it does

1. Reads the 117 BCP-47 locales from `translator-api/chirp2_languages.py`
   (`language_matrix.py` maps each to its Translation LLM code and classifies
   it A official / B experimental / C unsupported against the official docs).
2. Sends real requests to the Cloud Translation v2 API with
   `model=general/translation-llm`, in both directions, across 20 phrase
   categories plus stability repeats.
3. Scores each response (empty output, wrong script, lost numbers, identical
   output) with Unicode-aware digit handling, and writes every result to disk
   immediately with checkpoint/resume.
4. Enforces the budget before every request and stops at the hard limit.

No mock data is ever written. Every record in `results/` comes from a live HTTP
response.

## Files

| File | Purpose |
|------|---------|
| `language_matrix.py` | 117-locale → LLM-code mapping and A/B/C classification |
| `corpus.py` | 20 test categories (uz + en source phrases) |
| `quality.py` | Script/number/empty/identity scoring |
| `harness.py` | Real API client, budget guard, checkpoint/resume |
| `diagnose.py` | Targeted FAILED/experimental/disputed retests (tagged, fresh requests) |
| `report.py` | Builds all deliverables from recorded evidence |
| `fix_checkpoint.py` | One-off repair (drops degenerate/transient records) |
| `run-config.json` | Phase, locale filter, `deep` list, `mode`, request cap |
| `results/` | All generated evidence and reports |

## Running it

The runner is `.github/workflows/translation-llm-audit.yml` (push-triggered on
this branch, since `workflow_dispatch` is unavailable on a non-default branch).
It authenticates with Workload Identity Federation, runs the harness, generates
the deliverables and commits them back. Set `run-config.json`, push, and the
run resumes from the checkpoint.

Parameters: `phase` (`pilot`/`full`), `only` (locale filter), `deep`
(locales to probe fully even when undocumented), `max_requests`, and `mode`
(`failed`/`disputed`/`experimental`/`regression` to run `diagnose.py`).

Retest requests are keyed with a per-mode `tag`, so a retest always issues new
API calls instead of being served from the earlier corpus cache.

## Recovery and targeted retest

The audit was resumed from its verified checkpoint without repeating the
original 6,651 requests. The preserved evidence is unchanged; additional
targeted work was appended:

* **FAILED diagnosis** — each of the 24 rejected locales was probed from both
  `uz` and `en` sources. All 24 fail from both, so the *language code itself*
  is unsupported, not merely the pair. All 24 are absent from Google's official
  Translation LLM table. No supported alternative code or model exists for
  them, and no language was silently substituted.
* **Disputed retest** — `si-LK`, `xh-ZA`, `yo-NG` reproduce 20/20 success with
  correct native scripts (Sinhala, Latin Xhosa, Yoruba with tone marks), though
  they are absent from the official documentation. Kept RETEST_REQUIRED.
* **Experimental retest** — all 13 experimental locales succeed 6/6 with the
  expected script.
* **Regression** — a 10-locale sample of PASSED locales still succeeds 20/20.

## Cost

Translation LLM is billed at USD 10 per 1M input characters + USD 10 per 1M
output characters. The harness stops before $25 and the ceiling is $30. After
the recovery and targeted retests the total is **$4.19** for 6,881 real
requests (rejected calls are not billed).

## Result summary

117 locales, 100% API coverage: 77 PASSED, 13 EXPERIMENTAL, 24 FAILED,
3 RETEST_REQUIRED (documentation/API conflict). See
`results/translation-llm-final-report.md`.
