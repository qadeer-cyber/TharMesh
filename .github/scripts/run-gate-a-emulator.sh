#!/usr/bin/env bash
set -euo pipefail
export ANDROID_HOME="${ANDROID_SDK_ROOT}"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
export THARMESH_EVIDENCE_DIR="${THARMESH_EVIDENCE_DIR:-${GITHUB_WORKSPACE}/ci-evidence/gate-a}"
export LEGACY_SOURCE_COMMIT="${LEGACY_SOURCE_COMMIT:-9fedae917868340e9b2b93f46b847a6119c576bd}"
export SKIP_HEAD_ASSEMBLE=1
V6_APK="${V6_APK:-${GITHUB_WORKSPACE}/apk-artifacts/v6-room6-debug.apk}"
NEW_APK="${GITHUB_WORKSPACE}/apk-artifacts/tharmesh-head-debug.apk"
mkdir -p "$THARMESH_EVIDENCE_DIR"
bash scripts/verify-pinned-apk.sh "$V6_APK"
test -f "$NEW_APK"
bash scripts/debug-keystore-upgrade-evidence.sh "$V6_APK"
grep -q 'RESULT=PASS debug-keystore legacy schema upgrade' \
  "$THARMESH_EVIDENCE_DIR/debug-keystore-upgrade/upgrade-transcript.txt"
