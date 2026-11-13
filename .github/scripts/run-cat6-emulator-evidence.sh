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
test -f "$V6_APK"
test -f "$NEW_APK"
bash scripts/debug-keystore-upgrade-evidence.sh "$V6_APK"
bash scripts/emulator-0.7-evidence-3pass.sh
