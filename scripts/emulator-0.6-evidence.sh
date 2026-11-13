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
UI_DUMP="$ART/ui-dump-latest.xml"
: >"$LOG"
FAILED=0

log() { echo "$@" | tee -a "$LOG"; }

log_fatal() {
  log "--- logcat (FATAL / AndroidRuntime) ---"
  "$ADB" logcat -d -t 400 2>/dev/null | grep -E 'FATAL|AndroidRuntime' | tail -40 | tee -a "$LOG" || true
}

log_activity_top() {
  log "--- dumpsys activity top ---"
  "$ADB" shell dumpsys activity activities 2>/dev/null | grep -E 'mResumedActivity|Hist #0' | head -6 | tee -a "$LOG" || true
}

ensure_emulator() {
  if ! "$ADB" devices | grep -q emulator; then
    AVD="$("$EMU" -list-avds | head -1)"
    test -n "$AVD"
    log "starting emulator AVD=$AVD (-no-window swiftshader)"
    "$EMU" -avd "$AVD" -no-window -no-audio -gpu swiftshader_indirect &
    for _ in $(seq 1 120); do
      "$ADB" wait-for-device shell getprop sys.boot_completed 2>/dev/null | grep -q 1 && break
      sleep 2
    done
  fi
}

ensure_emulator

API_LEVEL="$("$ADB" shell getprop ro.build.version.sdk | tr -d '\r')"
API_RELEASE="$("$ADB" shell getprop ro.build.version.release | tr -d '\r')"
AVD_NAME="$("$EMU" -list-avds | head -1)"
log "emulator AVD=$AVD_NAME API=$API_LEVEL (Android $API_RELEASE)"
log "note: only tm_api30 available on this VM; no API 33+ image to run second pass"

APK="$ROOT/app/build/outputs/apk/debug/tharmesh.app-debug.apk"
(cd "$ROOT" && ./gradlew assembleDebug -q)
"$ADB" install -r "$APK" >>"$LOG" 2>&1

"$ADB" shell settings put global window_animation_scale 0 >>"$LOG" 2>&1 || true
"$ADB" shell settings put global transition_animation_scale 0 >>"$LOG" 2>&1 || true
"$ADB" shell input keyevent KEYCODE_WAKEUP >>"$LOG" 2>&1 || true

grant_runtime_perms() {
  for perm in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION; do
    "$ADB" shell pm grant "$PKG" "android.permission.$perm" >>"$LOG" 2>&1 || true
  done
  if [ "${API_LEVEL:-0}" -ge 31 ]; then
    for perm in BLUETOOTH_CONNECT BLUETOOTH_SCAN BLUETOOTH_ADVERTISE; do
      "$ADB" shell pm grant "$PKG" "android.permission.$perm" >>"$LOG" 2>&1 || true
    done
  else
    log "skipped BLUETOOTH_* grants (API $API_LEVEL < 31)"
  fi
  if [ "${API_LEVEL:-0}" -ge 33 ]; then
    "$ADB" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >>"$LOG" 2>&1 || true
  else
    log "skipped POST_NOTIFICATIONS grant (API $API_LEVEL < 33)"
  fi
}
grant_runtime_perms

ui_dump_pull() {
  "$ADB" shell uiautomator dump /sdcard/ui-evidence.xml >>"$LOG" 2>&1
  "$ADB" pull /sdcard/ui-evidence.xml "$UI_DUMP" >>"$LOG" 2>&1
}

wait_for_ui() {
  local needle="$1"
  local label="${2:-$needle}"
  local timeout="${3:-90}"
  local i
  for i in $(seq 1 "$timeout"); do
    ui_dump_pull
    if grep -qF "$needle" "$UI_DUMP" 2>/dev/null; then
      log "ui ready ($label): found after ${i}s"
      return 0
    fi
    sleep 1
  done
  log "FAIL ui wait: '$label' not found in ${timeout}s"
  log_activity_top
  log_fatal
  return 1
}

assert_png_not_blank() {
  local f="$1"
  local min="${2:-45000}"
  local sz
  sz=$(stat -c%s "$f" 2>/dev/null || echo 0)
  if [ "$sz" -lt "$min" ]; then
    log "FAIL screenshot likely blank: $f ($sz bytes < $min)"
    return 1
  fi
  log "screenshot size ok: $f ($sz bytes)"
  return 0
}

shot_after_ui() {
  local name="$1"
  local needle="$2"
  local label="$3"
  if ! wait_for_ui "$needle" "$label"; then
    FAILED=1
    "$ADB" exec-out screencap -p >"$ART/$name" || true
    return 1
  fi
  sleep 1
  "$ADB" shell input keyevent KEYCODE_WAKEUP >>"$LOG" 2>&1 || true
  "$ADB" exec-out screencap -p >"$ART/$name"
  log "screenshot: $ART/$name"
  if ! assert_png_not_blank "$ART/$name"; then
    FAILED=1
    return 1
  fi
  cp -f "$UI_DUMP" "$ART/${name%.png}-ui.xml"
  return 0
}

