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
| Allowed CORS origin, valid WAV | 200 | correct — transcribed `uz-UZ` |
| Silent 16-bit WAV (2 s) | 200 | correct — empty transcript (local near-silence gate) |
| Low-energy tone | 200 | correct — empty transcript (local near-silence gate) |
| Random bytes as `audio/wav` | 400 | upstream 400 now surfaced as 400 |
| Repeated recognition (same clip ×3) | 200 | correct — stable transcript |
| Rate-limit burst (14 valid requests) | 429 after 12 | correct — window enforced |
| Unreachable host | 0 | correct client-side network error |

The two 502 rows above were re-measured after the error-mapping fix: garbage
audio now returns 400, and the silent-audio probe returns a clean empty 200
(from the local near-silence gate, not from Google).

## 5. Defects and findings

### 5.1 "502 regression" was a harness artifact, not a service outage

After the campaign finished (last success 06:35), `watch_recovery.py` began
recording HTTP 502 `{"detail":"Google Speech transcription failed"}` for a
sample that had just returned 200. That was reported as a live regression and
briefly investigated as an IAM/quota outage. The diagnosis was wrong, and the
evidence now shows why.

`watch_recovery.py` read the **raw** FLEURS clip
(`/tmp/chirp2_samples/en-US/<id>.wav`) and posted it unmodified. FLEURS clips
are 32-bit float PCM (`pcm_f32le`), and the endpoint sends
`autoDecodingConfig`. Google Chirp 2 cannot auto-decode 32-bit float WAV or
headerless PCM, so it returns **400 INVALID_ARGUMENT** — *"Audio data does not
appear to be in a supported encoding."* The old error mapping collapsed every
non-200/401/403/429 upstream status to 502, so a client payload fault looked
exactly like a provider outage. `reliability.py` had the same defect: its
`_silence()` helper sent a headerless `b"\x00" * n` blob, which Google also
rejects.

Confirmed against the live endpoint (`chirp2-test` revision):

| Audio sent | Google result | Old app status |
|---|---|---|
| 16-bit PCM WAV (as the campaign sends) | 200 | 200 |
| webm/opus, ogg/opus, mp3 | 200 | 200 |
| 32-bit float WAV (raw FLEURS) | 400 INVALID_ARGUMENT | **502 (misleading)** |
| headerless PCM (`b"\x00"*n`) | 400 INVALID_ARGUMENT | **502 (misleading)** |
| random bytes as `audio/wav` | 400 INVALID_ARGUMENT | 502 (misleading) |

The speech logs confirm it: 26 `code 3` (INVALID_ARGUMENT) entries in the
watch window, and the code-3 messages are all the encoding error above. The
`code 7` (PERMISSION_DENIED) entries in the same window came from a separate,
deliberately unauthenticated CI probe — not from the service, which had already
recognised the campaign audio 351/351.

The campaign results are therefore valid: `run_campaign.py` converts every
sample to 16 kHz mono 16-bit PCM via ffmpeg before sending
(`samples.py`), which is exactly the encoding Chirp 2 auto-decodes.

Fixes applied:

- `translator-api/main.py`: Google 400 now maps to **400** (client payload
  fault) instead of 502; 404 still maps to 502; and every non-200 upstream
  status is now logged with its body, so IAM vs. quota vs. transient errors are
  distinguishable from Cloud Run logs.
- `verification/chirp2_campaign/watch_recovery.py`: converts the clip to 16-bit
  PCM before polling.
- `verification/chirp2_campaign/reliability.py`: `_silence()` now emits a real
  RIFF/WAVE header so the silent-audio probe reaches recognition.
- `translator-api/test_chirp2_stt.py`: the mapping table expects 400→400 and
  404→502, plus a regression test for the undecodable-audio case.

### 5.2 Silent / garbage audio behaviour

With a valid 16-bit WAV, silent audio now returns 200 with an **empty**
transcript — see `reliability.json`. This is the result of a fix, not of
Google: an earlier revision of this document claimed Google returned an empty
transcript for silence, but fresh evidence against the zero-traffic candidate
revision (`candidate---avnivo-sensor-test-...`) **falsified** that claim.
Chirp 2 returned `"avnivo"` for both 2 s of digital silence and a low-energy
220 Hz tone on `uz-UZ`.

Root cause: the `uz-UZ` phrase boost (`CHIRP2_ADAPTATION` in
`translator-api/chirp2_languages.py`, `"Avnivo"` at boost 20.0) amplifies the
model's output on near-silent input, so the boosted phrase is emitted where
there is no speech.

Fix: `main.py` now measures the RMS of any 16-bit PCM WAV with the standard
library (`_pcm_rms` / `_is_near_silent`) and answers sub-threshold input with an
empty transcript locally, before the paid Google call. The threshold (1e-3,
≈ −63 dBFS) gates only inaudible input; the loudest real campaign samples are
orders of magnitude above it. Compressed containers (webm/opus, what the
browser records) cannot be inspected without ffmpeg and are forwarded
unchanged, so the gate is a safety net for the WAV path the API and campaign
use, not a replacement for the boost.

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
