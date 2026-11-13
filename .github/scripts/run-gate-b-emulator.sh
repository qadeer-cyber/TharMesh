#!/usr/bin/env bash
set -euo pipefail
export ANDROID_HOME="${ANDROID_SDK_ROOT}"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
PASS="${GATE_B_PASS:-1}"
export THERMESH_EVIDENCE_DIR="${THERMESH_EVIDENCE_DIR:-${GITHUB_WORKSPACE}/ci-evidence/gate-b-pass${PASS}}"
export THARMESH_STRICT_BOOT=1
export REBOOT_BOOT_MAX_SEC="${REBOOT_BOOT_MAX_SEC:-240}"
export EMULATOR_EVIDENCE_LOG="$THARMESH_EVIDENCE_DIR/adb-transcript.txt"
PKG="tharmesh.app"
ADB="$ANDROID_HOME/platform-tools/adb"
HEAD_APK="${GITHUB_WORKSPACE}/apk-artifacts/tharmesh-head-debug.apk"
mkdir -p "$THARMESH_EVIDENCE_DIR"
{
  echo "gate_b_pass=$PASS"
  echo "started_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "commit=${GITHUB_SHA:-unknown}"
} | tee "$THARMESH_EVIDENCE_DIR/run-metadata.txt"
test -f "$HEAD_APK"
"$ADB" install -r -d "$HEAD_APK" >>"$EMULATOR_EVIDENCE_LOG" 2>&1
echo "APP_DATA_RESET $(date -u +%Y-%m-%dT%H:%M:%SZ) pass=$PASS reason=pre-suite-isolation" \
  | tee -a "$THARMESH_EVIDENCE_DIR/data-reset.log"
"$ADB" shell am force-stop "$PKG"
"$ADB" shell pm clear "$PKG" >>"$EMULATOR_EVIDENCE_LOG" 2>&1
"$ADB" install -r -d "$HEAD_APK" >>"$EMULATOR_EVIDENCE_LOG" 2>&1
bash scripts/emulator-0.7-evidence.sh
grep -q 'RESULT=PASS' "$EMULATOR_EVIDENCE_LOG"
