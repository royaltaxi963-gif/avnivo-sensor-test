# Independent Android release build (no GitHub Actions)

GitHub Actions is blocked at the account level: workflow runs fail before the first step starts,
so no artifact is produced. This document describes how the same release APK is built, signed and
verified without Actions, either on a workstation or on Google Cloud Build.

Nothing here changes the app: the toolchain, Gradle files, native runtimes, offline engines, models
and UI are exactly the ones on `integrate/offline-engines-20261118` @ `25e5bd2`.

## Toolchain (unchanged)

| Component | Version |
| --- | --- |
| JDK | 17 (Temurin) |
| Android SDK platform | 35 |
| Build tools | 35.0.0 |
| NDK | 27.0.12077973 |
| CMake | 3.22.1 |
| Gradle | 8.10.2 |
| AGP / Kotlin | 8.7.3 / 2.0.21 |

Native inputs are fetched at build time, exactly as the workflow did:

- `sherpa-onnx-2.0.1.aar` — pinned by SHA-256 `a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7`.
- `whisper.cpp` `v1.7.4` — cloned into `android-app/app/src/main/cpp/whisper.cpp` (git-ignored).

Models are **not** bundled and are not downloaded during the build; they are downloaded by the app
at runtime into `filesDir/models/<module>`.

## Local build

```bash
export JAVA_HOME=/opt/jdk
export ANDROID_HOME=/opt/android-sdk
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:/opt/gradle-8.10.2/bin:$PATH

export AVNIVO_VERSION_CODE=1314
export AVNIVO_KEYSTORE_FILE=/secure/avnivo-release.jks   # existing key, never committed
export AVNIVO_KEYSTORE_PASSWORD=...
export AVNIVO_KEY_ALIAS=...
export AVNIVO_KEY_PASSWORD=...                           # defaults to the keystore password

scripts/build-release-apk.sh
```

The script:

1. Checks the SDK/JDK components.
2. Fetches and checksum-verifies the native dependencies.
3. Refuses to build if the keystore certificate is not `12e7e26104c7a5110a849baec36d6916efe0b2494384749d4c733286aeeecb8e`.
4. Runs `clean assembleRelease testDebugUnitTest`.
5. Re-verifies the signed APK with `apksigner`, and fails if the signer differs.
6. Writes `dist/report.txt` with commit, size, SHA-256, versionCode and signer digest.

`AVNIVO_SKIP_SIGN=1` builds an unsigned APK for inspection only; it is not installable as an update.

## Google Cloud Build

`build/android/Dockerfile` builds the pinned toolchain image; `build/android/cloudbuild.yaml` builds,
signs, verifies and publishes.

```bash
gcloud builds submit --config build/android/cloudbuild.yaml \
  --substitutions=_VERSION_CODE=1314,_BUCKET=gs://<artifact-bucket>,_TAG=avnivo-v1314 .
```

The keystore is read from Secret Manager at build time and deleted from the worker before the step
ends. It is never written to the repository, to a build log, or to an artifact.

### Required IAM

| Principal | Role | Purpose |
| --- | --- | --- |
| Cloud Build service account (`<PROJECT_NUMBER>@cloudbuild.gserviceaccount.com`) | `roles/secretmanager.secretAccessor` on the three keystore secrets | read the keystore and passwords |
| Cloud Build service account | `roles/storage.objectCreator` on the artifact bucket | publish the APK |
| Cloud Build service account | `roles/logging.logWriter` | build logs |
| Trigger/operator account | `roles/cloudbuild.builds.editor` | start builds |
| Trigger/operator account | `roles/artifactregistry.writer` (if the builder image moves to Artifact Registry) | push the builder image |

### Cost estimate (verify in the billing console before enabling)

Cloud Build gives 120 free build-minutes per day on the default pool. This build uses
`E2_HIGHCPU_8`, which is billed per build-minute beyond the free allowance:

| Item | Estimate |
| --- | --- |
| Builder image build (first run only) | ~8–12 min |
| APK build + unit tests + signing | ~10–15 min |
| Per-build cost at the high-CPU rate (approx. $0.016/min) | ~$0.20–0.40 |
| Builder image storage (~7 GB) | ~$0.70–1.00 / month |
| APK egress (123 MB per download) | ~$0.015 each |

A daily build therefore stays in the low single-digit dollars per month. Nothing is spent until a
build is submitted; the configuration alone costs nothing.

## Update-in-place requirements

An APK can only replace the installed app when all of these hold:

| Requirement | Where it is checked |
| --- | --- |
| Same `applicationId` (`com.avnivo.translator`) | `android-app/app/build.gradle.kts` |
| Same signer certificate | `scripts/build-release-apk.sh` (fails on mismatch) |
| Higher `versionCode` | `AVNIVO_VERSION_CODE` |
| Same `minSdk`/ABI coverage | `build.gradle.kts`, 4 ABIs packaged |

Offline modules and user data live in app-private internal storage
(`filesDir/models/<module>`, WebView storage), which `adb install -r` keeps. `android:allowBackup="false"`
does not affect in-place updates.

## Why the keystore cannot be recreated

The release identity `CN=AVNIVO Translator, OU=Development, O=AVNIVO, C=uz` exists only as the
GitHub Actions secret `AVNIVO_KEYSTORE_BASE64`. GitHub never returns secret values, and Actions
cannot run on this account, so the private key must be supplied by the repository owner before a
signed update can be produced. Generating a new key would change the certificate and force a
uninstall, which would delete the downloaded offline modules — hence the hard failure above.

## In-place update check on a device

The build host for this repository has no KVM, so an Android emulator cannot be started there.
Run this on the target phone (USB debugging enabled) or on a machine with KVM:

```bash
adb install -r AVNIVO-Translator-<sha>.apk
adb shell dumpsys package com.avnivo.translator | grep -E "firstInstallTime|versionCode|lastUpdateTime"
adb shell run-as com.avnivo.translator ls files/models    # offline modules still present
```

Pass criteria:

- `Success` from `adb install -r` (a signature mismatch returns `INSTALL_FAILED_UPDATE_INCOMPATIBLE`).
- `firstInstallTime` unchanged, `lastUpdateTime` newer, `versionCode` increased.
- `files/models` still lists the downloaded modules.

The same check ran green in CI before Actions was blocked (run `45102003961`: `firstInstallTime`
preserved, versionCode `100` → `1259`, `UPDATE_INSTALL_OK`).
