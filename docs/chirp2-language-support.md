# Google Chirp 2 language support (Speech-to-Text V2)

Verification date: **2026-11-15**
Model identifier: `chirp_2`
Project: `project-6bcf7fe6-cb55-49b7-9f4`
Configured region: `us-central1`

This document is the authoritative matrix for the AVNIVO Online STT subsystem.
It was compiled from Google Cloud's official documentation, not from memory or
third-party lists. The machine-readable copy lives in
`translator-api/chirp2_languages.py`; the frontend matrix in
`avnivo-v11-49-chirp2-multilingual.html` mirrors it.

## 1. Sources

| # | Topic | URL |
|---|-------|-----|
| 1 | Chirp 2 model page (methods, regions, streaming languages) | https://cloud.google.com/speech-to-text/v2/docs/chirp_2-model |
| 2 | V2 supported languages (full `chirp_2` matrix) | https://cloud.google.com/speech-to-text/v2/docs/speech-to-text-supported-languages |
| 3 | Quotas and limits (content/request limits) | https://cloud.google.com/speech-to-text/v2/docs/quotas |
| 4 | Audio encodings (WEBM_OPUS/OGG_OPUS sample rates) | https://cloud.google.com/speech-to-text/v2/docs/encoding |
| 5 | V2 `recognize` reference | https://cloud.google.com/speech-to-text/v2/docs/reference/rest/v2/projects.locations.recognizers/recognize |

## 2. Model, methods and regions

Chirp 2 is available **only** in the Speech-to-Text API V2 and supports three
recognition methods:

- `Speech.StreamingRecognize` — limited language support.
- `Speech.Recognize` — short audio, under 1 minute. This is what AVNIVO uses.
- `Speech.BatchRecognize` — long audio (1 min to 8 hours), the broadest
  language support.

AVNIVO uses the synchronous `Recognize` method with inline base64 content, which
is the correct method for short, interactive microphone clips.

Regions where the `chirp_2` model is offered:

| Region | Notes |
|--------|-------|
| `us-central1` | Configured for AVNIVO |
| `europe-west4` | Supported |
| `asia-southeast1` | Supported |

The full 117-locale matrix below is identical in all three regions.

## 3. Audio requirements

- The client records WebM/Opus (`audio/webm;codecs=opus`), falling back to
  `audio/webm` then `audio/mp4` when the browser cannot record Opus.
- `WEBM_OPUS` and `OGG_OPUS` accept sample rates of 8000, 12000, 16000, 24000 or
  48000 Hz. Browser MediaRecorder defaults (48000 Hz) are in range.
- `autoDecodingConfig` is sent so Google infers the encoding, avoiding a
  hard-coded mismatch with the recorded container.

## 4. Limits that shape the implementation

| Limit | Value | How AVNIVO respects it |
|-------|-------|------------------------|
| Sync `Recognize` audio size | 10 MB or 1 minute (whichever first) | Endpoint rejects uploads above 10 MB; the client stops recording well inside a minute |
| Phrase boost value | max 20 | Uzbek phrases use 20/15/10 |
| Sync `Recognize` requests / 60 s / region | 300 | Per-IP app throttle is 12/60 s |
| Chirp 2 diarization | Not supported | Not requested |
| Chirp 2 language detection | Not supported | The locale is always sent explicitly |
| Chirp 2 adaptation | Supported (word/phrase biasing only) | Used only for `uz-UZ` |

## 5. Supported features

Automatic punctuation, automatic capitalization, speech adaptation (biasing),
word timings, profanity filter, language-agnostic transcription, language-specific
translation, forced normalization, word-level confidence and denoiser/SNR
filtering. Diarization and language detection are **not** supported.

## 6. Translation vs. recognition coverage

Chirp 2's speech-translation pairs are a **subset** of its transcription
languages and are not symmetrical (for example `ru-RU -> en-US` exists, but the
reverse translation is not listed). AVNIVO performs text translation through the
separate Cloud Translation LLM endpoint, so translation availability and
recognition availability are independent. A language may support AVNIVO text
translation without Chirp 2 recognition; this document covers recognition only.

## 7. Complete `chirp_2` locale matrix (117 locales)

