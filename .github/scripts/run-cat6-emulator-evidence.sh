#!/usr/bin/env bash
# Invoked inside reactivecircus/android-emulator-runner (one process — no per-line sh -c).
set -euo pipefail
export ANDROID_HOME="${ANDROID_SDK_ROOT}"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
export THARMESH_EVIDENCE_DIR="${THARMESH_EVIDENCE_DIR:-${GITHUB_WORKSPACE}/ci-evidence}"
export LEGACY_SOURCE_COMMIT="${LEGACY_SOURCE_COMMIT:-9fedae917868340e9b2b93f46b847a6119c576bd}"
export SKIP_HEAD_ASSEMBLE=1
export THARMESH_KEEP_EMULATOR_ALIVE=1
V6_APK="${V6_APK:-${GITHUB_WORKSPACE}/apk-artifacts/v6-room6-debug.apk}"
NEW_APK="${GITHUB_WORKSPACE}/apk-artifacts/tharmesh-head-debug.apk"
PKG="tharmesh.app"
ADB="${ANDROID_HOME}/platform-tools/adb"
EVIDENCE_LOG="${THARMESH_EVIDENCE_DIR}/cat6-orchestration.log"
chmod +x scripts/cat6-reset-clean-app.sh
: >"$EVIDENCE_LOG"
log() { echo "$@" | tee -a "$EVIDENCE_LOG"; }

test -f "$V6_APK"
test -f "$NEW_APK"
export HEAD_DEBUG_APK="$NEW_APK"
export TM_APK="$NEW_APK"

log "=== Gate A: legacy v6 debug-keystore in-place upgrade ==="
bash scripts/debug-keystore-upgrade-evidence.sh "$V6_APK"

GATE_A_DIR="${THARMESH_EVIDENCE_DIR}/gate-a-legacy-upgrade"
mkdir -p "$GATE_A_DIR"
cp -a "${THARMESH_EVIDENCE_DIR}/debug-keystore-upgrade/." "$GATE_A_DIR/"
grep -q 'RESULT=PASS debug-keystore legacy schema upgrade' "$GATE_A_DIR/upgrade-transcript.txt" \
  || { log "FAIL Gate A: upgrade transcript not PASS"; exit 1; }
{
  echo "gate_a_verdict=PASS"
  echo "archived_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "snapshots=${GATE_A_DIR}/db-snapshot-before ${GATE_A_DIR}/db-snapshot-after"
} | tee "${GATE_A_DIR}/gate-a-verdict.txt"
log "Gate A: PASS (artifacts under gate-a-legacy-upgrade/; verdict archived)"

export CAT6_RESET_ADB="$ADB"
CAT6_RESET_ADB="$ADB" bash scripts/cat6-reset-clean-app.sh "post-gate-a-before-persistence-3pass" "$NEW_APK" \
  "${THARMESH_EVIDENCE_DIR}/data-reset.log" | tee -a "$EVIDENCE_LOG"
bash scripts/emulator-0.7-evidence-3pass.sh
