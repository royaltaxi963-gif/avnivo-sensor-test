"""AVNIVO Google Speech-to-Text V2 backend (Cloud Run, ADC)."""
import os
from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from google.cloud import speech_v2
from google.api_core.client_options import ClientOptions
from google.api_core.exceptions import GoogleAPICallError, InvalidArgument
from starlette.concurrency import run_in_threadpool

REGION = "us-central1"
MODEL = "chirp_2"
LANGUAGES = {"uz": "uz-UZ", "ru": "ru-RU", "en": "en-US", "tr": "tr-TR"}
MAX_BYTES = 10 * 1024 * 1024
app = FastAPI(title="AVNIVO Google Speech API", docs_url=None, redoc_url=None)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

@app.get("/health")
async def health():
    return {"status": "ok", "provider": "google-speech-v2", "configured": bool(os.getenv("GOOGLE_CLOUD_PROJECT"))}

def recognize(data: bytes, language: str, encoding: str) -> str:
    project = os.environ["GOOGLE_CLOUD_PROJECT"]
    client = speech_v2.SpeechClient(
        client_options=ClientOptions(api_endpoint=f"{REGION}-speech.googleapis.com")
    )
    config = speech_v2.RecognitionConfig(
        explicit_decoding_config=speech_v2.ExplicitDecodingConfig(
            encoding=getattr(speech_v2.ExplicitDecodingConfig.AudioEncoding, encoding),
            sample_rate_hertz=48000,
            audio_channel_count=1,
        ),
        language_codes=[LANGUAGES[language]],
        model=MODEL,
        adaptation=speech_v2.SpeechAdaptation(
            phrase_sets=[
                speech_v2.SpeechAdaptation.AdaptationPhraseSet(
                    inline_phrase_set=speech_v2.PhraseSet(
                        phrases=[
                            speech_v2.PhraseSet.Phrase(value="Avnivo", boost=20.0),
                            speech_v2.PhraseSet.Phrase(value="ovoz tanish", boost=15.0),
                            speech_v2.PhraseSet.Phrase(value="sinovi", boost=10.0),
                        ]
                    )
                )
            ]
        ),
    )
    result = client.recognize(request=speech_v2.RecognizeRequest(
        recognizer=f"projects/{project}/locations/{REGION}/recognizers/_",
        config=config,
        content=data,
    ), timeout=75)
    return " ".join(r.alternatives[0].transcript for r in result.results if r.alternatives).strip()

@app.post("/api/transcribe")
async def transcribe(request: Request, audio: UploadFile = File(...), language: str = Form("uz")):
    # CORS is not authentication; restrict public access before production.
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
    return {"text": text, "model": MODEL}
