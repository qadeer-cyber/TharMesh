#!/usr/bin/env bash
# 0.6 emulator evidence: activation fakes, kill/relaunch, recovery stage, offline Room seed.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ANDROID_HOME:-/home/ubuntu/android-sdk}/platform-tools/adb"
EMU="${ANDROID_HOME:-/home/ubuntu/android-sdk}/emulator/emulator"
ART="/opt/cursor/artifacts/ui/0.6-evidence"
PKG="tharmesh.app"
mkdir -p "$ART"
LOG="$ART/adb-transcript.txt"
: >"$LOG"

log() { echo "$@" | tee -a "$LOG"; }

if ! "$ADB" devices | grep -q emulator; then
  AVD="$("$EMU" -list-avds | head -1)"
  test -n "$AVD"
  "$EMU" -avd "$AVD" -no-window -no-audio -gpu swiftshader_indirect &
  for _ in $(seq 1 120); do
    "$ADB" wait-for-device shell getprop sys.boot_completed 2>/dev/null | grep -q 1 && break
    sleep 2
  done
fi

APK="$ROOT/app/build/outputs/apk/debug/tharmesh.app-debug.apk"
(cd "$ROOT" && ./gradlew assembleDebug -q)
"$ADB" install -r "$APK" >>"$LOG" 2>&1

grant_runtime_perms() {
  for perm in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION BLUETOOTH_CONNECT BLUETOOTH_SCAN BLUETOOTH_ADVERTISE POST_NOTIFICATIONS; do
    "$ADB" shell pm grant "$PKG" "android.permission.$perm" >>"$LOG" 2>&1 || true
  done
}
grant_runtime_perms

shot() {
  local name="$1"
  sleep 2
  "$ADB" exec-out screencap -p >"$ART/$name"
  log "screenshot: $ART/$name"
}

# 1) Fake activation path (no Google/OTP)
log "=== fake provisioning ==="
"$ADB" shell am force-stop "$PKG"
"$ADB" shell am start -n "$PKG/com.tharmesh.ui.debug.FakeProvisionSmokeActivity" >>"$LOG" 2>&1
sleep 3
shot "01-fake-provision-account-flow.png"

# 2) Kill mid-flow, relaunch account flow
log "=== kill during provisioning ==="
"$ADB" shell am force-stop "$PKG"
"$ADB" shell am start -n "$PKG/com.tharmesh.ui.debug.ProvisionRelaunchSmokeActivity" >>"$LOG" 2>&1
sleep 2
shot "02-provision-relaunch.png"

# 3) Staged recovery + process death, then resume preview
log "=== recovery stage death ==="
"$ADB" shell am force-stop "$PKG"
"$ADB" shell am start -n "$PKG/com.tharmesh.ui.debug.RecoveryStageDeathSmokeActivity" >>"$LOG" 2>&1
sleep 1
STAGE=$("$ADB" shell run-as "$PKG" ls shared_prefs 2>/dev/null | grep tharmesh_recovery_rotation_stage || true)
log "staged prefs after death: $STAGE"
"$ADB" shell am start -n "$PKG/com.tharmesh.ui.debug.LayoutPreviewActivity" --es screen rotate_recovery >>"$LOG" 2>&1
sleep 2
shot "03-recovery-rotate-after-death.png"

# 4) Offline Room persistence
log "=== offline room seed ==="
"$ADB" shell am force-stop "$PKG"
"$ADB" shell am start -n "$PKG/com.tharmesh.ui.debug.OfflineRoomPersistenceSmokeActivity" >>"$LOG" 2>&1
sleep 3
db_query() {
  local sql="$1"
  local dir="/tmp/tharmesh-emulator-db"
  mkdir -p "$dir"
  for f in tharmesh.db tharmesh.db-wal tharmesh.db-shm; do
    "$ADB" exec-out run-as "$PKG" cat "databases/$f" >"$dir/$f" 2>/dev/null || touch "$dir/$f"
  done
  sqlite3 "$dir/tharmesh.db" "$sql" 2>/dev/null || echo "query_failed"
}
MSG_COUNT=$(db_query "select count(*) from messages where body='OFFLINE_PERSISTENCE_SEED';")
log "messages seeded (pre-kill): $MSG_COUNT"
shot "04-main-after-room-seed.png"

log "=== offline force-stop relaunch ==="
"$ADB" shell svc wifi disable >>"$LOG" 2>&1 || true
"$ADB" shell svc data disable >>"$LOG" 2>&1 || true
"$ADB" shell am force-stop "$PKG"
"$ADB" shell am start -n "$PKG/com.tharmesh.ui.main.MainActivity" >>"$LOG" 2>&1
sleep 3
MSG_AFTER=$(db_query "select count(*) from messages where body='OFFLINE_PERSISTENCE_SEED';")
log "messages after relaunch offline: $MSG_AFTER"
shot "05-offline-relaunch-main.png"

log "emulator-0.6-evidence: done (artifacts in $ART)"
