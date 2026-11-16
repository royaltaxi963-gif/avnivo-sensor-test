# AGENTS.md — AVNIVO sensor test

## What this repo is

AVNIVO translator app: a static HTML frontend (`avnivo-v11-*.html`) plus a
FastAPI backend in `translator-api/`. The current frontend is
`avnivo-v11-49-chirp2-multilingual.html`. Speech recognition uses Google
Speech-to-Text V2 (Chirp 2); translation uses Google Cloud Translation; TTS is
a separate endpoint.

## Layout

- `translator-api/main.py` — API. `/api/transcribe-google` is the Chirp 2 STT
  route; `/api/chirp2-languages` exposes the locale registry; `/api/translate*`,
  `/api/tts` are unrelated.
- `translator-api/chirp2_languages.py` — the 117-locale registry
  (`CHIRP2_LOCALES`), names, and `CHIRP2_ADAPTATION` phrase boosts.
- `avnivo-v11-49-chirp2-multilingual.html` — frontend; embeds
  `window.CHIRP2_MATRIX` which must mirror the backend registry.
- `docs/chirp2-language-support.md` — authoritative 117-locale matrix from
  official docs. `docs/chirp2-117-locale-verification.md` — real-audio results.
- `verification/chirp2_campaign/` — the 117-locale verification harness and
  evidence (see below).

## Environment

- Python: `/usr/local/bin/python`; a test venv exists at `.venv-test`.
- Run the suite: `.venv-test/bin/python -m pytest translator-api -q`.
- Deployed endpoint: `https://chirp2-test---avnivo-sensor-test-5xwrf7netq-ew.a.run.app`
  (Cloud Run, region `us-central1`, model `chirp_2`). No local GCP creds;
  Google auth happens inside Cloud Run.

## Constraints (product owner)

Do not change: HTML design, microphone position/animation, online/offline
settings, translation and TTS modules, production traffic, or the 117-locale
list. Never create paid resources without explicit approval.

## Verification harness

`verification/chirp2_campaign/` — one script per concern:

- `samples.py` — acquires real speech: FLEURS (HuggingFace, partial gzip),
  Tatoeba, Wikimedia Commons (Lingua Libre). Commons needs a contact URL in the
  User-Agent or it returns 429.
- `metrics.py` — WER/CER via `jiwer` 4.0 (`process_words` / `process_characters`;
  char-level for scripts without word spacing).
- `run_campaign.py` — sends samples to the endpoint, enforces a hard budget,
  resumes from `evidence.jsonl`.
- `reliability.py` — edge cases (bad locale, wrong content type, oversized,
  CORS, silent/garbage audio).
- `consistency.py` — asserts UI matrix == backend registry == live endpoint.
- `report.py` — writes `report.html` / `report.csv` / `report.json`.
- `watch_recovery.py` — polls the STT route until it recovers.

Cost: Speech-to-Text V2 Standard is $0.016/min (first 500k min/month). A full
117-locale × 3-sample run costs ~$1.10.

## Gotchas

- The deployed endpoint maps any non-200 from Google to a generic HTTP 502
  ("Google Speech transcription failed"), which hides quota/IAM/transient
  causes — check Cloud Run logs and the Speech-to-Text quota directly.
- `uz-UZ` carries a phrase boost (`"Avnivo"` @ 20.0). It amplifies hallucinated
  "avnivo" output on near-silent audio; consider an audio-energy gate.
- Tests that assert specific DOM selectors rot silently when the picker UI is
  redesigned and may drop out of CI — keep them tied to the live contract
  (`CHIRP2_MATRIX`, `chirp2LocaleFor`, etc.).
