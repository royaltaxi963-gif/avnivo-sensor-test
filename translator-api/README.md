# AVNIVO Translator API

Isolated Groq Whisper Large v3 Turbo proxy. Python 3.11+.

Build: `pip install -r translator-api/requirements.txt`
Start: `uvicorn main:app --app-dir translator-api --host 0.0.0.0 --port $PORT`
Required environment variable: `GROQ_API_KEY` (never store in GitHub or HTML).
Health: `GET /health`; transcription: `POST /api/transcribe` with multipart `audio` and optional `language`.

Security note: Origin restriction and in-memory per-IP throttling are only basic protections. They do not authenticate clients and reset on restart. Do not configure a billable Groq key for public use without server-side authentication and a spending/rate limit at the provider. Free-tier testing only, with active monitoring.
