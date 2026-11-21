#!/usr/bin/env bash
# Install head debug APK on a booted emulator and capture API-level permission/FGS evidence.
set -euo pipefail
export ANDROID_HOME="${ANDROID_SDK_ROOT}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
API="${ANDROID_API_LEVEL:-unknown}"
EVIDENCE="${THARMESH_EVIDENCE_DIR:-ci-evidence/api-${API}}"
APK="${GITHUB_WORKSPACE:-$(cd "$(dirname "$0")/.." && pwd)}/apk-artifacts/tharmesh-head-debug.apk"
PKG="tharmesh.app"
ADB="$ANDROID_HOME/platform-tools/adb"
SDK="$ANDROID_HOME"
APKSIGNER="$(ls -d "$SDK/build-tools/"*/apksigner 2>/dev/null | sort -V | tail -1)"
AAPT="$(ls -d "$SDK/build-tools/"*/aapt 2>/dev/null | sort -V | tail -1)"
mkdir -p "$EVIDENCE"
chmod +x scripts/verify-pinned-apk.sh 2>/dev/null || true
test -f "$APK"
{
  echo "api_level=$API"
  echo "started_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "commit=${GITHUB_SHA:-local}"
} | tee "$EVIDENCE/run-metadata.txt"
SUMS="$(dirname "$APK")/SHA256SUMS.txt"
test -f "$SUMS" || { echo "FAIL missing $SUMS (head APK provenance unverifiable)"; exit 1; }
EXPECT_HEAD=$(awk '/tharmesh-head-debug\.apk$/ {print $1}' "$SUMS" | head -1)
[ -n "$EXPECT_HEAD" ] || { echo "FAIL no head APK digest in SHA256SUMS.txt"; exit 1; }
bash scripts/verify-pinned-apk.sh "$APK" "expected:$EXPECT_HEAD" | tee -a "$EVIDENCE/run-metadata.txt"
"$AAPT" dump permissions "$APK" | tee "$EVIDENCE/aapt-permissions.txt"
grep -E 'POST_NOTIFICATIONS|NEARBY_WIFI|FOREGROUND_SERVICE_CONNECTED_DEVICE' "$EVIDENCE/aapt-permissions.txt" | tee "$EVIDENCE/permission-spotcheck.txt"
"$ADB" wait-for-device
DEVICE_SDK="$("$ADB" shell getprop ro.build.version.sdk | tr -d '\r')"
echo "$DEVICE_SDK" | tee "$EVIDENCE/device-sdk.txt"
test "$DEVICE_SDK" = "$API" || {
  echo "FAIL emulator SDK=$DEVICE_SDK expected=$API"
  exit 1
}
"$ADB" install -r -g "$APK" | tee "$EVIDENCE/adb-install.txt"
"$ADB" shell dumpsys package "$PKG" | grep -E 'permission|granted=true' | head -80 | tee "$EVIDENCE/dumpsys-package-head.txt"
echo "RESULT=PASS api_emulator_level=${API} scope=install+permission-declaration" | tee "$EVIDENCE/verdict.txt"
echo "note=this gate proves install and permission declaration on API ${API}; it does NOT exercise mesh messaging" | tee -a "$EVIDENCE/verdict.txt"
