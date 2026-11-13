#!/usr/bin/env bash
set -euo pipefail
export ANDROID_HOME="${ANDROID_SDK_ROOT}"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
PASS="${GATE_B_PASS:-1}"
export THARMESH_EVIDENCE_DIR="${THARMESH_EVIDENCE_DIR:-${GITHUB_WORKSPACE}/ci-evidence/gate-b-pass${PASS}}"
export THARMESH_STRICT_BOOT=1
export REBOOT_BOOT_MAX_SEC="${REBOOT_BOOT_MAX_SEC:-240}"
export SKIP_HEAD_ASSEMBLE=1
export EMULATOR_EVIDENCE_LOG="$THARMESH_EVIDENCE_DIR/adb-transcript.txt"
PKG="tharmesh.app"
ADB="$ANDROID_HOME/platform-tools/adb"
HEAD_APK="${GITHUB_WORKSPACE}/apk-artifacts/tharmesh-head-debug.apk"
mkdir -p "$THARMESH_EVIDENCE_DIR"
{
  echo "gate_b_pass=$PASS"
  echo "started_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "commit=${GITHUB_SHA:-unknown}"
  echo "tharmesh_evidence_dir=$THARMESH_EVIDENCE_DIR"
} | tee "$THARMESH_EVIDENCE_DIR/run-metadata.txt"
test -f "$HEAD_APK"
chmod +x scripts/verify-pinned-apk.sh
if [ -f "${GITHUB_WORKSPACE}/apk-artifacts/SHA256SUMS.txt" ]; then
  EXPECT_HEAD=$(awk '/tharmesh-head-debug\.apk$/ {print $1}' "${GITHUB_WORKSPACE}/apk-artifacts/SHA256SUMS.txt" | head -1)
  ACTUAL_HEAD=$(sha256sum "$HEAD_APK" | awk '{print $1}')
  echo "head_apk_file_sha256=$ACTUAL_HEAD expected_from_prepare=$EXPECT_HEAD" | tee -a "$THARMESH_EVIDENCE_DIR/run-metadata.txt"
  [ -z "$EXPECT_HEAD" ] || [ "$ACTUAL_HEAD" = "$EXPECT_HEAD" ] || { echo "FAIL head apk sha mismatch"; exit 1; }
fi
export THARMESH_VERIFY_CERT_ONLY=1
bash scripts/verify-pinned-apk.sh "$HEAD_APK" | tee -a "$THARMESH_EVIDENCE_DIR/run-metadata.txt"
export TM_APK="$HEAD_APK"
export HEAD_DEBUG_APK="$HEAD_APK"
echo "APP_DATA_RESET $(date -u +%Y-%m-%dT%H:%M:%SZ) pass=$PASS reason=pre-suite-isolation" \
  | tee -a "$THARMESH_EVIDENCE_DIR/data-reset.log"
"$ADB" shell am force-stop "$PKG" >>"$EMULATOR_EVIDENCE_LOG" 2>&1 || true
"$ADB" shell pm clear "$PKG" >>"$EMULATOR_EVIDENCE_LOG" 2>&1
bash scripts/emulator-0.7-evidence.sh
grep -q 'RESULT=PASS' "$EMULATOR_EVIDENCE_LOG"
