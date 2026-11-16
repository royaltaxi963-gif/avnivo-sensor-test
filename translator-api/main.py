"""AVNIVO Translator — Google Cloud Speech-to-Text V2 (Chirp 2) proxy.

Online speech recognition runs on Google Chirp 2 (see ``chirp2_languages``).
The legacy ``/api/transcribe`` routes below remain only for backward
compatibility and are unused by the AVNIVO Online frontend.
"""
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

from chirp2_languages import (
    CHIRP2_LOCALES,
    CHIRP2_REGIONS,
    CHIRP2_SYNC_MAX_BYTES,
    GOOGLE_MODEL,
    GOOGLE_REGION,
    adaptation_phrases,
    is_supported_locale,
)

MODEL = "whisper-large-v3"
GROQ_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
ALLOWED_ORIGINS = {"https://royaltaxi963-gif.github.io", "https://raw.githack.com", "https://rawcdn.githack.com", "http://localhost:3000", "http://localhost:8080", "null"}
GOOGLE_PROJECT = "project-6bcf7fe6-cb55-49b7-9f4"
MAX_AUDIO = CHIRP2_SYNC_MAX_BYTES
RATE_LIMIT = 12
WINDOW = 60
app = FastAPI(title="AVNIVO Translator API", docs_url=None, redoc_url=None)
app.add_middleware(CORSMiddleware, allow_origins=sorted(ALLOWED_ORIGINS), allow_methods=["POST"], allow_headers=["Content-Type"], allow_credentials=False)
recent = defaultdict(deque)

@app.get("/health")
async def health():
    # Application Default Credentials are implicit on Cloud Run (K_SERVICE) and
    # explicit locally, so this reflects real Chirp 2 recognizer availability.
    configured = bool(os.getenv("GOOGLE_APPLICATION_CREDENTIALS") or os.getenv("K_SERVICE"))
    return {
        "status": "ok",
        "model": GOOGLE_MODEL,
        "region": GOOGLE_REGION,
        "languages": len(CHIRP2_LOCALES),
        "configured": configured,
    }

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


ALLOWED_AUDIO_TYPES = frozenset((
    "audio/webm", "audio/ogg", "audio/mp4", "audio/mpeg",
    "audio/wav", "audio/x-wav", "audio/flac", "audio/x-flac",
))


def _chirp2_config(locale: str) -> dict:
    """Build the Chirp 2 RecognitionConfig for one registry locale."""
    config: dict = {
        "autoDecodingConfig": {},
        "languageCodes": [locale],
        "model": GOOGLE_MODEL,
    }
    phrases = adaptation_phrases(locale)
    if phrases:
        config["adaptation"] = {"phraseSets": [{"inlinePhraseSet": {"phrases": phrases}}]}
    return config


def _join_transcripts(payload: object) -> str:
    """Flatten a Speech V2 Recognize response into a single transcript string."""
    if not isinstance(payload, dict):
        return ""
    results = payload.get("results")
    if not isinstance(results, list):
        return ""
    parts = []
    for item in results:
        if not isinstance(item, dict):
            continue
        alternatives = item.get("alternatives")
        if not isinstance(alternatives, list) or not alternatives:
            continue
        first = alternatives[0]
        if isinstance(first, dict):
            transcript = first.get("transcript")
            if isinstance(transcript, str) and transcript.strip():
                parts.append(transcript.strip())
    return " ".join(parts).strip()


def _google_token() -> str:
    credentials, _ = google.auth.default(scopes=["https://www.googleapis.com/auth/cloud-platform"])
    if not credentials.valid:
        credentials.refresh(GoogleAuthRequest())
    return credentials.token


def _rate_limit(ip: str) -> None:
    now = time.monotonic()
    hits = recent[ip]
    while hits and now - hits[0] > WINDOW:
        hits.popleft()
    if len(hits) >= RATE_LIMIT:
        raise HTTPException(429, "Too many requests")
    hits.append(now)


@app.get("/api/chirp2-languages")
async def chirp2_languages():
    """Expose the registry so the frontend can never drift from the backend."""
    return {
        "model": GOOGLE_MODEL,
        "region": GOOGLE_REGION,
        "regions": sorted(CHIRP2_REGIONS),
        "count": len(CHIRP2_LOCALES),
        "locales": sorted(CHIRP2_LOCALES),
    }


