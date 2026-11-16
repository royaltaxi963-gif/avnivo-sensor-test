# Android release consolidation checkpoint

Integrated the existing five-step onboarding preview into the actual APK entrypoint `index.html`, preserving the animated presentation and setting a one-time completion guard. Existing Uzbek STT downloader remains bundled.

Android debug APK CI is configured for Java 17, Android SDK 35 and Gradle 8.9. It uploads an installable debug artifact only if the build succeeds. A successful compile is **not** proof of working offline inference.

Pending for release: native model download and inference, Whisper GCS object upload and SHA-256, 100-language onboarding selection, full microphone/online API/airplane-mode physical-device tests, and production signing.

This branch is intentionally independent of the in-progress 100-language localization PR #22. Reconcile both branches before release, without overwriting newer translations.
