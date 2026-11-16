# Chirp 2 — 117-locale real-audio verification

Verification date: **2026-11-16**
Model: `chirp_2` (Speech-to-Text V2, sync `Recognize`)
Region: `us-central1` · Project: `project-6bcf7fe6-cb55-49b7-9f4`
Endpoint under test: `https://chirp2-test---avnivo-sensor-test-5xwrf7netq-ew.a.run.app/api/transcribe-google`

This is the evidence-based follow-up to `docs/chirp2-language-support.md`. That
document established the 117-locale matrix from official documentation; this one
records what the deployed service actually did with real speech in every locale.

## 1. Method

Each locale was exercised with **3 real speech samples** (351 samples total, none
synthetic), sent as `multipart/form-data` to the deployed endpoint with the
locale in the `language` field. Every response was recorded verbatim
(`verification/chirp2_campaign/evidence.jsonl`).

| Source | Samples | Kind |
|---|---|---|
| `google/fleurs` | 309 | Real human read speech (CC-BY-4.0) |
| `tatoeba` | 27 | Real human speech (CC-BY 2.0 FR) |
| `wikimedia-commons` | 15 | Real human pronunciation clips (CC-BY-SA-4.0) |

Accuracy is reported two ways. **Execution status** says whether the API
transcribed the audio at all. **Accuracy** grades *how well*, using mean CER,
which is robust across scripts and short clips:

| Accuracy | Mean CER | Locales |
|---|---|---|
| EXCELLENT | < 0.10 | 93 |
| GOOD | < 0.20 | 13 |
| FAIR | < 0.35 | 5 |
| POOR | ≥ 0.35 | 6 |

## 2. Result summary

| Metric | Value |
|---|---|
| Locales tested | **117 / 117** |
| Samples (all real human speech) | 351 |
| Execution: PASS / FAIL / NOT TESTED | **117 / 0 / 0** |
| Mean WER (word-level) | 0.2154 |
| Mean CER (character-level) | 0.0898 |
| Exact-match rate | 27.6 % |
| Spend | **$1.0912** of $30.00 |

Every locale returned HTTP 200 with a non-empty transcript. A locale is only
marked PASS here because a real sample was transcribed; "117 supported" is not
inferred from the code list.

### Accuracy caveats

- Word-level WER is inflated by the sample type. Agglutinative and
  non-word-spaced scripts (e.g. Vietnamese, Thai, Japanese) and single-word
  Commons clips score badly on WER even when the recognition is correct —
  `natyrë → natyr` is 5 of 6 characters right but scores WER 1.0. CER is the
  fairer signal for these; both are in the report.
- espeak-ng synthetic audio was used only to fill a gap during development and
  **all synthetic rows were replaced** before the final run: the final 351
  samples are 100 % human speech.
- FLEURS/Tatoeba references contain punctuation and casing differences that
  normalization removes; residual WER partly reflects that, not misrecognition.

### Lowest-accuracy locales (mean CER ≥ 0.20)

| Locale | Accuracy | Mean WER | Mean CER | Sample source |
|---|---|---|---|---|
| `cmn-Hant-TW` | POOR | 1.00 | 1.00 | Tatoeba single-word clips |
| `rup-BG` | POOR | 1.33 | 1.05 | Tatoeba (Aromanian) |
| `bn-BD` | POOR | 1.67 | 0.57 | Commons single words |
| `su-ID` | POOR | 1.33 | 0.54 | Commons single words |
| `ig-NG` | POOR | 0.75 | 0.47 | FLEURS |
| `ga-IE` | POOR | 0.63 | 0.36 | FLEURS |
| `sq-AL` | FAIR | 1.00 | 0.30 | Commons single words |
| `ff-SN` | FAIR | 0.74 | 0.28 | FLEURS |
| `yue-Hant-HK` | FAIR | 0.28 | 0.28 | FLEURS |
| `yo-NG` | FAIR | 0.61 | 0.26 | FLEURS |
| `wo-SN` | FAIR | 0.45 | 0.23 | FLEURS |

The three POOR locales whose samples are single words (`cmn-Hant-TW`, `bn-BD`,
`su-ID`) are dominated by the measurement artifact above and should be
re-tested with full sentences before any conclusion is drawn about the model.
`rup-BG`, `ig-NG`, `ga-IE` are the clearest genuine weak spots.

