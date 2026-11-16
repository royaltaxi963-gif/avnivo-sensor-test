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
- Zero-traffic candidate: `https://candidate---avnivo-sensor-test-5xwrf7netq-ew.a.run.app`,
  deployed by `.github/workflows/cloud-run-candidate.yml` when
  `.github/candidate-deploy-request` changes. Use it to verify code changes
  without shifting production traffic. Promotion (traffic shift) is a separate
  deliberate step.

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
  CORS, silent/garbage/low-energy audio, repeated recognition, rate-limit
  burst). The rate-limit probe must send **valid** audio; empty audio is
  rejected by the size check before the limiter runs.
- `consistency.py` — asserts UI matrix == backend registry == live endpoint.
- `report.py` — writes `report.html` / `report.csv` / `report.json`; excludes
  invalidated rows and merges `weak_locales.jsonl`.
- `watch_recovery.py` — polls the STT route until it recovers.

Cost: Speech-to-Text V2 Standard is $0.016/min (first 500k min/month). A full
117-locale × 3-sample run costs ~$1.10. Spend so far: $1.1456 of $30.

## Campaign status

- Phases 1–3 complete: 117/117 locales tested with real audio, 373 samples,
  all PASS (93 EXCELLENT / 14 GOOD / 5 FAIR / 5 POOR by mean CER).
- Phase 4 complete: retries, error mapping and the near-silence gate are
  implemented and verified live on the zero-traffic candidate.
- Open item: promote the candidate to production (traffic shift) — deliberate,
  not yet done. Until then the production revision still hallucinates `avnivo`
  for silence.

## Gotchas

- The endpoint used to map any non-200 from Google to a generic HTTP 502
  ("Google Speech transcription failed"), which hid quota/IAM/transient causes
  *and* client-side audio faults. Fixed: Google 400 now returns 400, and every
  non-200 upstream status is logged with its body. Watch for the same collapse
  if the mapping is edited.
- Transient upstream failures (429/5xx and dropped connections) are retried up
  to `RETRY_ATTEMPTS` with backoff; client faults (400/401/403/404) are not
  retried. The final status keeps the normal mapping, so a provider that stays
  down still fails fast.
- Chirp 2 auto-decode accepts containerised 16-bit PCM (WAV), webm/ogg, and mp3.
  It **rejects 32-bit float WAV and headerless PCM** with 400 INVALID_ARGUMENT.
  FLEURS source clips are 32-bit float, so anything that posts them raw (instead
  of converting via ffmpeg to `pcm_s16le` first) will see failures that look
  like outages.
- `uz-UZ` carries a phrase boost (`"Avnivo"` @ 20.0). Verified live: it makes
  Chirp 2 return `"avnivo"` for digital silence and low-energy tones, not an
  empty transcript. `main.py` now gates near-silent 16-bit PCM WAV locally
  (`_is_near_silent`, RMS < 1e-3) and returns an empty transcript before the
  paid call. webm/opus (browser audio) is not inspected (no ffmpeg in the
  image) and is still sent to Google.
- Tests that assert specific DOM selectors rot silently when the picker UI is
  redesigned and may drop out of CI — keep them tied to the live contract
  (`CHIRP2_MATRIX`, `chirp2LocaleFor`, etc.).
