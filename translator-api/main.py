"""AVNIVO Translator — Groq Whisper Large v3 proxy."""
import os
import time
import base64
import asyncio
from collections import defaultdict, deque
from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.middleware.cors import CORSMiddleware
import httpx
import google.auth
from google.auth.transport.requests import Request as GoogleAuthRequest
from google.auth.exceptions import DefaultCredentialsError, RefreshError
from groq import AsyncGroq, RateLimitError, APIError

MODEL = "whisper-large-v3"
GROQ_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
ALLOWED_ORIGINS = {"https://royaltaxi963-gif.github.io", "http://localhost:3000", "http://localhost:8080", "null"}
GOOGLE_PROJECT = "project-6bcf7fe6-cb55-49b7-9f4"
GOOGLE_REGION = "us-central1"
GOOGLE_MODEL = "chirp_2"
MAX_AUDIO = 10 * 1024 * 1024
RATE_LIMIT = 12
WINDOW = 60
app = FastAPI(title="AVNIVO Translator API", docs_url=None, redoc_url=None)
app.add_middleware(CORSMiddleware, allow_origins=sorted(ALLOWED_ORIGINS), allow_methods=["POST"], allow_headers=["Content-Type"], allow_credentials=False)
recent = defaultdict(deque)

@app.get("/health")
async def health():
    return {"status": "ok", "model": MODEL, "configured": bool(os.getenv("GROQ_API_KEY"))}

@app.post("/api/transcribe")
@app.post("/api/transcribe-groq-sdk-test")
@app.post("/api/transcribe-groq-test")
async def transcribe(request: Request, audio: UploadFile = File(...), language: str | None = Form(None)):
    # Origin validation is a browser guard, not user authentication.
    origin = request.headers.get("origin")
    if origin is not None and origin not in ALLOWED_ORIGINS:
        raise HTTPException(403, "Origin not allowed")
    key = os.getenv("GROQ_API_KEY")
    if not key:
        raise HTTPException(503, "Speech recognition is not configured")
    ip = request.headers.get("x-forwarded-for", request.client.host if request.client else "unknown").split(",")[0].strip()
    now = time.monotonic()
    hits = recent[ip]
    while hits and now - hits[0] > WINDOW:
        hits.popleft()
    if len(hits) >= RATE_LIMIT:
        raise HTTPException(429, "Too many requests")
    hits.append(now)
    if audio.content_type not in {"audio/webm", "audio/ogg", "audio/mp4", "audio/mpeg", "audio/wav", "audio/x-wav", "audio/flac", "audio/x-flac"}:
        raise HTTPException(415, "Unsupported audio format")
    data = await audio.read(MAX_AUDIO + 1)
    if not data or len(data) > MAX_AUDIO:
        raise HTTPException(413, "Invalid audio size")
    is_sdk_test = request.url.path == "/api/transcribe-groq-sdk-test"
    is_test = request.url.path == "/api/transcribe-groq-test"
    model = "whisper-large-v3-turbo" if is_test else MODEL
    fields = {"model": model, "response_format": "json", "temperature": "0"}
    if is_test and language == "uz":
        fields["prompt"] = "Salom, yaxshimisiz, qayerdasiz, nima qilyapsiz, tuzukmisiz."
    if language:
        if language not in {"uz", "ru", "en", "tr"}:
            raise HTTPException(400, "Unsupported language")
        fields["language"] = language
    if is_sdk_test:
        # Isolated user-requested SDK experiment; other routes retain httpx.
        if language not in {"uz", "ru", "en", "tr"}:
            raise HTTPException(400, "Unsupported language")
        prompt = "Salom, yaxshimisiz, qayerdasiz, nima qilyapsiz, tuzukmisiz." if language == "uz" else ""
        try:
            sdk = AsyncGroq(api_key=key, max_retries=3, timeout=75)
            result = await sdk.audio.transcriptions.create(
                file=(audio.filename or "speech.webm", data),
                model="whisper-large-v3-turbo",
                language=language,
                prompt=prompt,
                response_format="text",
            )
            return {"text": result if isinstance(result, str) else str(result), "model": "whisper-large-v3-turbo"}
        except RateLimitError:
            raise HTTPException(429, "Speech provider rate limit reached")
        except APIError:
            raise HTTPException(502, "Speech provider error")
        except Exception:
            raise HTTPException(502, "Speech provider unreachable")
    try:
        async with httpx.AsyncClient(timeout=75) as client:
            response = await client.post(
                GROQ_URL, headers={"Authorization": f"Bearer {key}"},
                data=fields, files={"file": (audio.filename or "speech.webm", data, audio.content_type)}
            )
    except httpx.RequestError:
        raise HTTPException(502, "Speech provider unreachable")
    if response.status_code != 200:
        if response.status_code == 429:
            raise HTTPException(429, "Speech provider rate limit reached")
        raise HTTPException(502, "Speech provider error")
    result = response.json()
    return {"text": result.get("text", ""), "model": model}


