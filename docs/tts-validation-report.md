# AVNIVO TTS — Multilingual Quality Assurance Report

**Date:** 2026-11-16
**Branch:** `tts-validation-2026-11` (from `main` @ `c1d2d99`)
**Endpoint under test:** `POST /api/tts`
**Deployed service:** `avnivo-translation-test`, Cloud Run, `europe-west1`
**Backend:** `translator-api/main.py`
**Paid spend:** **$0.1337** estimated of a **$20.00** hard ceiling

---

## A. Executive summary

The deployed TTS endpoint is real, reachable, and functionally correct. It uses
the Google Vertex AI model **`gemini-3.8-flash-tts`** through
`generateContent`, returns valid 24 kHz mono WAV audio, and enforces input
validation, per-IP throttling, an origin guard, and CORS preflight. The deployed
service was confirmed to match the repository implementation (identical CORS
headers, validation messages, and OpenAPI route set).

Paid evidence covers **124 requests**: **92 HTTP 200** responses with valid,
non-silent audio across **33 languages** and **all 10 configured voices**, plus
**32 HTTP 429** responses produced by the service's own rate limiter (correct
behaviour, not a provider fault). Local, mocked, non-billable tests cover 49
reliability/security cases and 12 analyzer/budget unit cases — all pass.

Release readiness is **conditional**. Functionally the endpoint is sound. What
is *not* proven — and cannot be proven by an API response — is speech quality:
pronunciation accuracy and whether the five emotional styles are perceptibly
different. Those items are explicitly marked **PENDING HUMAN LISTENING REVIEW**.

**No production change, deployment, or merge was performed.**

---

## B. Model compatibility

| Item | Verified value | Evidence |
|------|----------------|----------|
| Model identifier | `gemini-3.8-flash-tts` | `translator-api/main.py` L420; confirmed in official Vertex AI docs |
| API endpoint | `POST https://aiplatform.googleapis.com/v1/projects/{project}/locations/global/publishers/google/models/gemini-3.8-flash-tts:generateContent` | main.py L419-423 |
| Region | `global` | matches documented available locations (`global`, `us`, `eu`) |
| Request schema | `contents[].parts[].text` + `speechMetadata.style`; `generationConfig.responseModalities=["AUDIO"]`; `speechConfig.voiceConfig.voice` | main.py L407-417; matches official REST sample |
| Output format | WAV, 16-bit PCM, 24 kHz, mono (unary default) | Official docs; live samples confirmed `channels=1, sampwidth=2, framerate=24000` |
| Languages | 132 supported by the model; **33 exercised live**, including `uz`, `ru`, `en`, `tr` | Official docs language table; live tests |
| Voices | 10 configured, all present in the model's 30 prebuilt voices | Official docs voice list; live tests |
| Emotion/style | `speechMetadata.style` string, sent per request | main.py L410; official REST sample |

### Important findings

1. **`language` is accepted but ignored.** The frontend sends
   `{text, language, voice}`, and the backend reads only `text` and `voice`.
   The model auto-detects language from the text. This is documented behaviour
   and not a defect, but the field is effectively decorative and could mislead
   future maintainers.
2. **Style is auto-detected, not user-selected.** `_detect_tts_style()` chooses
   one of five styles using hard-coded Uzbek/Russian/English keyword matching.
   The resulting style is returned in the `X-AVNIVO-TTS-Style` response header.
   The five styles in `_TTS_STYLES` are the only ones reachable.
3. **Preview model.** `gemini-3.8-flash-tts` is a **Preview** model. Google's
   pricing page shows a price change on 2027-01-01 ($9/1M → $18/1M audio
   tokens) and preview models can change without notice. This is a forward risk.

---

## C. Language results

**Tested and passed (HTTP 200, valid non-silent WAV): 33 languages**

Priority languages (Stage A/B): `uz` (Uzbek), `ru` (Russian), `en` (English),
`tr` (Turkish) — all pass with real audio evidence.

Extended coverage (Stage C, Kore voice unless noted): `de`, `fr`, `es`, `it`,
`pt`, `pl`, `nl`, `sv`, `cs`, `el`, `ro`, `hu`, `fi`, `da`, `ar`, `he`, `fa`,
`hi`, `bn`, `zh`, `ja`, `ko`, `kk`, `az`, `th`, `vi`, `id`, `ms`, `ca`.
Cross-voice checks: `de`/Puck, `ja`/Aoede, `ar`/Charon, `zh`/Leda, `hi`/Orus.

**Unsupported:** none observed. The model documents 132 languages; the remaining
~99 were not exercised (budget/time bound, not a failure). No requested language
was silently substituted by another model.

**Untested:** the ~99 documented languages outside the 33 above, and any
language absent from Google's supported list.

---

