#!/usr/bin/env bash
# Emulator-only: layout previews + offline identity relaunch smoke. Not live Supabase.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ANDROID_HOME:-/home/ubuntu/android-sdk}/platform-tools/adb"
EMU="${ANDROID_HOME:-/home/ubuntu/android-sdk}/emulator/emulator"
ART="/opt/cursor/artifacts/ui/0.4"
PKG="tharmesh.app"
MAIN="$PKG/com.tharmesh.ui.main.MainActivity"
SEED="$PKG/com.tharmesh.ui.debug.OfflineRelaunchSmokeActivity"
mkdir -p "$ART"
SERIAL="${ANDROID_SERIAL:-}"

if ! "$ADB" devices | grep -q emulator; then
  AVD="$("$EMU" -list-avds | head -1)"
  if [ -z "$AVD" ]; then
    echo "No AVD; layout preview screenshots require a packaged APK only."
    exit 0
  fi
  "$EMU" -avd "$AVD" -no-window -no-audio -gpu swiftshader_indirect &
  for _ in $(seq 1 120); do
    "$ADB" wait-for-device shell getprop sys.boot_completed 2>/dev/null | grep -q 1 && break
    sleep 2
  done
fi

APK="$ROOT/app/build/outputs/apk/debug/tharmesh.app-debug.apk"
if [ ! -f "$APK" ]; then
  (cd "$ROOT" && ./gradlew assembleDebug -q)
fi
"$ADB" install -r "$APK" >/dev/null

grant_runtime_perms() {
  for perm in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION BLUETOOTH_CONNECT BLUETOOTH_SCAN BLUETOOTH_ADVERTISE POST_NOTIFICATIONS; do
    "$ADB" shell pm grant "$PKG" "android.permission.$perm" >/dev/null 2>&1 || true
  done
}
grant_runtime_perms

capture() {
  local screen="$1" palette="$2" night="$3" out="$4"
  "$ADB" shell am start -n "$PKG/com.tharmesh.ui.debug.LayoutPreviewActivity" \
    --es screen "$screen" --es palette "$palette" --ez night "$night" >/dev/null
  sleep 2
  "$ADB" exec-out screencap -p >"$ART/$out"
  echo "emulator: $out"
}

capture devices OCEAN false devices-ocean-light.png
capture devices ROSE true devices-rose-dark.png
capture rotate_recovery OCEAN false recovery-ocean-light.png
capture rotate_recovery ROSE true recovery-rose-dark.png
capture rotation_auth OCEAN false rotation-auth-ocean-light.png
capture rotation_auth ROSE true rotation-auth-rose-dark.png

# Offline relaunch (emulator): seed activated local identity, disable network, cold-start MainActivity.
"$ADB" shell am force-stop "$PKG"
"$ADB" shell am start -n "$SEED" >/dev/null
sleep 3
"$ADB" shell svc wifi disable >/dev/null 2>&1 || true
"$ADB" shell svc data disable >/dev/null 2>&1 || true
"$ADB" shell am force-stop "$PKG"
"$ADB" shell am start -n "$MAIN" >/dev/null
sleep 4
RESUMED="$("$ADB" shell dumpsys activity activities 2>/dev/null | tr -d '\r' | rg -m1 'mResumedActivity' || true)"
"$ADB" shell uiautomator dump /sdcard/offline-ui.xml >/dev/null 2>&1 || true
DUMP="$("$ADB" shell cat /sdcard/offline-ui.xml 2>/dev/null | tr -d '\r' || true)"
if echo "$RESUMED" | rg -q "LoginActivity|AccountFlowActivity"; then
  echo "emulator: offline relaunch FAILED — routed to sign-in/account flow"
  echo "$RESUMED"
  exit 1
fi
if ! echo "$RESUMED$DUMP" | rg -qi "MainActivity|Search chats|Chats"; then
  echo "emulator: offline relaunch FAILED — MainActivity/Chats not detected"
  echo "$RESUMED"
  exit 1
fi
"$ADB" exec-out screencap -p >"$ART/offline-relaunch-emulator.png"
echo "emulator: offline-relaunch-emulator.png (label: emulator, no cloud auth)"
echo "emulator: offline relaunch resumed=$RESUMED"