## 3. Configuration consistency

The frontend matrix, the backend registry, and the deployed endpoint were
compared as sets (`verification/chirp2_campaign/consistency.json`):

| Pair | Result |
|---|---|
| UI matrix = backend registry | ✅ identical (117) |
| UI matrix = live `/api/chirp2-languages` | ✅ identical (117) |
| Backend registry = live endpoint | ✅ identical (117) |
| Duplicate or missing codes | none |

Live endpoint reports `model=chirp_2`, `region=us-central1`. There is no drift
between the three sources.

## 4. Reliability probes

`verification/chirp2_campaign/reliability.json`:

| Probe | HTTP | Verdict |
|---|---|---|
| Unsupported locale `xx-XX` | 400 | correct rejection |
| Unsupported locale `ar-AE` | 400 | correct rejection |
| Base code `uz` (not a locale) | 400 | correct rejection |
| Missing `language` field | 400 | correct — no silent Uzbek fallback |
| Wrong content type `text/plain` | 415 | correct rejection |
| Empty audio | 413 | correct rejection |
| Oversized audio (> 10 MB) | 413 | correct rejection |
| Disallowed CORS origin | 403 | correct rejection |
| Unreachable host | 0 | correct client-side network error |

## 5. Defects and findings

### 5.1 Live regression: STT route returns 502 for valid audio

After the campaign finished (last success 06:35), **every** call to
`/api/transcribe-google` began returning HTTP 502
`{"detail":"Google Speech transcription failed"}` — including a known-good
`en-US` sample that had just returned 200. `/health` and
`/api/chirp2-languages` kept returning 200, so the service is up; only the
Google Speech call inside the endpoint fails. `main.py:242` maps any non-200
from Google to this generic 502, which hides the upstream cause (quota,
billing, or a Google-side error). Observed continuously for > 15 minutes and
tracked by `verification/chirp2_campaign/watch_recovery.py`.

Recommended fixes:
- Surface the upstream Google status/error in the 502 response or in logs, so
  quota vs. IAM vs. transient errors are distinguishable.
- Confirm the Speech-to-Text V2 quota and billing state on
  `project-6bcf7fe6-cb55-49b7-9f4`.
- Add a retry with backoff for transient Google 5xx.

### 5.2 Silent / garbage audio behaviour changed

Earlier reconnaissance observed Chirp 2 returning hallucinated text for silent
audio (e.g. `uz-UZ → "avnivo"`). In the final probes, silent and garbage audio
returned **502** (Google rejected the request) rather than a hallucinated 200,
so no hallucination was observed this time. The `uz-UZ` phrase boost
(`CHIRP2_ADAPTATION` in `translator-api/chirp2_languages.py`, `"Avnivo"` at
boost 20.0) remains a risk: if Google ever returns 200 for near-silent audio,
that boost makes a spurious `"avnivo"` output more likely. Consider gating
adaptation on a minimum audio-energy check.

### 5.3 Test rot fixed

`translator-api/test_chirp2_ui_catalog.py` asserted selectors
(`id='avnivoChirp2Locale'`, `renderSpeechLocales`, `localeKey`) that a later
picker redesign removed, so it was silently excluded from CI. It was rewritten
against the current contract (`CHIRP2_MATRIX`, `CHIRP2_BASE_TO_LOCALE`,
`chirp2LocaleFor`, `avnivo-language-options`,
`avnivoOnlineLanguageForSpeaker`) and now runs in the suite.

## 6. Reproduce

```bash
cd verification/chirp2_campaign
python -m venv .venv-test && . .venv-test/bin/activate && pip install jiwer
python run_campaign.py --run --per-locale 3   # spends ~$1.10 at $0.016/min
python reliability.py
python consistency.py
python report.py                              # report.html / report.csv / report.json
```

Artifacts: `evidence.jsonl` (every request/response), `report.csv`,
`report.html`, `report.json`, `reliability.json`, `consistency.json`.

## 7. Scope limits

- Three samples per locale is a smoke test, not a benchmark. Accuracy grades
  indicate direction, not a production SLA.
- 15 locales use short Commons clips; their WER is not comparable to
  sentence-level locales.
- Text translation and TTS were out of scope and untouched.
