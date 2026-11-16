# Android APK shell — implementation checkpoint

This branch adds a launchable Android Activity, local WebViewAssetLoader origin, build-time packaging of existing HTML/JS/i18n assets, and permission-gated microphone capture.

**Not yet verified:** Gradle build, physical device microphone capture, Google Cloud downloads, background download service, ONNX/Whisper inference, onboarding merge, and complete 100-language packages.

Build from the existing android/ Gradle project with a local Android SDK. Do not merge until Android CI builds and device smoke tests pass.

No native offline runtime or model activation is claimed by this shell. External HTTPS API calls still require network and backend availability. Do not grant camera or other WebView permissions.
