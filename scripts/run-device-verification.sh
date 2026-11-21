#!/usr/bin/env bash
#
# Run the AVNIVO offline-engine verification on a real ARM64 device (target: Galaxy S23 Ultra).
#
# CI can only reach x86_64 emulators. This script is the physical-device counterpart: it installs
# the debug APK on a connected phone, runs the same instrumentation suites, captures the results as
# JSON plus logcat, and prints a summary you can attach to the PR.
#
# Usage:
#   scripts/run-device-verification.sh                  # all suites
#   scripts/run-device-verification.sh whisper          # one suite
#   OUT=/tmp/avnivo-device scripts/run-device-verification.sh
#
# Requirements: adb on PATH, a device authorised for USB debugging, JDK 17 and the Android SDK.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP="$ROOT/android-app/app"
OUT="${OUT:-$ROOT/build/device-verification}"
SUITE="${1:-all}"
PKG="com.avnivo.translator"
RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"

mkdir -p "$OUT"

say() { printf '\n=== %s ===\n' "$*"; }

say "Checking device"
if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found. Install platform-tools and add it to PATH." >&2
  exit 1
fi
DEVICES=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
if [ -z "$DEVICES" ]; then
  echo "No authorised device. Enable Developer options > USB debugging, reconnect, accept the prompt." >&2
  adb devices
  exit 1
fi
for d in $DEVICES; do
  echo "device: $d  $(adb -s "$d" shell getprop ro.product.model | tr -d '\r')  $(adb -s "$d" shell getprop ro.build.version.release | tr -d '\r')  $(adb -s "$d" shell getprop ro.product.cpu.abi | tr -d '\r')"
done

say "Recording device facts"
{
  echo "model=$(adb shell getprop ro.product.model | tr -d '\r')"
  echo "android=$(adb shell getprop ro.build.version.release | tr -d '\r')"
  echo "abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
  echo "memtotal_kb=$(adb shell grep MemTotal /proc/meminfo | tr -s ' ' | cut -d' ' -f2 | tr -d '\r')"
} > "$OUT/device.txt"
cat "$OUT/device.txt"

say "Building debug and androidTest APKs"
(cd "$APP" && ./gradlew --no-daemon assembleDebug assembleDebugAndroidTest) || {
  echo "Build failed." >&2
  exit 1
}

say "Installing (in-place update, existing app data untouched)"
adb install -r "$APP/build/outputs/apk/debug/app-debug.apk" || exit 1
adb install -r "$APP/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" || exit 1

run_suite() {
  local name="$1"; shift
  say "Suite: $name"
  adb logcat -c
  adb shell am instrument -w -r "$@" "$RUNNER" > "$OUT/instrument_$name.txt" 2>&1
  adb logcat -d > "$OUT/logcat_$name.txt" 2>&1
  if grep -q "OK (" "$OUT/instrument_$name.txt"; then
    echo "PASS: $name  $(grep -o 'OK ([0-9]* tests*)' "$OUT/instrument_$name.txt" | head -1)"
  else
    echo "FAIL: $name (see $OUT/instrument_$name.txt)" >&2
    return 1
  fi
}

FAILED=0
case "$SUITE" in
  all|stt)
    run_suite real -e avnivoRealInference true -e class "$PKG.OfflineRealInferenceTest" || FAILED=1
    ;;
esac
case "$SUITE" in
  all|whisper)
    run_suite whisper -e avnivoWhisperInference true -e class "$PKG.OfflineWhisperInferenceTest" || FAILED=1
    ;;
esac
case "$SUITE" in
  all|nllb|offline)
    run_suite nllb -e avnivoNllbInference true -e class "$PKG.OfflineNllbInferenceTest" || FAILED=1
    ;;
esac

case "$SUITE" in
  all|whisper-large)
    run_suite whisper_large -e avnivoWhisperLargeInference true -e class "$PKG.OfflineWhisperInferenceTest" || FAILED=1
    ;;
esac

if [ "$SUITE" = "all" ] || [ "$SUITE" = "nllb" ] || [ "$SUITE" = "offline" ]; then
  say "Disabling radios and repeating NLLB"
  adb shell svc wifi disable
  adb shell svc data disable
  sleep 5
  if adb shell ping -c 1 -W 2 8.8.8.8 >/dev/null 2>&1; then
    echo "Network still reachable; the offline pass would not prove anything." >&2
    FAILED=1
  else
    echo "NETWORK_DOWN confirmed"
    run_suite nllb_offline -e avnivoNllbInference true -e class "$PKG.OfflineNllbInferenceTest" || FAILED=1
  fi
  adb shell svc wifi enable
  adb shell svc data enable
fi

say "Peak memory during the run"
adb shell dumpsys meminfo "$PKG" > "$OUT/meminfo.txt" 2>&1
grep -iE "TOTAL PSS|TOTAL RSS" "$OUT/meminfo.txt" | head -2

say "Summary"
printf 'artifacts: %s\n' "$OUT"
ls -1 "$OUT"
if [ "$FAILED" -ne 0 ]; then
  echo "One or more suites failed." >&2
  exit 1
fi
echo "All requested suites passed on the connected device."
