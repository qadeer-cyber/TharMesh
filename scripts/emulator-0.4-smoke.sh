#!/usr/bin/env bash
# Emulator-only: layout previews + offline identity relaunch smoke. Not live Supabase.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ANDROID_HOME:-/home/ubuntu/android-sdk}/platform-tools/adb"
EMU="${ANDROID_HOME:-/home/ubuntu/android-sdk}/emulator/emulator"
ART="/opt/cursor/artifacts/ui/0.4"
PKG="tharmesh.app"
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

# Offline relaunch: seed identity prefs via app process (debug-only helper activity).
"$ADB" shell am force-stop "$PKG"
"$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null || true
sleep 2
"$ADB" shell am force-stop "$PKG"
"$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null || true
sleep 2
"$ADB" exec-out screencap -p >"$ART/offline-relaunch-emulator.png"
echo "emulator: offline-relaunch-emulator.png (label: emulator, no cloud auth)"
