#!/usr/bin/env bash
# Invoked inside reactivecircus/android-emulator-runner (one process — no per-line sh -c).
set -euo pipefail
export ANDROID_HOME="${ANDROID_SDK_ROOT}"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
export THARMESH_EVIDENCE_DIR="${THARMESH_EVIDENCE_DIR:-${GITHUB_WORKSPACE}/ci-evidence}"
export LEGACY_SOURCE_COMMIT="${LEGACY_SOURCE_COMMIT:-9fedae917868340e9b2b93f46b847a6119c576bd}"
export SKIP_HEAD_ASSEMBLE=1
V6_APK="${V6_APK:-${THARMESH_EVIDENCE_DIR}/v6-room6-debug.apk}"
NEW_APK="${GITHUB_WORKSPACE}/app/build/outputs/apk/debug/tharmesh.app-debug.apk"
PKG="tharmesh.app"
ADB="${ANDROID_HOME}/platform-tools/adb"
EVIDENCE_LOG="${THARMESH_EVIDENCE_DIR}/cat6-orchestration.log"
: >"$EVIDENCE_LOG"
log() { echo "$@" | tee -a "$EVIDENCE_LOG"; }

reset_app_data() {
  local reason="$1"
  log "APP_DATA_RESET $(date -u +%Y-%m-%dT%H:%M:%SZ) reason=$reason"
  "$ADB" shell am force-stop "$PKG" >>"$EVIDENCE_LOG" 2>&1 || true
  "$ADB" shell pm clear "$PKG" >>"$EVIDENCE_LOG" 2>&1
}

test -f "$V6_APK"
test -f "$NEW_APK"

log "=== Gate A: legacy v6 debug-keystore in-place upgrade ==="
bash scripts/debug-keystore-upgrade-evidence.sh "$V6_APK"

GATE_A_DIR="${THARMESH_EVIDENCE_DIR}/gate-a-legacy-upgrade"
mkdir -p "$GATE_A_DIR"
cp -a "${THARMESH_EVIDENCE_DIR}/debug-keystore-upgrade/." "$GATE_A_DIR/"
grep -q 'RESULT=PASS debug-keystore legacy schema upgrade' "$GATE_A_DIR/upgrade-transcript.txt" \
  || { log "FAIL Gate A: upgrade transcript not PASS"; exit 1; }
log "Gate A: PASS (artifacts under gate-a-legacy-upgrade/)"

reset_app_data "post-gate-a-before-persistence-3pass"
bash scripts/emulator-0.7-evidence-3pass.sh
