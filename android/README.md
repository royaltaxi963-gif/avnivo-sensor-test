# AVNIVO Android shell — background STT download (work in progress)

This is an additive Android project. The existing index.html, online/offline controls, and web branch are unchanged.

## Architecture
- Native Android foreground service for user-initiated large file transfer; persistent notification and explicit cancel action.
- App-private file storage with Range resume and full SHA-256 verification before exposing any file to inference.
- Signed URL refresh via existing /api/offline-models/uzbek-stt/download-ticket endpoint.
- WebView UI to be bundled as local assets via a controlled origin (AndroidX WebViewAssetLoader); avoid exposing native bridge to untrusted origins.
- Network availability callbacks, backoff, and persisted transfer checkpoint; WorkManager for recovery after process death or reboot.

## Current state
The native background service, bridge, and APK build are **not yet implemented or tested**. This initial scaffold records build settings and native service permissions only. Do not publish as working APK.

## Security gates
1. Confirm server-side ticket endpoint deployed and CORS/auth behavior.
2. Confirm GCS Range support, content length and Content-Range.
3. Never mark model ready until exact pinned SHA-256 for both files matches.
4. Keep signed URLs out of logs and WebView JavaScript.
5. Validate Android 14+ foreground service type and user-visible notification.
6. Verify locked-screen, app-background, network-off/on, force-stop and reboot behavior on a physical device.
