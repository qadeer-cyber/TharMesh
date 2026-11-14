#!/usr/bin/env bash
set -euo pipefail
export ANDROID_HOME="${ANDROID_SDK_ROOT}"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
export THARMESH_EVIDENCE_DIR="${THARMESH_EVIDENCE_DIR:-${GITHUB_WORKSPACE}/ci-evidence/gate-a}"
export LEGACY_SOURCE_COMMIT="${LEGACY_SOURCE_COMMIT:-9fedae917868340e9b2b93f46b847a6119c576bd}"
export SKIP_HEAD_ASSEMBLE=1
V6_APK="${V6_APK:-${GITHUB_WORKSPACE}/apk-artifacts/v6-room6-debug.apk}"
NEW_APK="${GITHUB_WORKSPACE}/apk-artifacts/tharmesh-head-debug.apk"
export HEAD_DEBUG_APK="$NEW_APK"
export NEW_APK
mkdir -p "$THARMESH_EVIDENCE_DIR"
bash scripts/verify-pinned-apk.sh "$V6_APK"
test -f "$NEW_APK"
bash scripts/debug-keystore-upgrade-evidence.sh "$V6_APK"
UPGRADE_DIR="${THARMESH_EVIDENCE_DIR}/debug-keystore-upgrade"
grep -q 'RESULT=PASS debug-keystore legacy schema upgrade' \
  "$UPGRADE_DIR/upgrade-transcript.txt"
ARCHIVE="${THARMESH_EVIDENCE_DIR}/gate-a-legacy-archive"
mkdir -p "$ARCHIVE"
cp -a "${UPGRADE_DIR}/." "$ARCHIVE/"
{
  echo "gate_a_verdict=PASS"
  echo "archived_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "commit=${GITHUB_SHA:-unknown}"
  echo "upgrade_transcript=${ARCHIVE}/upgrade-transcript.txt"
  echo "snapshots_before=${ARCHIVE}/db-snapshot-before"
  echo "snapshots_after=${ARCHIVE}/db-snapshot-after"
} | tee "${THARMESH_EVIDENCE_DIR}/gate-a-verdict.txt"
