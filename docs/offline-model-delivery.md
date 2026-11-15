# AVNIVO offline model delivery: Android integration contract

## Implemented (backend source only)
GET /api/offline-models/uzbek-stt/download-ticket issues two **15-minute signed HTTPS GET URLs** for the verified private GCS objects, and their exact SHA-256 and byte sizes. Unknown model IDs fail with 404. No public bucket access is required.

Environment variable: `AVNIVO_SIGNER_SERVICE_ACCOUNT` = the **Cloud Run runtime service account email** (not an API key). The runtime identity needs `roles/storage.objectViewer` on the bucket and `iam.serviceAccounts.signBlob` on the signing account (typically grant `roles/iam.serviceAccountTokenCreator` on that account to the runtime identity). Cloud Run must deploy the updated translator-api image. Until permissions and deployment are verified, endpoint may return 503.

**Security limitation:** The ticket endpoint currently has no user authentication. Short URL lifetime, model allowlist and simple in-memory request limiting reduce accidental exposure but do not prevent bulk downloading; add authenticated issuance, distributed rate limits, and quotas before a public rollout. Signed URLs are bearer credentials; never log or store them in public files.

## Android client (not yet implemented)
1. Query download-ticket from the configured trusted HTTPS backend.
2. Download each file directly to an app-private temporary file, using a foreground notification / WorkManager with resumable downloads as appropriate. Keep signed URL refresh separate from partial download retry.
3. Check exact **byte length** and **SHA-256** of both files before use. Reject mismatches and remove temporary files.
4. Atomically move verified files into an app-private versioned directory; never execute external code or accept arbitrary model paths.
5. Initialize the existing Sherpa-ONNX STT runtime using the actual model type/configuration and tokenizer. Run a local recognition self-test; only then set status `active`.
6. Implement the JS interface exposed to the trusted WebView origin only:
   - `AvnivoOfflineModules.status("uzbek-stt")` returns JSON string `{"state":"not_installed"|"downloading"|"activating"|"active"|"error","percent":0..100,"active":boolean}`.
   - `AvnivoOfflineModules.download("uzbek-stt")` starts the download; emit `avnivo-offline-module-status` on state changes.
   - `AvnivoOfflineModules.remove("uzbek-stt")` stops use and deletes app-private files.
7. Keep HTML-only GitHub Pages in **preview mode**; it cannot install Android ONNX models. Do not show a false active badge.

## Operational checklist
- Validate Cloud Run revision, IAM, ticket issuance and signed URL GET in a real environment.
- Run Android install, restart, airplane-mode inference, interrupted download, corrupt hash, low disk, cancellation and deletion tests.
- Revoke broad project-level Storage Admin from the deployer once narrower bucket-level roles are configured and tested.
