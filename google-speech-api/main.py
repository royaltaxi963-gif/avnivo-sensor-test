"""AVNIVO Google Speech-to-Text V2 backend (Cloud Run, ADC)."""
import os
from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from google.cloud import speech_v2
from google.api_core.exceptions import GoogleAPICallError, InvalidArgument
from starlette.concurrency import run_in_threadpool

ORIGIN = "https://royaltaxi963-gif.github.io"
LANGUAGES = {"uz": "uz-UZ", "ru": "ru-RU", "en": "en-US", "tr": "tr-TR"}
MAX_BYTES = 10 * 1024 * 1024
app = FastAPI(title="AVNIVO Google Speech API", docs_url=None, redoc_url=None)
app.add_middleware(CORSMiddleware, allow_origins=[ORIGIN], allow_methods=["POST"], allow_headers=["Content-Type"])

@app.get("/health")
async def health():
    return {"status": "ok", "provider": "google-speech-v2", "configured": bool(os.getenv("GOOGLE_CLOUD_PROJECT"))}

def recognize(data: bytes, language: str, encoding: str) -> str:
    project = os.environ["GOOGLE_CLOUD_PROJECT"]
    client = speech_v2.SpeechClient()
    config = speech_v2.RecognitionConfig(
        explicit_decoding_config=speech_v2.ExplicitDecodingConfig(
            encoding=getattr(speech_v2.ExplicitDecodingConfig.AudioEncoding, encoding),
            sample_rate_hertz=48000,
            audio_channel_count=1,
        ),
        language_codes=[LANGUAGES[language]],
        model="long",
    )
    result = client.recognize(request=speech_v2.RecognizeRequest(
        recognizer=f"projects/{project}/locations/global/recognizers/_",
        config=config,
        content=data,
    ), timeout=75)
    return " ".join(r.alternatives[0].transcript for r in result.results if r.alternatives).strip()

@app.post("/api/transcribe")
async def transcribe(request: Request, audio: UploadFile = File(...), language: str = Form("uz")):
    # CORS/Origin is not authentication. Deploy with upstream abuse protection.
    if request.headers.get("origin") != ORIGIN:
        raise HTTPException(403, "Origin not allowed")
    if language not in LANGUAGES:
        raise HTTPException(400, "Unsupported language")
    encoding = {"audio/webm": "WEBM_OPUS", "audio/ogg": "OGG_OPUS"}.get(
        (audio.content_type or "").split(";")[0].lower()
    )
    if not encoding:
        raise HTTPException(415, "Unsupported audio format")
    data = await audio.read(MAX_BYTES + 1)
    if not data or len(data) > MAX_BYTES:
        raise HTTPException(413, "Invalid audio size")
    if not os.getenv("GOOGLE_CLOUD_PROJECT"):
        raise HTTPException(503, "Google Cloud project not configured")
    try:
        text = await run_in_threadpool(recognize, data, language, encoding)
    except (InvalidArgument, ValueError):
        raise HTTPException(422, "Audio could not be recognized")
    except GoogleAPICallError:
        raise HTTPException(502, "Speech provider error")
    return {"text": text, "model": "google-speech-v2"}