## D. Voice compatibility matrix (actually tested)

| Voice | Languages with HTTP 200 | Count |
|-------|-------------------------|-------|
| Kore | all 33 | 33 |
| Aoede | uz, ru, en, tr, ja | 5 |
| Leda | uz, ru, en, tr, zh | 5 |
| Zephyr | uz, ru, en, tr | 4 |
| Achernar | uz, ru, en, tr | 4 |
| Puck | uz, ru, en, tr, de | 5 |
| Charon | uz, ru, en, tr, ar | 5 |
| Orus | uz, ru, en, tr, hi | 5 |
| Achird | uz, ru, en, tr | 4 |
| Iapetus | uz, ru, en, tr | 4 |

All ten voices passed in all four priority languages (Stage B, 40/40). No
untested combination is marked as passed. Full 10×33 expansion was not run;
the marginal value did not justify the spend.

---

## E. Audio quality

Objective metrics from **92** generated samples (no subjective scores invented):

| Metric | Result |
|--------|--------|
| Valid WAV container | 92/92 |
| Sample rate / channels | 24000 Hz / mono, all samples |
| Duration range | 3.24 s – 8.76 s (mean 6.40 s) |
| All-silent or empty audio | 0 |
| Excessive leading/trailing silence (>1 s) | 0 |
| DC offset out of range | 0 |
| Clipping above 0.1% of samples | 1 sample (`B-tr-Charon`, 0.107% — marginal) |

The single clipping observation is 133 samples in 5.16 s at 0 dBFS peak; it is
not audible as gross distortion at this density but is recorded for honesty.

**PENDING HUMAN LISTENING REVIEW:** pronunciation accuracy, naturalness, and
whether emotional styles are perceptible. An HTTP 200 with valid audio proves
neither. No MOS, pronunciation percentage, or "native quality" claim is made.

---

## F. Reliability and security

All cases below are local/mocked (non-billable) unless marked *live*.

| Case | Result |
|------|--------|
| Empty / whitespace-only text → 400 | PASS |
| Text > 350 chars → 400; exactly 350 → 200 | PASS |
| Invalid JSON → 400; non-object JSON → 400 | PASS |
| Body > 2048 bytes → 413 | PASS |
| Unsupported voice → 400; non-string voice → 400 | PASS |
| All 10 configured voices accepted | PASS |
| Unknown extra fields ignored | PASS |
| `language` field ignored by backend | PASS |
| Disallowed origin → 403; allowed origin → 200 | PASS |
| CORS preflight (allowed origin) → 200 with correct headers | PASS |
| CORS preflight (unknown origin) → no allow-origin header | PASS |
| Provider timeout / connection error → 502 | PASS |
| Provider 429 → 429; 401/403 → 503; 500/503 → 502 | PASS |
| Missing/malformed base64, empty audio, oversized audio → 502 | PASS |
| Raw PCM wrapped into RIFF/WAV | PASS |
| Style detection for all five styles | PASS |
| `Cache-Control: no-store` and style header present | PASS |
| Rate limit: 13th request in window → 429 | PASS |
| Rate limit is per-IP | PASS |
| Invalid requests do not consume rate-limit budget | PASS |
| Rate limiting (*live*) | PASS — 32 real 429s observed |
| Bounded concurrency, 4 parallel requests (*live*) | PASS — all 200 |
| Browser playback compatibility | See note below |

**Security notes**

- Origin check and per-IP throttling are basic guards, not authentication, and
  reset on restart (the API README says so too). The endpoint is publicly
  callable; there is no server-side auth or spend cap at the provider.
- The rate limit is in-process (`defaultdict` of deques). On Cloud Run with
  multiple instances the effective limit is per-instance, so real-world limits
  are looser than 12/min/IP. This is a **production reliability risk**.
- No API keys, tokens, or personal data appear in logs or artifacts.

**Browser playback compatibility — not verified.** No browser was exercised in
this run. The frontend loads the response into an `Audio` element from a blob
URL; WAV in a `Blob` of type `audio/wav` is broadly supported, but this remains
an assumption, not a tested result.

---

## G. Budget

| Item | Value |
|------|-------|
| Pricing source | Official Google Cloud TTS pricing page (2026-11-16) |
| Input | $0.50 / 1M text tokens (sku 4CB8-5200-1246) |
| Output | $9.00 / 1M audio tokens (sku 5FD7-DAE7-9FBA) |
| Billing unit | 25 audio tokens per second of audio |
| Worst-case per request (model) | $0.010169 |
| Hard ceiling | $20.00 |
| Stop threshold | $18.00 |
| Reserve held back | $2.00 |
| Paid requests made | 124 (92 × HTTP 200, 32 × HTTP 429) |
| **Estimated spend** | **$0.133668** |
| Remaining to ceiling | $19.866332 |

