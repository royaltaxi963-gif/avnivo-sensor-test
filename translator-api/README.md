# AVNIVO Translator API

Isolated Groq Whisper Large v3 Turbo proxy plus Google Chirp 2 speech
recognition. Python 3.11+.

Build: `pip install -r translator-api/requirements.txt`
Start: `uvicorn main:app --app-dir translator-api --host 0.0.0.0 --port $PORT`
Required environment variable: `GROQ_API_KEY` (never store in GitHub or HTML).
Health: `GET /health`; transcription: `POST /api/transcribe` with multipart `audio` and optional `language`.

## Google Chirp 2 (Speech-to-Text V2)

- `POST /api/transcribe-google` — multipart `audio` plus `language` (BCP-47).
  Accepts any locale in the centralized registry; an unknown or missing code is
  rejected with HTTP 400 and never silently substituted.
- `GET /api/chirp2-languages` — returns the registry (model, region, locales) so
  the frontend cannot drift from the backend.
- `translator-api/chirp2_languages.py` — the single source of truth for the 117
  official `chirp_2` locales, the configured region, the protected Uzbek phrase
  adaptation and the sync audio limits. See `docs/chirp2-language-support.md`.
- Credentials: Application Default Credentials with
  `https://www.googleapis.com/auth/cloud-platform`, calling
  `{region}-speech.googleapis.com`. No key is stored in code or the browser.

Tests: `python -m pytest -q translator-api/test_translation_llm.py translator-api/test_chirp2_stt.py`
(offline, no paid calls). Real recognition verification is opt-in:
`AVNIVO_CHIRP2_LIVE=1 AVNIVO_CHIRP2_SAMPLE=/path/speech.wav python -m pytest -q translator-api/test_chirp2_live.py -s`.

Security note: Origin restriction and in-memory per-IP throttling are only basic protections. They do not authenticate clients and reset on restart. Do not configure a billable Groq key for public use without server-side authentication and a spending/rate limit at the provider. Free-tier testing only, with active monitoring.