@app.post("/api/transcribe-google")
async def transcribe_google(request: Request, audio: UploadFile = File(...), language: str | None = Form(None)):
    """Google Speech-to-Text V2 (Chirp 2) endpoint; existing Groq routes are unchanged.

    One reusable recognition pipeline serves every locale in the centralized
    registry. An unsupported or missing code is rejected outright; it never
    silently substitutes another language such as uz-UZ.
    """
    origin = request.headers.get("origin")
    if origin is not None and origin not in ALLOWED_ORIGINS:
        raise HTTPException(403, "Origin not allowed")
    # A missing field must not default to a specific language: reject it.
    if not is_supported_locale(language):
        raise HTTPException(400, "Unsupported Chirp 2 language")
    if audio.content_type not in ALLOWED_AUDIO_TYPES:
        raise HTTPException(415, "Unsupported audio format")
    data = await audio.read(MAX_AUDIO + 1)
    if not data or len(data) > MAX_AUDIO:
        raise HTTPException(413, "Invalid audio size")
    ip = request.headers.get("x-forwarded-for", request.client.host if request.client else "unknown").split(",")[0].strip()
    _rate_limit(ip)
    try:
        token = await asyncio.to_thread(_google_token)
    except (DefaultCredentialsError, RefreshError):
        raise HTTPException(503, "Google Cloud credentials are not configured")
    url = (
        f"https://{GOOGLE_REGION}-speech.googleapis.com/v2/projects/{GOOGLE_PROJECT}"
        f"/locations/{GOOGLE_REGION}/recognizers/_:recognize"
    )
    payload = {
        "config": _chirp2_config(language),
        "content": base64.b64encode(data).decode("ascii"),
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
    return {"text": _join_transcripts(response.json()), "model": GOOGLE_MODEL, "language": language}


@app.post("/api/translate")
async def translate_public(request: Request):
    """Browser translation route using the Cloud Run service identity."""
    origin = request.headers.get("origin")
    if origin is not None and origin not in ALLOWED_ORIGINS:
        raise HTTPException(403, "Origin not allowed")
    raw = await request.body()
    if len(raw) > 4096:
        raise HTTPException(413, "Request too large")
    try:
        body = __import__("json").loads(raw)
    except (ValueError, UnicodeDecodeError):
        raise HTTPException(400, "Invalid JSON")
    if not isinstance(body, dict):
        raise HTTPException(400, "Invalid request")
    text = body.get("text")
    source = body.get("source")
    target = body.get("target")
    if not isinstance(text, str) or not text.strip() or len(text) > 500:
        raise HTTPException(400, "Text must contain 1-500 characters")
    if source not in {"uz", "ru", "en", "tr"} or target not in {"uz", "ru", "en", "tr"} or source == target:
        raise HTTPException(400, "Unsupported language pair")
    project = os.getenv("GOOGLE_CLOUD_PROJECT", GOOGLE_PROJECT)
    region = os.getenv("AVNIVO_TRANSLATION_REGION", "us-central1")
    if region not in {"us-central1", "global"}:
        raise HTTPException(503, "Invalid translation region")
    def get_token():
        credentials, _ = google.auth.default(scopes=["https://www.googleapis.com/auth/cloud-platform"])
        if not credentials.valid:
            credentials.refresh(GoogleAuthRequest())
        return credentials.token
    try:
        token = await asyncio.to_thread(get_token)
    except (DefaultCredentialsError, RefreshError):
        raise HTTPException(503, "Translation credentials unavailable")
    url = f"https://translation.googleapis.com/v3/projects/{project}/locations/{region}:translateText"
    payload = {
        "contents": [text],
        "mimeType": "text/plain",
        "sourceLanguageCode": source,
        "targetLanguageCode": target,
        "model": f"projects/{project}/locations/{region}/models/general/translation-llm",
    }
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            response = await client.post(url, headers={"Authorization": f"Bearer {token}"}, json=payload)
    except httpx.RequestError:
        raise HTTPException(502, "Translation provider unreachable")
    if response.status_code == 429:
        raise HTTPException(429, "Translation quota exceeded")
    if response.status_code in (401, 403):
        raise HTTPException(503, "Translation access denied")
    if response.status_code != 200:
        raise HTTPException(502, "Translation provider error")
    try:
        translated = response.json()["translations"][0]["translatedText"]
        if not isinstance(translated, str):
            raise ValueError()
    except (KeyError, IndexError, TypeError, ValueError):
        raise HTTPException(502, "Unexpected translation response")
    return {"text": translated, "model": "general/translation-llm", "source": source, "target": target}


@app.post("/api/translate-llm-test")
async def translate_llm_test(request: Request):
    """Opt-in, authenticated test route. Never silently falls back to another model."""
    import secrets
    if os.getenv("AVNIVO_TRANSLATION_TEST_ENABLED") != "true":
        raise HTTPException(503, "Translation test is disabled")
    expected = os.getenv("AVNIVO_TRANSLATION_TEST_TOKEN", "")
    supplied = request.headers.get("x-avnivo-test-token", "")
    if not expected or not secrets.compare_digest(supplied, expected):
        raise HTTPException(401, "Unauthorized")
    origin = request.headers.get("origin")
    if origin is not None and origin not in ALLOWED_ORIGINS:
        raise HTTPException(403, "Origin not allowed")
    raw_length = request.headers.get("content-length", "0")
    try:
        content_length = int(raw_length)
    except ValueError:
        raise HTTPException(400, "Invalid Content-Length")
    if content_length > 4096:
        raise HTTPException(413, "Request too large")
    raw_body = await request.body()
    if len(raw_body) > 4096:
        raise HTTPException(413, "Request too large")
    try:
        body = await request.json()
    except ValueError:
        raise HTTPException(400, "Invalid JSON")
    if not isinstance(body, dict):
        raise HTTPException(400, "Invalid request")
    text = body.get("text")
    source = body.get("source", "uz")
    target = body.get("target", "ru")
    if not isinstance(text, str) or not text.strip() or len(text) > 500:
        raise HTTPException(400, "Text must contain 1-500 characters")
    if source not in {"uz", "ru", "en"} or target not in {"uz", "ru", "en"} or source == target:
        raise HTTPException(400, "Unsupported language pair")
    project = os.getenv("GOOGLE_CLOUD_PROJECT", GOOGLE_PROJECT)
    region = os.getenv("AVNIVO_TRANSLATION_REGION", "us-central1")
    if region not in {"us-central1", "global"}:
        raise HTTPException(503, "Invalid translation region")

    def get_translation_token():
        credentials, _ = google.auth.default(scopes=["https://www.googleapis.com/auth/cloud-platform"])
        if not credentials.valid:
            credentials.refresh(GoogleAuthRequest())
        return credentials.token

    try:
        token = await asyncio.to_thread(get_translation_token)
    except (DefaultCredentialsError, RefreshError):
        raise HTTPException(503, "Translation credentials unavailable")
    model = f"projects/{project}/locations/{region}/models/general/translation-llm"
    url = f"https://translation.googleapis.com/v3/projects/{project}/locations/{region}:translateText"
    payload = {
        "contents": [text],
        "mimeType": "text/plain",
        "sourceLanguageCode": source,
        "targetLanguageCode": target,
        "model": model,
    }
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            response = await client.post(url, headers={"Authorization": f"Bearer {token}"}, json=payload)
    except httpx.RequestError:
        raise HTTPException(502, "Translation provider unreachable")
    if response.status_code == 429:
        raise HTTPException(429, "Translation quota exceeded")
    if response.status_code in (401, 403):
        raise HTTPException(503, "Translation access denied")
    if response.status_code != 200:
        raise HTTPException(502, "Translation provider error")
    try:
        translations = response.json()["translations"]
        translated = translations[0]["translatedText"]
        if not isinstance(translated, str):
            raise ValueError("Invalid translated text")
    except (KeyError, IndexError, TypeError, ValueError):
        raise HTTPException(502, "Unexpected translation response")
    return {"text": translated, "model": "general/translation-llm", "source": source, "target": target}


# Emotion-aware TTS is isolated from translation and speech recognition.
_TTS_STYLES = {
    "concerned": "Speak in a slightly stern, concerned tone with restrained frustration; remain natural, never shout.",
    "happy": "Speak with warm happiness and a lively, smiling intonation.",
    "sad": "Speak softly and compassionately, with subdued sadness.",
    "excited": "Speak with enthusiastic, animated energy and natural emphasis.",
    "calm": "Speak clearly, naturally and conversationally with a calm neutral tone.",
}

def _detect_tts_style(text: str) -> str:
    lowered = text.casefold()
    if any(x in lowered for x in ("qayerda yurd", "izlayapman", "izlayabman", "nega kelmad", "где ты был", "я тебя ищу", "where have you been")):
        return "concerned"
    if any(x in lowered for x in ("tabrik", "zo'r yangilik", "ура", "поздравля", "congratulations", "wonderful news")):
        return "excited"
    if any(x in lowered for x in ("xursand", "rahmat", "спасибо", "радост", "thank you", "so happy")):
        return "happy"
    if any(x in lowered for x in ("afsus", "hamdard", "сожалею", "груст", "sorry for your loss")):
        return "sad"
    return "calm"


@app.post("/api/tts")
async def synthesize_emotional_speech(request: Request):
    """Return Gemini-generated WAV audio; auto-select a conservative emotional style."""
    origin = request.headers.get("origin")
    if origin is not None and origin not in ALLOWED_ORIGINS:
        raise HTTPException(403, "Origin not allowed")
    raw = await request.body()
    if len(raw) > 2048:
        raise HTTPException(413, "Request too large")
    try:
        import json
        body = json.loads(raw)
    except (ValueError, UnicodeDecodeError):
        raise HTTPException(400, "Invalid JSON")
    if not isinstance(body, dict):
        raise HTTPException(400, "Invalid request")
    spoken = body.get("text")
    if not isinstance(spoken, str) or not spoken.strip() or len(spoken) > 350:
        raise HTTPException(400, "Text must contain 1-350 characters")
    spoken = spoken.strip()
    allowed_voices = {
        "Kore", "Aoede", "Leda", "Zephyr", "Achernar",
        "Puck", "Charon", "Orus", "Achird", "Iapetus",
    }
    voice = body.get("voice", "Kore")
    if not isinstance(voice, str) or voice not in allowed_voices:
        raise HTTPException(400, "Unsupported TTS voice")
    ip = request.headers.get("x-forwarded-for", request.client.host if request.client else "unknown").split(",")[0].strip()
    now = time.monotonic()
    hits = recent[ip]
    while hits and now - hits[0] > WINDOW:
        hits.popleft()
    if len(hits) >= RATE_LIMIT:
        raise HTTPException(429, "Too many requests")
    hits.append(now)

    style = _detect_tts_style(spoken)
    project = os.getenv("GOOGLE_CLOUD_PROJECT", GOOGLE_PROJECT)
    # Use the enabled Cloud Text-to-Speech API with a higher-quality Gemini voice.
    # Keep translation, ASR and the public /api/tts contract unchanged.
    def get_tts_token():
        credentials, _ = google.auth.default(scopes=["https://www.googleapis.com/auth/cloud-platform"])
        if not credentials.valid:
            credentials.refresh(GoogleAuthRequest())
        return credentials.token
    try:
        token = await asyncio.to_thread(get_tts_token)
    except (DefaultCredentialsError, RefreshError) as exc:
        import logging
        logging.getLogger("avnivo.tts").exception("TTS credentials unavailable (%s)", type(exc).__name__)
        raise HTTPException(503, "TTS credentials unavailable")

    # Gemini 3.8 TTS detects Uzbek, Russian and other supported languages.
    # Unlike Cloud Text-to-Speech v1, this endpoint accepts turn-level speechMetadata.
    tts_payload = {
        "contents": [{"role": "user", "parts": [{
            "text": spoken,
            "speechMetadata": {"style": _TTS_STYLES[style]},
        }]}],
        "generationConfig": {
            "responseModalities": ["AUDIO"],
            "speechConfig": {"voiceConfig": {"voice": voice}},
        },
    }
    try:
        async with httpx.AsyncClient(timeout=75) as client:
            response = await client.post(
                f"https://aiplatform.googleapis.com/v1/projects/{project}/locations/global/publishers/google/models/gemini-3.8-flash-tts:generateContent",
                headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
                json=tts_payload,
            )
    except httpx.RequestError:
        raise HTTPException(502, "TTS provider unreachable")
    if response.status_code == 429:
        raise HTTPException(429, "TTS quota exceeded")
    if response.status_code in (401, 403):
        import logging
        logging.getLogger("avnivo.tts").error("Cloud TTS access denied: HTTP %s, response=%s", response.status_code, response.text[:1200])
        raise HTTPException(503, "TTS access denied; check service account permissions")
    if response.status_code != 200:
        import logging
        logging.getLogger("avnivo.tts").error("Cloud TTS failed: HTTP %s, response=%s", response.status_code, response.text[:1200])
        raise HTTPException(502, f"TTS provider HTTP {response.status_code}; see Cloud Run logs")
    try:
        payload = response.json()
        parts = payload["candidates"][0]["content"]["parts"]
        encoded = next(part["inlineData"]["data"] for part in parts if part.get("inlineData", {}).get("data"))
        audio = base64.b64decode(encoded, validate=True)
        if not audio or len(audio) > 8 * 1024 * 1024:
            raise ValueError("Invalid audio size")
    except (KeyError, IndexError, TypeError, ValueError, StopIteration):
        raise HTTPException(502, "Invalid TTS audio response")
    # Gemini 3.8 unary output is WAV; wrap raw PCM only if needed.
    if not audio.startswith(b"RIFF"):
        import io
        import wave
        output = io.BytesIO()
        with wave.open(output, "wb") as wav:
            wav.setnchannels(1)
            wav.setsampwidth(2)
            wav.setframerate(24000)
            wav.writeframes(audio)
        audio = output.getvalue()
    from fastapi.responses import Response
    return Response(content=audio, media_type="audio/wav", headers={"Cache-Control": "no-store", "X-AVNIVO-TTS-Style": style})
