#!/usr/bin/env bash
# Uninstall + reinstall debug APK for Cat 6 Gate B / 3-pass isolation (documented clean state).
set -euo pipefail
PKG="${THARMESH_PKG:-tharmesh.app}"
ADB="${CAT6_RESET_ADB:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}/platform-tools/adb}"
REASON="${1:?reset reason}"
APK="${2:?apk path}"
LOG="${3:-/dev/stdout}"
PASS_LABEL="${CAT6_RESET_PASS_LABEL:-}"
TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
LINE="APP_DATA_RESET $TS reason=$REASON method=uninstall+install-reinstall"
[ -n "$PASS_LABEL" ] && LINE="$LINE pass=$PASS_LABEL"
echo "$LINE" | tee -a "$LOG" >/dev/null
echo "$LINE"
test -f "$APK" || { echo "FAIL reset: APK missing at $APK" | tee -a "$LOG"; exit 1; }
"$ADB" shell am force-stop "$PKG" >>"$LOG" 2>&1 || true
"$ADB" uninstall "$PKG" >>"$LOG" 2>&1 || true
"$ADB" install -r -d "$APK" >>"$LOG" 2>&1
