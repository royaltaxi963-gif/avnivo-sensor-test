# AVNIVO TTS validation toolkit

Objective, budget-bounded validation for `POST /api/tts`.

## Layout

| File | Purpose |
|------|---------|
| `audio_analysis.py` | Objective WAV analyzer (stdlib only). No subjective scores. |
| `budget.py` | Verified pricing constants, cost model, append-only ledger. |
| `run_paid_tests.py` | Staged paid runner; sequential, budget-gated, checkpointed. |
| `generate_report.py` | Aggregates the ledger + metrics into `ledger/report_data.json`. |
| `test_tts_reliability.py` | 49 mocked reliability/security tests. No network. |
| `test_audio_analysis.py` | 12 analyzer + budget unit tests. |

`artifacts/` and `ledger/` are git-ignored; audio samples stay out of the repo.

## Safety model

- Paid work is **gated**: a request is refused if conservative estimated spend
  would exceed the **$18** stop threshold (of a **$20** ceiling).
- **Sequential by default.** Concurrency is opt-in and bounded.
- **Append-only ledger + checkpoint**: an interruption never repeats a paid
  request. Reconcile with `--reconcile` (only HTTP 200 counts as done).
- 429 is treated as a correct response and retried with back-off, not as failure.
- The local ledger is an estimate, **not** a Google Cloud billing cap.

## Usage

```bash
# Local tests only — no network, no cost
python -m pytest tests/tts_validation/ -q

# Paid stages (require budget approval), paced under the rate limit
python tests/tts_validation/run_paid_tests.py --stage A --delay 6.5
python tests/tts_validation/run_paid_tests.py --stage B --delay 6.5
python tests/tts_validation/run_paid_tests.py --stage C --delay 6.5
python tests/tts_validation/run_paid_tests.py --stage styles --delay 6.5
python tests/tts_validation/run_paid_tests.py --stage concurrency --concurrency 4

# Spend and coverage summary
python tests/tts_validation/run_paid_tests.py --summary
python tests/tts_validation/generate_report.py
```

Override the target with `AVNIVO_TTS_URL`.
