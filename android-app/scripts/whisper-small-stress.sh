#!/usr/bin/env bash
# Optional Whisper Small stress run. The emulator runner executes each workflow line in its own
# shell, so the loop lives in a script instead of a multi-line `if` in the YAML.
set -euo pipefail

echo "=== whisper small stress: 20 real sessions ==="
adb shell am instrument -w -e class com.avnivo.translator.OfflineWhisperInferenceTest#twentyRealSessionsKeepTheirOwnTranscript -e avnivoWhisperStress true com.avnivo.translator.test/androidx.test.runner.AndroidJUnitRunner | tee instrument_whisper_stress.txt
grep -q "OK (" instrument_whisper_stress.txt || { echo WHISPER_SMALL_STRESS_FAILED; exit 1; }
adb logcat -d | grep -a "WHISPER_SMALL_STRESS" | tail -3
adb logcat -d | grep -qa "WHISPER_SMALL_STRESS sessions=20 ok=20" || { echo WHISPER_SMALL_STRESS_NOT_MEASURED; exit 1; }

echo "=== whisper small stress: 10 unload/reload cycles ==="
adb shell am instrument -w -e class com.avnivo.translator.OfflineWhisperInferenceTest#tenUnloadReloadCyclesKeepTheModelUsable -e avnivoWhisperStress true com.avnivo.translator.test/androidx.test.runner.AndroidJUnitRunner | tee instrument_whisper_reload.txt
grep -q "OK (" instrument_whisper_reload.txt || { echo WHISPER_SMALL_RELOAD_FAILED; exit 1; }
adb logcat -d | grep -a "WHISPER_SMALL_RELOAD" | tail -3
adb logcat -d | grep -qa "WHISPER_SMALL_RELOAD cycles=10 ok=10" || { echo WHISPER_SMALL_RELOAD_NOT_MEASURED; exit 1; }

echo "=== whisper small stress: 10 real app restarts ==="
restarts=0
for i in $(seq 1 10); do
  adb logcat -c
  adb shell am force-stop com.avnivo.translator
  adb shell am start -n com.avnivo.translator/.MainActivity >/dev/null
  ready=0
  for t in $(seq 1 60); do
    sleep 1
    if adb logcat -d | grep -qa "AvnivoModules: AvnivoOfflineModuleReady whisper-small-q8"; then ready=1; break; fi
  done
  if [ "$ready" != 1 ]; then
    echo APP_RESTART_${i}_NOT_READY
    adb logcat -d | grep -a "AvnivoModules" | tail -20
    exit 1
  fi
  restarts=$((restarts + 1))
done
echo WHISPER_SMALL_RESTARTS restarts=$restarts ready=$restarts
[ "$restarts" = 10 ] || { echo WHISPER_SMALL_RESTARTS_NOT_MEASURED; exit 1; }
echo WHISPER_SMALL_STRESS_OK
