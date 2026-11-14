"""AVNIVO Translator — Groq Whisper Large v3 proxy."""
import os
import time
from collections import defaultdict, deque
from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.middleware.cors import CORSMiddleware
import httpx
from groq import AsyncGroq, RateLimitError, APIError

MODEL = "whisper-large-v3"
GROQ_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
ORIGIN = "https://royaltaxi963-gif.github.io"
MAX_AUDIO = 10 * 1024 * 1024
RATE_LIMIT = 12
WINDOW = 60
app = FastAPI(title="AVNIVO Translator API", docs_url=None, redoc_url=None)
app.add_middleware(CORSMiddleware, allow_origins=[ORIGIN], allow_methods=["POST"], allow_headers=["Content-Type"], allow_credentials=False)
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
    if origin != ORIGIN:
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
    if audio.content_type not in {"audio/webm", "audio/ogg", "audio/mp4", "audio/mpeg", "audio/wav", "audio/x-wav"}:
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
