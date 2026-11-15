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
_requests = defaultdict(deque)

@router.get("/api/offline-models/{model_id}/download-ticket")
def download_ticket(model_id: str, request: Request):
    """Return short-lived GCS signed GET URLs, with expected hashes for native verification.

    This endpoint intentionally does NOT claim installation or activation.
    """
    if model_id not in OBJECTS:
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
        for item in OBJECTS[model_id]:
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
