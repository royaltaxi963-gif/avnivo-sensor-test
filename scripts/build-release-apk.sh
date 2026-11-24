#!/usr/bin/env bash
# Build, sign and verify the AVNIVO Translator release APK without GitHub Actions.
#
# Signing uses the existing release identity. The script refuses to deliver an APK whose
# certificate does not match AVNIVO_EXPECTED_CERT_SHA256, so a wrong or freshly generated key
# cannot silently produce an APK that cannot update the installed app.
#
# Required for signing:
#   AVNIVO_KEYSTORE_FILE      path to the existing keystore (never commit it)
#   AVNIVO_KEYSTORE_PASSWORD  keystore password
#   AVNIVO_KEY_ALIAS          key alias
#   AVNIVO_KEY_PASSWORD       key password (defaults to the keystore password)
#
# Optional:
#   AVNIVO_VERSION_CODE       versionCode, must be greater than the installed one
#   AVNIVO_EXPECTED_CERT_SHA256  expected signer certificate digest
#   AVNIVO_OUT_DIR            output directory (default: dist/)
#   AVNIVO_SKIP_SIGN=1        build an unsigned APK only (no delivery)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_DIR="$ROOT/android-app"
OUT_DIR="${AVNIVO_OUT_DIR:-$ROOT/dist}"
EXPECTED_CERT="${AVNIVO_EXPECTED_CERT_SHA256:-12e7e26104c7a5110a849baec36d6916efe0b2494384749d4c733286aeeecb8e}"
SHERPA_AAR_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar"
SHERPA_AAR_SHA256="a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7"
WHISPER_TAG="v1.7.4"

log() { printf '\n== %s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

: "${ANDROID_HOME:?ANDROID_HOME must point at an Android SDK with platform 35, build-tools 35.0.0 and NDK 27.0.12077973}"
: "${JAVA_HOME:?JAVA_HOME must point at JDK 17}"
GRADLE_BIN="${GRADLE_BIN:-gradle}"
BUILD_TOOLS="$ANDROID_HOME/build-tools/35.0.0"

log "Preflight"
"$JAVA_HOME/bin/java" -version 2>&1 | head -1
for d in "$ANDROID_HOME/platforms/android-35" "$BUILD_TOOLS" "$ANDROID_HOME/ndk/27.0.12077973"; do
  [ -d "$d" ] || die "missing SDK component: $d"
done
command -v "$GRADLE_BIN" >/dev/null || die "gradle not found (set GRADLE_BIN)"
git -C "$ROOT" rev-parse HEAD

log "Native dependencies"
mkdir -p "$APP_DIR/app/libs"
aar="$APP_DIR/app/libs/sherpa-onnx-2.0.1.aar"
if [ ! -s "$aar" ] || [ "$(sha256sum "$aar" | cut -d' ' -f1)" != "$SHERPA_AAR_SHA256" ]; then
  curl --fail --location --retry 3 -o "$aar" "$SHERPA_AAR_URL"
  echo "$SHERPA_AAR_SHA256  $aar" | sha256sum -c -
fi
if [ ! -f "$APP_DIR/app/src/main/cpp/whisper.cpp/CMakeLists.txt" ]; then
  rm -rf "$APP_DIR/app/src/main/cpp/whisper.cpp"
  git clone --depth 1 --branch "$WHISPER_TAG" https://github.com/ggml-org/whisper.cpp.git \
    "$APP_DIR/app/src/main/cpp/whisper.cpp"
fi

log "Signing identity"
if [ "${AVNIVO_SKIP_SIGN:-0}" = "1" ]; then
  unset AVNIVO_KEYSTORE_FILE AVNIVO_KEYSTORE_PASSWORD AVNIVO_KEY_ALIAS AVNIVO_KEY_PASSWORD
  echo "AVNIVO_SKIP_SIGN=1: building unsigned"
else
  : "${AVNIVO_KEYSTORE_FILE:?set AVNIVO_KEYSTORE_FILE to the existing keystore}"
  [ -f "$AVNIVO_KEYSTORE_FILE" ] || die "keystore not found: $AVNIVO_KEYSTORE_FILE"
  export AVNIVO_KEYSTORE_FILE
  export AVNIVO_KEYSTORE_PASSWORD="${AVNIVO_KEYSTORE_PASSWORD:?set AVNIVO_KEYSTORE_PASSWORD}"
  export AVNIVO_KEY_ALIAS="${AVNIVO_KEY_ALIAS:?set AVNIVO_KEY_ALIAS}"
  export AVNIVO_KEY_PASSWORD="${AVNIVO_KEY_PASSWORD:-$AVNIVO_KEYSTORE_PASSWORD}"
  # Fail before a long build if the key is not the one the installed app trusts.
  keystore_cert=$("$JAVA_HOME/bin/keytool" -list -v -keystore "$AVNIVO_KEYSTORE_FILE" \
    -alias "$AVNIVO_KEY_ALIAS" -storepass "$AVNIVO_KEYSTORE_PASSWORD" 2>/dev/null \
    | sed -n 's/.*SHA256: *//p' | tr -d ':' | tr 'A-Z' 'a-z' | head -1)
  [ "$keystore_cert" = "$EXPECTED_CERT" ] || die "keystore certificate $keystore_cert != expected $EXPECTED_CERT"
  echo "keystore certificate matches $EXPECTED_CERT"
fi

log "Build"
cd "$APP_DIR"
"$GRADLE_BIN" --no-daemon clean assembleRelease testDebugUnitTest
apk=$(find app/build/outputs/apk/release -name '*.apk' | head -1)
[ -n "$apk" ] || die "no APK produced"

mkdir -p "$OUT_DIR"
name="AVNIVO-Translator-$(git -C "$ROOT" rev-parse --short HEAD).apk"
cp "$apk" "$OUT_DIR/$name"
apk="$OUT_DIR/$name"

log "Verification"
"$BUILD_TOOLS/aapt2" dump badging "$apk" | grep -E "^package:|^sdkVersion|^targetSdkVersion" || true
if [ "${AVNIVO_SKIP_SIGN:-0}" != "1" ]; then
  certs=$("$BUILD_TOOLS/apksigner" verify --print-certs "$apk" | tee "$OUT_DIR/cert.txt")
  echo "$certs"
  echo "$certs" | grep -qi "SHA-256 digest: $EXPECTED_CERT" \
    || die "APK signer certificate does not match $EXPECTED_CERT"
  "$BUILD_TOOLS/apksigner" verify "$apk" || die "apksigner verification failed"
else
  echo "unsigned build: skipping signature verification"
fi

size=$(stat -c%s "$apk")
sha=$(sha256sum "$apk" | cut -d' ' -f1)
version_code=$("$BUILD_TOOLS/aapt2" dump badging "$apk" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" | head -1)
{
  echo "commit=$(git -C "$ROOT" rev-parse HEAD)"
  echo "apk=$apk"
  echo "size=$size"
  echo "sha256=$sha"
  echo "versionCode=$version_code"
  echo "signer_cert_sha256=${EXPECTED_CERT}"
} | tee "$OUT_DIR/report.txt"

log "Done"