The ledger (`tests/tts_validation/ledger/expenditure.json`) is append-only and
checkpointed; every entry records test ID, language, voice, style, timestamp,
status, latency, estimated cost, artifact, and notes. The runner refuses to
start a request that would push conservative spend past $18.

**Caveat:** the local ledger is an estimate, not an enforceable Google Cloud
billing cap. Billing is subject to Google's own token counting. Because the
observed spend is ~0.7% of the ceiling, the estimate is safe with large margin.

---

## H. Defects

| # | Severity | Defect | Root cause | Reproduce |
|---|----------|--------|------------|-----------|
| 1 | Medium | In-process rate limit is per-instance on Cloud Run; effective global limit is looser than 12/min/IP | `recent = defaultdict(deque)` in module scope, no shared store | Scale to >1 instance; send >12/min from one IP across instances |
| 2 | Low | `language` request field is accepted but ignored | Backend reads only `text` and `voice`; model auto-detects | Send same text with different `language` values; output voice is unchanged |
| 3 | Low | Preview model dependency | `gemini-3.8-flash-tts` is a Preview model with a scheduled 2027 price change | N/A (informational risk) |
| 4 | Low | Marginal clipping in one sample | Peak 0 dBFS, 133 clipped samples in 5.16 s | Analyze `artifacts/B-tr-Charon.wav` |
| 5 | Info | Browser playback path untested | No browser run performed | Open the frontend and press the read-aloud button |

No high-severity defects were found. None of these block functional use.

---

## I. Readiness score

Weighted from explicit, executed criteria. No percentages are invented.

### Functional readiness — 100% (8/8 criteria)
All validation branches, all 10 voices, all 5 styles, error mapping, CORS, and
the live audio path were exercised and pass.

### Audio-quality readiness — 60% (3/5 criteria)
- PASS: valid container, correct format, non-silent, correct duration.
- PASS: no gross truncation, no excessive edge silence.
- FAIL/PENDING: pronunciation accuracy (needs human review).
- FAIL/PENDING: emotional-style perceptibility (needs human review).
- PASS: integrity across 92 samples with one marginal clipping note.

### Production reliability readiness — 70% (7/10 criteria)
- PASS: validation, error mapping, origin guard, CORS, timeout handling.
- PASS: rate limiting works and is per-IP (single instance).
- FAIL/PENDING: global rate limiting under multi-instance scale.
- FAIL/PENDING: browser playback compatibility.
- FAIL/PENDING: provider-side spend cap / authentication.

**Overall: CONDITIONAL GO.** Ship for functional use behind the existing UI.
Do not claim production-grade quality or abuse resistance until the human
listening review and the multi-instance rate-limit fix are done.

---

## J. Next actions

1. **Human listening review** of the style samples
   (`tests/tts_validation/artifacts/S-*.wav`) to confirm the five styles are
   perceptible and do not distort pronunciation.
2. **Human pronunciation spot-check** on `uz` and `ru` samples with a native
   reviewer, since keyword-driven style selection is the main quality risk.
3. **Fix the rate limiter** to use a shared store (e.g. Memorystore/Redis) or
   move the limit to the platform (Cloud Armor / API Gateway) for multi-instance
   correctness.
4. **Add provider-side spend protection** (budget/quota on the Cloud project)
   since the endpoint is publicly callable and unauthenticated.
5. **Browser playback check** on the actual frontend, including the
   `githack`/GitHub Pages origins.
6. **Plan for the Preview→GA transition** and the 2027-01-01 price change;
   pin and re-test the model identifier before then.
7. Optional: extend the voice matrix to more languages if the remaining budget
   is ever spent for coverage rather than assurance.

---

## Evidence inventory

- `tests/tts_validation/run_paid_tests.py` — staged, budget-gated paid runner
  with checkpointing and 429 back-off.
- `tests/tts_validation/audio_analysis.py` — objective WAV analyzer (stdlib).
- `tests/tts_validation/budget.py` — pricing constants and durable ledger.
- `tests/tts_validation/test_tts_reliability.py` — 49 mocked reliability tests.
- `tests/tts_validation/test_audio_analysis.py` — 12 analyzer/budget tests.
- `tests/tts_validation/generate_report.py` — aggregates ledger + metrics.
- `tests/tts_validation/ledger/expenditure.json` — append-only spend ledger.
- `tests/tts_validation/artifacts/*.wav` + `*.metrics.json` — 93 samples with
  per-sample metrics (kept out of git; regenerate with the runner).

Reproduce local tests:
`python -m pytest tests/tts_validation/ -q` (no network, no cost).

Run paid stages only if budget is approved:
`python tests/tts_validation/run_paid_tests.py --stage A --delay 6.5`