| `af-ZA` | Afrikaans (South Africa) |
| `am-ET` | Amharic (Ethiopia) |
| `ar-EG` | Arabic (Egypt) |
| `as-IN` | Assamese (India) |
| `ast-ES` | Asturian (Spain) |
| `az-AZ` | Azerbaijani (Azerbaijan) |
| `be-BY` | Belarusian (Belarus) |
| `bg-BG` | Bulgarian (Bulgaria) |
| `bn-BD` | Bengali (Bangladesh) |
| `bn-IN` | Bengali (India) |
| `bs-BA` | Bosnian (Bosnia and Herzegovina) |
| `ca-ES` | Catalan (Spain) |
| `ceb-PH` | Cebuano (Philippines) |
| `ckb-IQ` | Central Kurdish (Iraq) |
| `cmn-Hans-CN` | Chinese (Simplified, China) |
| `cmn-Hant-TW` | Chinese, Mandarin (Traditional, Taiwan) |
| `cs-CZ` | Czech (Czech Republic) |
| `cy-GB` | Welsh (United Kingdom) |
| `da-DK` | Danish (Denmark) |
| `de-DE` | German (Germany) |
| `el-GR` | Greek (Greece) |
| `en-AU` | English (Australia) |
| `en-GB` | English (United Kingdom) |
| `en-IN` | English (India) |
| `en-US` | English (United States) |
| `es-419` | Spanish (Latin American) |
| `es-ES` | Spanish (Spain) |
| `es-US` | Spanish (United States) |
| `et-EE` | Estonian (Estonia) |
| `eu-ES` | Basque (Spain) |
| `fa-IR` | Persian (Iran) |
| `ff-SN` | Fulah (Senegal) |
| `fi-FI` | Finnish (Finland) |
| `fil-PH` | Filipino (Philippines) |
| `fr-CA` | French (Canada) |
| `fr-FR` | French (France) |
| `ga-IE` | Irish (Ireland) |
| `gl-ES` | Galician (Spain) |
| `gu-IN` | Gujarati (India) |
| `ha-NG` | Hausa (Nigeria) |
| `hi-IN` | Hindi (India) |
| `hr-HR` | Croatian (Croatia) |
| `hu-HU` | Hungarian (Hungary) |
| `hy-AM` | Armenian (Armenia) |
| `id-ID` | Indonesian (Indonesia) |
| `ig-NG` | Igbo (Nigeria) |
| `is-IS` | Icelandic (Iceland) |
| `it-IT` | Italian (Italy) |
| `iw-IL` | Hebrew (Israel) |
| `ja-JP` | Japanese (Japan) |
| `jv-ID` | Javanese (Indonesia) |
| `ka-GE` | Georgian (Georgia) |
| `kam-KE` | Kamba (Kenya) |
| `kea-CV` | Kabuverdianu (Cape Verde) |
| `kk-KZ` | Kazakh (Kazakhstan) |
| `km-KH` | Khmer (Cambodia) |
| `kn-IN` | Kannada (India) |
| `ko-KR` | Korean (South Korea) |
| `ky-KG` | Kyrgyz (Cyrillic) |
| `lb-LU` | Luxembourgish (Luxembourg) |
| `lg-UG` | Ganda (Uganda) |
| `ln-CD` | Lingala (Congo-Kinshasa) |
| `lo-LA` | Lao (Laos) |
| `lt-LT` | Lithuanian (Lithuania) |
| `luo-KE` | Luo (Kenya) |
| `lv-LV` | Latvian (Latvia) |
| `mi-NZ` | Maori (New Zealand) |
| `mk-MK` | Macedonian (North Macedonia) |
| `ml-IN` | Malayalam (India) |
| `mn-MN` | Mongolian (Mongolia) |
| `mr-IN` | Marathi (India) |
| `ms-MY` | Malay (Malaysia) |
| `mt-MT` | Maltese (Malta) |
| `my-MM` | Burmese (Myanmar) |
| `ne-NP` | Nepali (Nepal) |
| `nl-NL` | Dutch (Netherlands) |
| `no-NO` | Norwegian Bokmål (Norway) |
| `nso-ZA` | Sepedi (South Africa) |
| `ny-MW` | Nyanja (Malawi) |
| `oc-FR` | Occitan (France) |
| `om-ET` | Oromo (Ethiopia) |
| `or-IN` | Oriya (India) |
| `pa-Guru-IN` | Punjabi (Gurmukhi India) |
| `pl-PL` | Polish (Poland) |
| `ps-AF` | Pashto |
| `pt-BR` | Portuguese (Brazil) |
| `pt-PT` | Portuguese (Portugal) |
| `ro-RO` | Romanian (Romania) |
| `ru-RU` | Russian (Russia) |
| `rup-BG` | Aromanian (Bulgaria) |
| `sd-IN` | Sindhi (India) |
| `si-LK` | Sinhala (Sri Lanka) |
| `sk-SK` | Slovak (Slovakia) |
| `sl-SI` | Slovenian (Slovenia) |
| `sn-ZW` | Shona (Zimbabwe) |
| `so-SO` | Somali |
| `sq-AL` | Albanian (Albania) |
| `sr-RS` | Serbian (Serbia) |
| `su-ID` | Sundanese (Indonesia) |
| `sv-SE` | Swedish (Sweden) |
| `sw` | Swahili |
| `sw-KE` | Swahili (Kenya) |
| `ta-IN` | Tamil (India) |
| `te-IN` | Telugu (India) |
| `tg-TJ` | Tajik (Tajikistan) |
| `th-TH` | Thai (Thailand) |
| `tr-TR` | Turkish (Turkey) |
| `uk-UA` | Ukrainian (Ukraine) |
| `umb-AO` | Umbundu (Angola) |
| `ur-PK` | Urdu (Pakistan) |
| `uz-UZ` | Uzbek (Uzbekistan) |
| `vi-VN` | Vietnamese (Vietnam) |
| `wo-SN` | Wolof (Senegal) |
| `xh-ZA` | Xhosa (South Africa) |
| `yo-NG` | Yoruba (Nigeria) |
| `yue-Hant-HK` | Chinese, Cantonese (Traditional Hong Kong) |
| `zu-ZA` | Zulu (South Africa) |