@app.post("/api/transcribe-google")
async def transcribe_google(request: Request, audio: UploadFile = File(...), language: str = Form("uz-UZ")):
    """Independent Google Speech-to-Text v2 endpoint; existing Groq routes are unchanged."""
    origin = request.headers.get("origin")
    if origin is not None and origin not in ALLOWED_ORIGINS:
        raise HTTPException(403, "Origin not allowed")
    if language != "uz-UZ":
        raise HTTPException(400, "Only uz-UZ is configured for this endpoint")
    if audio.content_type not in {"audio/webm", "audio/ogg", "audio/mp4", "audio/mpeg", "audio/wav", "audio/x-wav", "audio/flac", "audio/x-flac"}:
        raise HTTPException(415, "Unsupported audio format")
    data = await audio.read(MAX_AUDIO + 1)
    if not data or len(data) > MAX_AUDIO:
        raise HTTPException(413, "Invalid audio size")
    ip = request.headers.get("x-forwarded-for", request.client.host if request.client else "unknown").split(",")[0].strip()
    now = time.monotonic()
    hits = recent[ip]
    while hits and now - hits[0] > WINDOW:
        hits.popleft()
    if len(hits) >= RATE_LIMIT:
        raise HTTPException(429, "Too many requests")
    hits.append(now)

    def get_token():
        credentials, _ = google.auth.default(scopes=["https://www.googleapis.com/auth/cloud-platform"])
        if not credentials.valid:
            credentials.refresh(GoogleAuthRequest())
        return credentials.token

    try:
        token = await asyncio.to_thread(get_token)
    except (DefaultCredentialsError, RefreshError):
        raise HTTPException(503, "Google Cloud credentials are not configured")
    url = (
        f"https://{GOOGLE_REGION}-speech.googleapis.com/v2/projects/{GOOGLE_PROJECT}"
        f"/locations/{GOOGLE_REGION}/recognizers/_:recognize"
    )
    payload = {
        "config": {
            "autoDecodingConfig": {},
            "languageCodes": [language],
            "model": GOOGLE_MODEL,
            "adaptation": {
                "phraseSets": [{
                    "inlinePhraseSet": {
                        "phrases": [
                            {"value": "Avnivo", "boost": 20.0},
                            {"value": "ovoz tanish", "boost": 15.0},
                            {"value": "sinovi", "boost": 10.0}
                        ]
                    }
                }]
            }
        },
        "content": base64.b64encode(data).decode("ascii")
    }
    try:
        async with httpx.AsyncClient(timeout=90) as client:
            response = await client.post(
                url,
                headers={"Authorization": f"Bearer {token}"},
                json=payload,
            )
    except httpx.RequestError:
        raise HTTPException(502, "Google Speech service unreachable")
    if response.status_code == 429:
        raise HTTPException(429, "Google Speech quota exceeded")
    if response.status_code in (401, 403):
        raise HTTPException(503, "Google Speech access denied; check IAM and API")
    if response.status_code != 200:
        raise HTTPException(502, "Google Speech transcription failed")
    results = response.json().get("results", [])
    text = " ".join(
        item["alternatives"][0].get("transcript", "").strip()
        for item in results if item.get("alternatives")
    ).strip()
    return {"text": text, "model": GOOGLE_MODEL, "language": language}