db_query() {
  local sql="$1"
  local dir="/tmp/tharmesh-emulator-db"
  mkdir -p "$dir"
  for f in tharmesh.db tharmesh.db-wal tharmesh.db-shm; do
    "$ADB" exec-out run-as "$PKG" cat "databases/$f" >"$dir/$f" 2>/dev/null || touch "$dir/$f"
  done
  sqlite3 "$dir/tharmesh.db" "$sql" 2>/dev/null || echo "query_failed"
}

# 1) Fake activation path (no Google/OTP)
log "=== fake provisioning ==="
"$ADB" logcat -c >>"$LOG" 2>&1 || true
"$ADB" shell am force-stop "$PKG"
"$ADB" shell am start -W -n "$PKG/com.tharmesh.ui.debug.FakeProvisionSmokeActivity" >>"$LOG" 2>&1
shot_after_ui "01-fake-provision-account-flow.png" "Account security" "account_flow_create" 60 || true
log_activity_top

# 2) Kill mid-flow, relaunch account flow
log "=== kill during provisioning ==="
"$ADB" shell am force-stop "$PKG"
"$ADB" shell am start -W -n "$PKG/com.tharmesh.ui.debug.ProvisionRelaunchSmokeActivity" >>"$LOG" 2>&1
shot_after_ui "02-provision-relaunch.png" "Account security" "account_flow_relaunch" 60 || true

# 3) Staged recovery + process death, then resume preview
log "=== recovery stage death ==="
"$ADB" shell am force-stop "$PKG"
"$ADB" shell am start -W -n "$PKG/com.tharmesh.ui.debug.RecoveryStageDeathSmokeActivity" >>"$LOG" 2>&1
STAGE=$("$ADB" shell run-as "$PKG" ls shared_prefs 2>/dev/null | grep tharmesh_recovery_rotation_stage || true)
log "staged prefs after death: $STAGE"
"$ADB" shell am start -W -n "$PKG/com.tharmesh.ui.debug.LayoutPreviewActivity" --es screen rotate_recovery >>"$LOG" 2>&1
shot_after_ui "03-recovery-rotate-after-death.png" "Rotate recovery key" "rotate_recovery_preview" 45 || true

# 4) Offline Room persistence
log "=== offline room seed ==="
"$ADB" shell am force-stop "$PKG"
"$ADB" shell am start -W -n "$PKG/com.tharmesh.ui.debug.OfflineRoomPersistenceSmokeActivity" >>"$LOG" 2>&1
MSG_COUNT=$(db_query "select count(*) from messages where body like 'OFFLINE_PERSISTENCE_SEED_%';")
CONTACT_COUNT=$(db_query "select count(*) from contacts;")
VERIFIED_FP=$(db_query "select count(*) from peer_identity where verified=1;")
log "db pre-kill: messages=$MSG_COUNT contacts=$CONTACT_COUNT verified_peer_identity=$VERIFIED_FP"
if [ "$MSG_COUNT" != "3" ] || [ "$CONTACT_COUNT" != "2" ] || [ "$VERIFIED_FP" != "2" ]; then
  log "FAIL db seed counts (expected messages=3 contacts=2 verified=2)"
  FAILED=1
fi
shot_after_ui "04-main-after-room-seed.png" "Evidence Peer A" "chats_after_seed" || true

log "=== offline force-stop relaunch ==="
"$ADB" shell svc wifi disable >>"$LOG" 2>&1 || true
"$ADB" shell svc data disable >>"$LOG" 2>&1 || true
"$ADB" shell am force-stop "$PKG"
"$ADB" shell am start -W -n "$PKG/com.tharmesh.ui.main.MainActivity" >>"$LOG" 2>&1
MSG_AFTER=$(db_query "select count(*) from messages where body like 'OFFLINE_PERSISTENCE_SEED_%';")
log "db after relaunch: messages=$MSG_AFTER"
if [ "$MSG_AFTER" != "3" ]; then
  log "FAIL message count after relaunch (expected 3)"
  FAILED=1
fi
if ! wait_for_ui "Evidence Peer A" "chats_offline_relaunch"; then
  FAILED=1
fi
if ! wait_for_ui "OFFLINE_PERSISTENCE_SEED_A2" "chat_preview_line"; then
  FAILED=1
fi
shot_after_ui "05-offline-relaunch-main.png" "Evidence Peer A" "chats_offline_screenshot" 30 || true
cp -f "$UI_DUMP" "$ART/05-offline-relaunch-ui.xml"

log "emulator-0.6-evidence: finished (artifacts in $ART)"
if [ "$FAILED" -ne 0 ]; then
  log "emulator-0.6-evidence: **FAILED** (see transcript)"
  exit 1
fi