## 8. Excluded languages

53 language codes appear in the Speech-to-Text V2 matrix for other models but
are **not** offered by `chirp_2` (for example `ar-AE`, `ar-DZ`, `ar-IL` and the
telephony-only `telephony`/`telephony_short` codes). They are excluded because
Google does not list them for the `chirp_2` model. Chirp 3 support never implies
Chirp 2 support, so nothing was inferred from the Chirp 3 list.

Locales that AVNIVO supports for **text translation** but that are outside the
Chirp 2 matrix (if added to the translation selector later) must be reported as
"no Chirp 2 recognition" rather than silently routed to `uz-UZ`.

## 9. Frontend mapping

The translation panels use base codes (`uz`, `ru`, `en`, `tr`). The resolver
maps them to their verified Chirp 2 locales:

| Base | Chirp 2 locale |
|------|----------------|
| `uz` | `uz-UZ` |
| `ru` | `ru-RU` |
| `en` | `en-US` |
| `tr` | `tr-TR` |

Any other base code resolves to `null`, and the UI reports that Chirp 2 is not
configured for that language. There is no silent fallback to Uzbek.

## 10. Verification status

- Research: complete (117 locales, three regions, methods, limits, encodings).
- Implementation: the backend registry and endpoint accept all 117 locales; the
  frontend resolver covers the four currently selectable languages.
- **Matrix cross-check (independent):** the 117 codes and English names in
  `translator-api/chirp2_languages.py` were parsed out of the official supported-
  languages page (rows whose model column is `chirp` or `chirp_2`) and compared:
  **exact set match, 0 name mismatches**. Script: `translator-api/test_chirp2_stt.py`
  asserts the same invariants against the embedded frontend matrix.
- **Real Google recognition (verified):** four locales were transcribed by the
  deployed Chirp 2 service on Cloud Run revision `avnivo-sensor-test-00012-qap`
  (candidate tag, 0% production traffic), region `us-central1`, model `chirp_2`:

  | Locale | Sample | HTTP | Transcript | Latency |
  |---|---|---|---|---|
  | `uz-UZ` | Wikimedia Commons Uzbek pronunciation (CC-BY-SA) | 200 | `bakteriya keyingi` | 2.28 s |
  | `ru-RU` | espeak-ng synthetic Russian | 200 | `Здравствуйте это проверка системы распознавания речи` | 2.07 s |
  | `en-US` | espeak-ng synthetic English | 200 | `hello this is a test of the speech recognition system` | 1.47 s |
  | `tr-TR` | espeak-ng synthetic Turkish | 200 | `merhaba bu hafta sonu sınav sistemi ile ilgili test` | 1.52 s |

  Recorded evidence: `docs/chirp2-live-verification.json`. Harness:
  `translator-api/test_chirp2_live.py` (skipped unless explicitly enabled).

- **Error handling (verified against the deployment):** unsupported codes
  (`xx-XX`, `ar-AE`) → HTTP 400; missing `language` → HTTP 400 (no Uzbek
  fallback); unsupported content type → HTTP 415; empty audio → HTTP 413.

- **Not yet real-verified:** the remaining 113 registered locales. Their codes
  are registered and validated, and request construction is covered by offline
  tests, but they have not been exercised against the live API (no speech
  samples and no explicit spend authorization). They must not be reported as
  verified until tested.
- **Production deployment:** the live production revision still runs the older
  pre-registry build (`/api/chirp2-languages` → 404). Promoting the candidate
  revision to 100% traffic is a deliberate, separate step requiring approval.
