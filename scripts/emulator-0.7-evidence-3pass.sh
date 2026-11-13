#!/usr/bin/env bash
# Run emulator-0.7-evidence.sh three times with separate transcripts (supervisor gate).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ART="${THARMESH_EVIDENCE_DIR:-/opt/cursor/artifacts/ui/0.7-evidence}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/home/ubuntu/android-sdk}}"
ADB="$SDK/platform-tools/adb"
EMU="$SDK/emulator/emulator"
mkdir -p "$ART"

pass_count=0
for run in 1 2 3; do
  export EMULATOR_EVIDENCE_LOG="$ART/adb-transcript-run${run}.txt"
  : >"$EMULATOR_EVIDENCE_LOG"
  echo "=== emulator evidence run $run/3 ===" | tee -a "$ART/emulator-3pass-summary.txt"
  if ! "$ADB" devices | grep -qE 'emulator-[0-9]+\s+device'; then
    pkill -f "qemu-system" 2>/dev/null || true
    "$ADB" emu kill 2>/dev/null || true
    sleep 3
    AVD="$("$EMU" -list-avds 2>/dev/null | head -1)"
    test -n "$AVD"
    "$EMU" -avd "$AVD" -no-window -no-audio -gpu swiftshader_indirect -no-boot-anim &
    for _ in $(seq 1 120); do
      "$ADB" wait-for-device shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' | grep -q 1 && break
      sleep 2
    done
    for _ in $(seq 1 60); do
      b=$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
      a=$("$ADB" shell getprop init.svc.bootanim 2>/dev/null | tr -d '\r')
      [ "$b" = "1" ] && { [ "$a" = "stopped" ] || [ -z "$a" ]; } && break
      sleep 2
    done
  fi
  if bash "$ROOT/scripts/emulator-0.7-evidence.sh"; then
    echo "run${run}=PASS" | tee -a "$ART/emulator-3pass-summary.txt"
    pass_count=$((pass_count + 1))
    cp -f "$EMULATOR_EVIDENCE_LOG" "$ART/adb-transcript-run${run}-final.txt"
  else
    echo "run${run}=FAIL" | tee -a "$ART/emulator-3pass-summary.txt"
    tail -20 "$EMULATOR_EVIDENCE_LOG" | tee -a "$ART/emulator-3pass-summary.txt"
    echo "RESULT=FAIL (stopped at run $run; need 3 consecutive PASS)" | tee -a "$ART/emulator-3pass-summary.txt"
    exit 1
  fi
  if [ "$run" -lt 3 ]; then
    "$ADB" emu kill >>"$EMULATOR_EVIDENCE_LOG" 2>&1 || true
    sleep 5
  fi
done

echo "RESULT=PASS (3 consecutive full runs)" | tee -a "$ART/emulator-3pass-summary.txt"
exit 0
