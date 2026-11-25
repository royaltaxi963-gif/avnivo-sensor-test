# Independent Android build (no GitHub Actions)

The release APK must be producible without GitHub Actions, because an Actions billing lock stops
every job before it starts. Two independent paths are configured here:

1. **Local Gradle build in this workspace** — verified, no cloud cost.
2. **Google Cloud Build** — `android-app/cloudbuild.yaml`, for repeatable builds from a clean machine.

Both run the same `assembleRelease` task, the same NDK/CMake configuration, and the same signing
identity. No engine, model, asset or interface file is changed by either path.

## 1. Local build (verified in this workspace)

```bash
export JAVA_HOME=<jdk17>
export ANDROID_HOME=<android-sdk with platforms;android-35, build-tools;35.0.0, ndk;27.0.12077973>
export AVNIVO_VERSION_CODE=1315
export AVNIVO_ABIS=arm64-v8a        # or arm64-v8a,armeabi-v7a for the universal APK

cd android-app
mkdir -p app/libs
curl -fsSL -o app/libs/sherpa-onnx-2.0.1.aar \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar
git clone --depth 1 --branch v1.7.4 https://github.com/ggml-org/whisper.cpp.git app/src/main/cpp/whisper.cpp

gradle --no-daemon testDebugUnitTest      # 291 JVM tests
gradle --no-daemon assembleRelease        # 54 s for one ABI on 8 vCPU
```

Measured in this workspace: **BUILD SUCCESSFUL in 54 s**, `app-release-unsigned.apk` 30,610,388 bytes
for `arm64-v8a` only. `testDebugUnitTest`: 291 tests, 0 failures. Node suites: 153 pass.

`app-release-unsigned.apk` is unsigned on purpose: `app/build.gradle.kts` only configures a release
signing config when `AVNIVO_KEYSTORE_FILE` is set. **Do not generate a new key** — an APK signed by
a different key cannot be installed over the installed app, and the update would lose the downloaded
offline modules and user data. Sign with the existing identity:

```bash
printf '%s' "$AVNIVO_KEYSTORE_BASE64" | base64 -d > /workspace/avnivo-release.jks
export AVNIVO_KEYSTORE_FILE=/workspace/avnivo-release.jks
export AVNIVO_KEYSTORE_PASSWORD=... AVNIVO_KEY_ALIAS=... AVNIVO_KEY_PASSWORD=...
gradle --no-daemon assembleRelease

# The certificate must match the identity the installed app already trusts.
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk | grep -i 'SHA-256'
# expected: 12e7e26104c7a5110a849baec36d6916efe0b2494384749d4c733286aeeecb8e
```

The keystore itself is only stored as a GitHub Actions secret (`AVNIVO_KEYSTORE_BASE64` and the
three password/alias secrets). GitHub never exposes secret values through the API, so a local
signed build needs the same base64 blob supplied once as an environment variable or as a Google
Secret Manager entry. Nothing in this repository contains or derives that key.

## 2. Google Cloud Build

```bash
gcloud builds submit android-app --config=android-app/cloudbuild.yaml \
  --substitutions=_VERSION_CODE=1315,_ABIS=arm64-v8a,armeabi-v7a
```

Before the first run the project needs:

| Requirement | Command |
| --- | --- |
| APIs enabled | `gcloud services enable cloudbuild.googleapis.com secretmanager.googleapis.com storage.googleapis.com` |
| Build service account | `<PROJECT_NUMBER>@cloudbuild.gserviceaccount.com` |
| IAM for the build account | `roles/secretmanager.secretAccessor` (the four secrets), `roles/storage.objectAdmin` on the artifact bucket |
| Keystore secrets | `gcloud secrets create avnivo-keystore-b64 --data-file=-` (same base64 blob as the Actions secret), plus `avnivo-keystore-password`, `avnivo-key-alias`, `avnivo-key-password` |
| Artifact bucket | `gcloud storage buckets create gs://avnivo-translator-builds --location=europe-west1` |

