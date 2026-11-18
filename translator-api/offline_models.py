"""Private GCS model download tickets; fixed allowlist, no arbitrary object paths."""
import os
import json
import re
from pathlib import Path
import time
from collections import defaultdict, deque
from datetime import timedelta
from fastapi import APIRouter, HTTPException, Request
from google.auth import default as google_auth_default
from google.auth.transport.requests import Request as GoogleRequest
from google.cloud import storage

router = APIRouter()
BUCKET = os.getenv("AVNIVO_MODEL_BUCKET", "avnivo-offline-models-792624108019")
OBJECTS = {
    "uzbek-stt": [
        {"name": "model.int8.onnx", "object": "stt-uzbek/model.int8.onnx",
         "bytes": 591649160, "sha256": "8bff17ef8d7da04369a559b99b71c1155a90fc604d35d84c6d4e44159c1b0873"},
        {"name": "tokens.txt", "object": "stt-uzbek/tokens.txt",
         "bytes": 411, "sha256": "2022129a0c61784d4a1838da59e5875f9b29d2c6ad12413cbedc98632f98710f"},
    ]
}
# Only SHA256-verified, repository-reviewed manifests can enable extra models.
# Files are deliberately not fetched from GCS at request time.
MANIFEST_DIR = Path(__file__).resolve().parent / "verified_models"
ALLOWED_PREFIXES = {"navoiy-tts": "tts/uzbek/navoiy/", "mms-tts": "tts/mms/rus/"}

def load_verified_models():
    models = dict(OBJECTS)
    for model_id, prefix in ALLOWED_PREFIXES.items():
        path = MANIFEST_DIR / (model_id + ".json")
        if not path.is_file():
            continue
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            files = data["files"]
            if data.get("model_id") != model_id or not isinstance(files, list) or not files or len(files) > 100:
                continue
            checked = []
            for item in files:
                name, obj, digest, size = (item[k] for k in ("name", "object", "sha256", "bytes"))
                if not all(isinstance(x, str) for x in (name, obj, digest)):
                    raise ValueError("Invalid manifest strings")
                if not obj.startswith(prefix) or obj != prefix + name or name.startswith("/") or ".." in name.split("/"):
                    raise ValueError("Unsafe object path")
                if not re.fullmatch(r"[A-Za-z0-9_./-]+", name) or not re.fullmatch(r"[a-f0-9]{64}", digest):
                    raise ValueError("Invalid manifest hash or name")
                if not isinstance(size, int) or isinstance(size, bool) or size < 0 or size > 3_000_000_000:
                    raise ValueError("Invalid size")
                checked.append({"name": name, "object": obj, "sha256": digest, "bytes": size})
            models[model_id] = checked
        except (ValueError, KeyError, TypeError, OSError, json.JSONDecodeError):
            continue
    return models

_requests = defaultdict(deque)

@router.get("/api/offline-models/{model_id}/download-ticket")
def download_ticket(model_id: str, request: Request):
    """Return short-lived GCS signed GET URLs, with expected hashes for native verification.

    This endpoint intentionally does NOT claim installation or activation.
    """
    available = load_verified_models()
    if model_id not in available:
        raise HTTPException(404, "Unknown offline model")
    ip = request.client.host if request.client else "unknown"
    now = time.monotonic()
    hits = _requests[ip]
    while hits and now - hits[0] > 60:
        hits.popleft()
    if len(hits) >= 6:
        raise HTTPException(429, "Too many download requests")
    hits.append(now)
    try:
        credentials, _ = google_auth_default(scopes=["https://www.googleapis.com/auth/cloud-platform"])
        if not credentials.valid:
            credentials.refresh(GoogleRequest())
        email = os.getenv("AVNIVO_SIGNER_SERVICE_ACCOUNT")
        if not email:
            raise RuntimeError("Signing service account is not configured")
        client = storage.Client(credentials=credentials)
        bucket = client.bucket(BUCKET)
        files = []
        for item in available[model_id]:
            blob = bucket.blob(item["object"])
            url = blob.generate_signed_url(
                version="v4", expiration=timedelta(minutes=15), method="GET",
                service_account_email=email, access_token=credentials.token,
                response_disposition='attachment; filename="' + item["name"] + '"',
            )
            files.append({"name": item["name"], "bytes": item["bytes"],
                          "sha256": item["sha256"], "url": url})
    except Exception as exc:
        import logging
        logging.getLogger("avnivo.models").warning("Cannot issue model ticket: %s", type(exc).__name__)
        raise HTTPException(503, "Model downloads are not configured")
    return {"model_id": model_id, "expires_in_seconds": 900, "files": files}
