# AVNIVO Tarjimon — Android shell, phase 1

Isolated native Android WebView skeleton. **Not a functional translator APK yet.** Existing web UI, STT, TTS, online and offline services remain unchanged.

Build prerequisites: JDK 17, Android SDK 35, Gradle 8.9 (run `gradle :app:assembleDebug` from this folder). A Gradle wrapper is not yet committed; no build is claimed.

Uses AndroidX WebKit WebViewAssetLoader to load bundled HTML via `https://appassets.androidplatform.net/assets/index.html`. Only the trusted local origin can request microphone permission; Android runtime permission is requested first. External navigation is blocked; no arbitrary JS bridge is exposed.

The bundled HTML is an explicitly labelled placeholder. Before integration, pin the correct tested AVNIVO HTML revision and audit its network origins, CSP, microphone behavior, and backend CORS. Do not copy an unverified HTML build or merge parallel OpenHands branches.

Acceptance pending: Gradle compile, Android emulator/device launch, microphone permission, asset load, external navigation denial, regression and actual device testing. No production deploy.
