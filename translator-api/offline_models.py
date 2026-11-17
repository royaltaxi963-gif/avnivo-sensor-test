"""Private GCS model download tickets; fixed allowlist, no arbitrary object paths."""
import os
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

# Additional models are enabled only after an operator supplies an audited manifest.
# Never infer hashes from untrusted requests or grant arbitrary GCS object access.
import json
import re
ADDITIONAL_MODEL_PREFIXES = {
    "whisper-small-q8": "multilingual-stt/whisper-small-q8_0/",
    "whisper-large-v3-turbo": "multilingual-stt/whisper-large-v3-turbo-q5_0/",
    "nllb-200-600m-int8": "multilingual-translation/nllb-200-600m-int8/",
    "navoiy-tts": "tts/uzbek/navoiy/",
    "mms-tts": "tts/mms/",
}
def _load_audited_models():
    """Optional JSON from a private deployment secret; fail closed if absent/invalid."""
    raw = os.getenv("AVNIVO_AUDITED_MODEL_MANIFEST", "")
    if not raw:
        return {}
    try:
        catalog = json.loads(raw)
        if not isinstance(catalog, dict):
            raise ValueError("catalog")
        result = {}
        for model_id, files in catalog.items():
            prefix = ADDITIONAL_MODEL_PREFIXES.get(model_id)
            if prefix is None or not isinstance(files, list) or not files or len(files)>1000:
                raise ValueError("model")
            checked = []
            seen = set()
            for item in files:
                obj = item["object"]
                name = item["name"]
                size = item["bytes"]
                sha = item["sha256"]
                if (not isinstance(obj,str) or not obj.startswith(prefix)
                    or ".." in obj or not isinstance(name,str)
                    or not re.fullmatch(r"[a-zA-Z0-9_.\\/-]{1,180}",name)
                    or ".." in name or name in seen
                    or not isinstance(size,int) or isinstance(size,bool) or size<=0
                    or not isinstance(sha,str) or not re.fullmatch(r"[a-f0-9]{64}",sha)):
                    raise ValueError("file")
                seen.add(name)
                checked.append({"object":obj,"name":name,"bytes":size,"sha256":sha})
            result[model_id]=checked
        return result
    except (ValueError, TypeError, KeyError, json.JSONDecodeError):
        import logging
        logging.getLogger("avnivo.models").warning("Audited model manifest rejected")
        return {}

_requests = defaultdict(deque)

@router.get("/api/offline-models/{model_id}/download-ticket")
def download_ticket(model_id: str, request: Request):
    """Return short-lived GCS signed GET URLs, with expected hashes for native verification.

    This endpoint intentionally does NOT claim installation or activation.
    """
    # Browser origin restriction is defense in depth, not authentication.
    origin = request.headers.get("origin")
    allowed = {"https://royaltaxi963-gif.github.io", "https://raw.githack.com",
               "https://rawcdn.githack.com", "http://localhost:3000",
               "http://localhost:8080"}
    if origin is not None and origin not in allowed:
        raise HTTPException(403, "Origin not allowed")
    models = {**OBJECTS, **_load_audited_models()}
    if model_id not in models:
        raise HTTPException(404, "Unknown or unverified offline model")
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
        for item in models[model_id]:
            blob = bucket.blob(item["object"])
            # Fail closed if an audited object was removed or its size changed.
            blob.reload(client=client)
            if blob.size != item["bytes"]:
                raise RuntimeError("Audited object size mismatch")
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