The pipeline refuses to publish when the APK certificate is not the expected fingerprint, so a
misconfigured keystore fails the build instead of shipping an incompatible update.

### Cost

| Item | Rate | This build |
| --- | --- | --- |
| `E2_HIGHCPU_8` worker | ≈ $0.02 per build-minute | one ABI ≈ 3-4 min ≈ $0.08; two ABIs + native compiles ≈ 8-10 min ≈ $0.20 |
| Free tier | 120 build-minutes/day (default pool) | a handful of builds per day stays inside it |
| GCS storage | ≈ $0.02/GB-month | ~120 MB per APK, negligible |
| Egress | first 100 GB/month free | negligible |

No Cloud Run service, VM, or always-on resource is created. Nothing is billed until
`gcloud builds submit` is run, and the local path above costs nothing at all.

## 3. Device diagnostics for the STT hang

Every offline stage now writes one line to logcat and to a pullable file:

```bash
adb logcat -c
# reproduce: activate Whisper Large, then press the microphone
adb logcat -s AvnivoWhisper AvnivoOfflineStt AvnivoModuleLifecycle AvnivoMemory AvnivoGuard
adb pull /sdcard/Android/data/com.avnivo.translator/files/diagnostics/offline.log
```

Stage names to look for:

| Tag | Line | Meaning |
| --- | --- | --- |
| `AvnivoWhisper` | `WHISPER_LOAD_START` / `WHISPER_LOAD_END elapsed_ms=` | JNI model load, with the time it took |
| `AvnivoWhisper` | `WHISPER_NATIVE_INIT_START` / `_END` | inside `whisper_init_from_file_with_params` |
| `AvnivoWhisper` | `WHISPER_DECODE_PROGRESS pct=` | `whisper_full` progress, in 10 % steps |
| `AvnivoOfflineStt` | `UZBEK_LOAD_START` / `_END` | sherpa-onnx `OfflineRecognizer` construction |
| `AvnivoModuleLifecycle` | `STAGE_START` / `STAGE_END` / `STAGE_TIMEOUT` | the bounded call around each native stage |
| `AvnivoGuard` | `ENGINE_QUARANTINED` | a decoder that had to be abandoned (never freed) |

A stage that never prints its `_END` line is the one that hangs. That line is what was missing from
the S23 Ultra report.

## 4. What changed for the hang

| Symptom | Root cause | Fix |
| --- | --- | --- |
| Whisper Large stayed on «Ishga tushmoqda…» | `OfflineModuleManager.activate()` was `@Synchronized` and ran the whole native load inside the monitor, on the only worker thread. A load that never returned blocked `deactivate`, `unload`, `delete` and every other module, with no timeout anywhere. | The load runs outside the monitor through `OfflineGuard.boundedCloseable` with a 180 s budget; a timeout settles the card on `installed` with the stage name, keeps the verified files, and a later press retries with a fresh engine. |
| Uzbek card stayed on «Nutq matnga aylantirilmoqda…» while the user was on Whisper | `sttController.beginCapture()`/`endCapture()` were called for **every** capture, including Whisper sessions, so a Whisper decode that never finished pinned the Uzbek card on processing forever. | Capture bookkeeping is engine-aware: `publishState` is false for a Whisper session, and only an Uzbek session may `complete()`/`fail()` the Uzbek card. |
| Deactivation did nothing | Both deactivation paths refused while a decode was "in progress", and that flag was never cleared by a hang. | A stuck decode is now detected by budget, its engine is quarantined, `transcribingEngine` is cleared, and the module stays deactivatable. |
| Restart landed on the same stuck card | `reactivateInstalled()` reloaded the active module on every page load. | A load that had to be abandoned is remembered; only an explicit activation retries it. |
| One stuck decode blocked every later press | All decodes shared one single-thread executor. | Decodes run on a cached pool; the one-engine guard still serializes real work. |

A quarantined engine is never freed: `whisper_free` under a running `whisper_full`, or closing an
ONNX session under a running `OfflineRecognizer` constructor, is undefined behaviour. It is detached
and kept referenced, and a fresh engine is built for the next attempt.
