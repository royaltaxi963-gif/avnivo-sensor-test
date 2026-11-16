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
| `report.py` | Builds all deliverables from recorded evidence |
| `fix_checkpoint.py` | One-off repair (drops degenerate/transient records) |
| `run-config.json` | Phase, locale filter, `deep` list, request cap |
| `results/` | All generated evidence and reports |

## Running it

The runner is `.github/workflows/translation-llm-audit.yml` (push-triggered on
this branch, since `workflow_dispatch` is unavailable on a non-default branch).
It authenticates with Workload Identity Federation, runs the harness, generates
the deliverables and commits them back. Set `run-config.json`, push, and the
run resumes from the checkpoint.

Parameters: `phase` (`pilot`/`full`), `only` (locale filter), `deep`
(locales to probe fully even when undocumented), `max_requests`.

## Cost

Translation LLM is billed at USD 10 per 1M input characters + USD 10 per 1M
output characters. The harness stops before $25 and the ceiling is $30. The
completed run cost **$4.01** for 6,651 real requests (6,606 recorded calls;
rejected calls are not billed).

## Result summary

117 locales, 100% API coverage: 77 PASSED, 13 EXPERIMENTAL, 24 FAILED,
3 RETEST_REQUIRED (documentation/API conflict). See
`results/translation-llm-final-report.md`.
