#!/usr/bin/env bash
# Category 6 — FAIL-CLOSED emulator evidence (TharMesh Update 0.7 Phase 0).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/home/ubuntu/android-sdk}}"
ADB="$SDK/platform-tools/adb"
EMU="$SDK/emulator/emulator"
ART="${THARMESH_EVIDENCE_DIR:-/opt/cursor/artifacts/ui/0.7-evidence}"
PKG="tharmesh.app"
mkdir -p "$ART"
LOG="${EMULATOR_EVIDENCE_LOG:-$ART/adb-transcript.txt}"
UI_DUMP="$ART/ui-dump-latest.xml"
if [ -z "${EMULATOR_KEEP_LOG:-}" ]; then
  : >"$LOG"
fi
FAILED=0
REBOOT_UI_MAX_ATTEMPTS="${REBOOT_UI_MAX_ATTEMPTS:-6}"

log() { echo "$@" | tee -a "$LOG"; }

fail() {
  log "FAIL: $*"
  FAILED=1
}

assert_not_query_failed() {
  local label="$1"
  local value="$2"
  if [ "$value" = "query_failed" ] || [ -z "$value" ]; then
    fail "$label: db query_failed"
    return 1
  fi
}

log_fatal() {
  if "$ADB" logcat -d -t 500 2>/dev/null | grep -E 'FATAL EXCEPTION' | grep -qE 'tharmesh|tharmesh\.app'; then
    fail "logcat tharmesh FATAL"
    "$ADB" logcat -d -t 200 2>/dev/null | grep -E 'FATAL|AndroidRuntime' | grep tharmesh | tail -25 | tee -a "$LOG"
    return 1
  fi
  log "logcat: no tharmesh FATAL"
}

log_activity_top() {
  "$ADB" shell dumpsys activity activities 2>/dev/null | grep -E 'mResumedActivity' | head -3 | tee -a "$LOG" || true
}

ensure_emulator() {
  if ! "$ADB" devices | grep -q emulator; then
    AVD="$("$EMU" -list-avds | head -1)"
    test -n "$AVD"
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
BUILD_SHA="$(cd "$ROOT" && git rev-parse HEAD)"
APK="$ROOT/app/build/outputs/apk/debug/tharmesh.app-debug.apk"

log "=== emulator metadata ==="
log "AVD=$AVD_NAME API=$API_LEVEL (Android $API_RELEASE)"
log "source_commit=$BUILD_SHA"
if [ "${API_LEVEL:-0}" -lt 33 ]; then
  log "API 33+ image: NOT EXECUTED (only $AVD_NAME installed)"
fi

(cd "$ROOT" && ./gradlew assembleDebug -q)
SHA256=$(sha256sum "$APK" | awk '{print $1}')
APK_SIZE=$(stat -c%s "$APK")
log "APK sha256=$SHA256 size_bytes=$APK_SIZE"

"$ADB" install -r "$APK" >>"$LOG" 2>&1
"$ADB" shell settings put global window_animation_scale 0 >>"$LOG" 2>&1 || true
"$ADB" shell input keyevent KEYCODE_WAKEUP >>"$LOG" 2>&1 || true

for perm in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION; do
  "$ADB" shell pm grant "$PKG" "android.permission.$perm" >>"$LOG" 2>&1 || true
done
if [ "${API_LEVEL:-0}" -ge 31 ]; then
  for perm in BLUETOOTH_CONNECT BLUETOOTH_SCAN BLUETOOTH_ADVERTISE; do
    "$ADB" shell pm grant "$PKG" "android.permission.$perm" >>"$LOG" 2>&1 || true
  done
else
  log "skipped BLUETOOTH_* (API $API_LEVEL < 31)"
fi

ui_dump_pull() {
  "$ADB" shell uiautomator dump /sdcard/ui-evidence.xml >>"$LOG" 2>&1
  "$ADB" pull /sdcard/ui-evidence.xml "$UI_DUMP" >>"$LOG" 2>&1
}

dismiss_system_anr() {
  "$ADB" shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >>"$LOG" 2>&1 || true
  ui_dump_pull
  if grep -qE "Process system isn't responding|System UI isn't responding" "$UI_DUMP" 2>/dev/null; then
    log "dismissing system ANR dialog (uiautomator Wait)"
    if grep -qF 'text="Wait"' "$UI_DUMP" 2>/dev/null; then
      local bounds
      bounds=$(grep -oE 'text="Wait"[^/]*bounds="[^"]+"' "$UI_DUMP" | head -1 | sed -n 's/.*bounds="\([^"]*\)".*/\1/p')
      if [ -n "$bounds" ]; then
        local x1 y1 x2 y2 cx cy
        IFS='[],' read -r _ x1 y1 x2 y2 _ <<<"$bounds"
        cx=$(( (x1 + x2) / 2 ))
        cy=$(( (y1 + y2) / 2 ))
        "$ADB" shell input tap "$cx" "$cy" >>"$LOG" 2>&1 || true
      fi
    fi
    "$ADB" shell input keyevent KEYCODE_DPAD_DOWN >>"$LOG" 2>&1 || true
    "$ADB" shell input keyevent KEYCODE_DPAD_CENTER >>"$LOG" 2>&1 || true
    "$ADB" shell input tap 540 1450 >>"$LOG" 2>&1 || true
    sleep 3
  fi
}

wait_for_boot_settled() {
  local i anim
  for i in $(seq 1 90); do
    if ! "$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' | grep -q 1; then
      sleep 2
      continue
    fi
    anim=$("$ADB" shell getprop init.svc.bootanim 2>/dev/null | tr -d '\r')
    if [ "$anim" = "stopped" ] || [ -z "$anim" ]; then
      log "boot settled (bootanim=$anim) after ${i}s"
      "$ADB" shell input keyevent KEYCODE_WAKEUP >>"$LOG" 2>&1 || true
      "$ADB" shell wm dismiss-keyguard 2>>"$LOG" || true
      sleep 2
      return 0
    fi
    sleep 2
  done
  log "boot settled: timeout (bootanim still running)"
  return 0
}

wait_for_ui() {
  local needle="$1"
  local label="${2:-$needle}"
  local timeout="${3:-90}"
  local i
  for i in $(seq 1 "$timeout"); do
    dismiss_system_anr
    ui_dump_pull
    if grep -qF "$needle" "$UI_DUMP" 2>/dev/null; then
      log "ui ready ($label): ${i}s"
      return 0
    fi
    sleep 1
  done
  fail "ui wait '$label'"
  log_activity_top
  return 1
}

shot_after_ui() {
  local name="$1"
  local needle="$2"
  local label="$3"
  local timeout="${4:-60}"
  wait_for_ui "$needle" "$label" "$timeout" || return 1
  sleep 1
  "$ADB" exec-out screencap -p >"$ART/$name"
  local sz
  sz=$(stat -c%s "$ART/$name")
  if [ "$sz" -lt 45000 ]; then
    fail "blank screenshot $name ($sz)"
    return 1
  fi
  log "screenshot $name ($sz bytes)"
  cp -f "$UI_DUMP" "$ART/${name%.png}-ui.xml"
}

db_pull_dir() {
  local dir="$1"
  mkdir -p "$dir"
  for f in tharmesh.db tharmesh.db-wal tharmesh.db-shm; do
    "$ADB" exec-out run-as "$PKG" cat "databases/$f" >"$dir/$f" 2>/dev/null || touch "$dir/$f"
  done
  sqlite3 "$dir/tharmesh.db" "PRAGMA wal_checkpoint(FULL);" >/dev/null 2>&1 || true
}

db_query_in() {
  local sql="$1"
  local dir="$2"
  sqlite3 "$dir/tharmesh.db" "$sql" 2>/dev/null || echo "query_failed"
}

write_tsv() {
  local dir="$1"
  local file="$2"
  local sql="$3"
  local out
  if ! out=$(sqlite3 "$dir/tharmesh.db" "$sql" 2>/dev/null); then
    echo "query_failed" >"$dir/$file"
    return 1
  fi
  printf '%s\n' "$out" >"$dir/$file"
}

db_snap() {
  local tag="$1"
  local dir="$ART/db-snapshot-$tag"
  mkdir -p "$dir"
  db_pull_dir "$dir"
  write_tsv "$dir" messages.tsv "select id,body,status from messages order by id;" || fail "snapshot $tag messages"
  write_tsv "$dir" contacts.tsv "select userId,displayName from contacts order by userId;" || fail "snapshot $tag contacts"
  write_tsv "$dir" peer_identity.tsv "select userId,fingerprint,verified from peer_identity order by userId;" || fail "snapshot $tag peers"
  "$ADB" shell run-as "$PKG" cat shared_prefs/tharmesh_user_prefs.xml 2>/dev/null >"$dir/user_prefs.xml" || echo query_failed >"$dir/user_prefs.xml"
  sed -n 's/.*identity_fingerprint">\([^<]*\).*/\1/p' "$dir/user_prefs.xml" | head -1 >"$dir/mesh_fingerprint.txt"
  [ -s "$dir/mesh_fingerprint.txt" ] || echo query_failed >"$dir/mesh_fingerprint.txt"
  log "snapshot $tag mesh_fp=$(cat "$dir/mesh_fingerprint.txt") lines=$(wc -l <"$dir/messages.tsv")"
}

compare_snapshots() {
  local before="$ART/db-snapshot-$1"
  local after="$ART/db-snapshot-$2"
  for f in messages.tsv contacts.tsv peer_identity.tsv mesh_fingerprint.txt; do
  if [ ! -f "$before/$f" ] || [ ! -f "$after/$f" ]; then
      fail "missing snapshot file $f"
      continue
    fi
    if ! diff -q "$before/$f" "$after/$f" >>"$LOG" 2>&1; then
      fail "snapshot mismatch $f"
      diff "$before/$f" "$after/$f" | head -15 | tee -a "$LOG" || true
    else
      log "snapshot ok $f"
    fi
  done
}

step_offline_persistence() {
  log "=== offline room seed + persistence ==="
  "$ADB" logcat -c >>"$LOG" 2>&1 || true
  "$ADB" shell am force-stop "$PKG"
  "$ADB" shell am start -W -n "$PKG/com.tharmesh.ui.debug.OfflineRoomPersistenceSmokeActivity" >>"$LOG" 2>&1
  local dir="/tmp/tharmesh-pre"
  db_pull_dir "$dir"
  local MSG CONTACTS VERIFIED
  MSG=$(db_query_in "select count(*) from messages where body like 'OFFLINE_PERSISTENCE_SEED_%';" "$dir")
  CONTACTS=$(db_query_in "select count(*) from contacts;" "$dir")
  VERIFIED=$(db_query_in "select count(*) from peer_identity where verified=1;" "$dir")
  assert_not_query_failed messages "$MSG" || return 1
  assert_not_query_failed contacts "$CONTACTS" || return 1
  assert_not_query_failed verified "$VERIFIED" || return 1
  log "pre-kill: messages=$MSG contacts=$CONTACTS verified=$VERIFIED"
  [ "$MSG" = "3" ] || { fail "expected 3 messages"; return 1; }
  [ "$CONTACTS" = "2" ] || { fail "expected 2 contacts"; return 1; }
  [ "$VERIFIED" = "2" ] || { fail "expected 2 verified peers"; return 1; }
  db_snap pre_kill
  "$ADB" shell am force-stop "$PKG"
  "$ADB" shell am start -W -n "$PKG/com.tharmesh.ui.main.MainActivity" >>"$LOG" 2>&1 || true
  sleep 5
  for _ in 1 2 3; do dismiss_system_anr; done
  shot_after_ui "04-chats-after-seed.png" "Evidence Peer A" "chats_seed" 90 || return 1
  grep -qF "TharMesh" "$UI_DUMP" || { fail "missing TharMesh title"; return 1; }
  grep -qF "Chats" "$UI_DUMP" || { fail "missing Chats nav"; return 1; }

  "$ADB" shell svc wifi disable >>"$LOG" 2>&1 || true
  "$ADB" shell svc data disable >>"$LOG" 2>&1 || true
  "$ADB" shell am force-stop "$PKG"
  "$ADB" shell am start -W -n "$PKG/com.tharmesh.ui.main.MainActivity" >>"$LOG" 2>&1
  db_snap post_relaunch
  compare_snapshots pre_kill post_relaunch || return 1
  wait_for_ui "Evidence Peer A" "chats_a" 60 || return 1
  wait_for_ui "Evidence Peer B" "chats_b" 60 || return 1
  wait_for_ui "OFFLINE_PERSISTENCE_SEED_A2" "preview" 60 || return 1
  shot_after_ui "05-offline-chats-relaunch.png" "Evidence Peer A" "chats_shot" 30 || return 1
  log_activity_top
  log_fatal || return 1
}

step_reboot_durability() {
  log "=== emulator reboot durability ==="
  if ! "$ADB" reboot 2>>"$LOG"; then
    log "emulator reboot: NOT EXECUTED (adb reboot unsupported on this AVD)"
    return 0
  fi
  local i
  for i in $(seq 1 120); do
    if "$ADB" wait-for-device shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' | grep -q 1; then
      log "boot_completed after reboot: ${i}s"
      break
    fi
    sleep 2
  done
  wait_for_boot_settled
  sleep 10
  "$ADB" shell input keyevent KEYCODE_WAKEUP >>"$LOG" 2>&1 || true
  "$ADB" shell input keyevent KEYCODE_BACK >>"$LOG" 2>&1 || true
  "$ADB" shell svc wifi disable >>"$LOG" 2>&1 || true
  "$ADB" shell svc data disable >>"$LOG" 2>&1 || true
  "$ADB" shell am force-stop "$PKG"
  "$ADB" shell am start -W -n "$PKG/com.tharmesh.ui.main.MainActivity" >>"$LOG" 2>&1
  db_snap post_reboot
  compare_snapshots pre_kill post_reboot || return 1
  log "reboot durability TSV match: PASS"
  wait_for_boot_settled
  local attempt reboot_needle reboot_ok=0
  for attempt in $(seq 1 "$REBOOT_UI_MAX_ATTEMPTS"); do
    log "post-reboot UI attempt $attempt/$REBOOT_UI_MAX_ATTEMPTS"
    for _ in 1 2 3 4; do dismiss_system_anr; sleep 1; done
    "$ADB" shell am force-stop "$PKG"
    "$ADB" shell am start -W -n "$PKG/com.tharmesh.ui.main.MainActivity" >>"$LOG" 2>&1 || true
    sleep 6
    reboot_needle="Evidence Peer A"
    if wait_for_ui "$reboot_needle" "chats_post_reboot" 50; then
      reboot_ok=1
      break
    fi
    reboot_needle="OFFLINE_PERSISTENCE_SEED_A2"
    if wait_for_ui "$reboot_needle" "chats_post_reboot_seed" 50; then
      reboot_ok=1
      break
    fi
    wait_for_boot_settled
  done
  if [ "$reboot_ok" -ne 1 ]; then
    fail "post-reboot UI assertion failed after $REBOOT_UI_MAX_ATTEMPTS attempts"
    return 1
  fi
  shot_after_ui "06-offline-chats-post-reboot.png" "$reboot_needle" "reboot_chats" 60 || return 1
  log_fatal || return 1
}

step_fake_provision() {
  log "=== fake provisioning ==="
  "$ADB" shell am force-stop "$PKG"
  "$ADB" shell am start -W -n "$PKG/com.tharmesh.ui.debug.FakeProvisionSmokeActivity" >>"$LOG" 2>&1
  shot_after_ui "01-fake-provision.png" "Account security" "provision" 60
}

step_provision_relaunch() {
  log "=== provision relaunch ==="
  "$ADB" shell am force-stop "$PKG"
  "$ADB" shell am start -W -n "$PKG/com.tharmesh.ui.debug.ProvisionRelaunchSmokeActivity" >>"$LOG" 2>&1
  shot_after_ui "02-provision-relaunch.png" "Account security" "relaunch" 60
}

step_recovery() {
  log "=== recovery stage death ==="
  "$ADB" shell am force-stop "$PKG"
  "$ADB" shell am start -W -n "$PKG/com.tharmesh.ui.debug.RecoveryStageDeathSmokeActivity" >>"$LOG" 2>&1
  local STAGE
  STAGE=$("$ADB" shell run-as "$PKG" ls shared_prefs 2>/dev/null | grep tharmesh_recovery_rotation_stage || true)
  log "staged prefs: $STAGE"
  [ -n "$STAGE" ] || { fail "missing recovery stage prefs"; return 1; }
  "$ADB" shell am start -W -n "$PKG/com.tharmesh.ui.debug.LayoutPreviewActivity" --es screen rotate_recovery >>"$LOG" 2>&1
  shot_after_ui "03-recovery-rotate.png" "Rotate recovery key" "recovery" 45
}

step_offline_persistence || FAILED=1
step_reboot_durability || FAILED=1
step_fake_provision || FAILED=1
step_provision_relaunch || FAILED=1
step_recovery || FAILED=1

step_layout_l10n() {
  log "=== layout l10n light/dark (debug preview) ==="
  for spec in "account:false:layout-account-light.png" "account:true:layout-account-dark.png" \
    "rotate_recovery:false:layout-rotate-light.png" "rotate_recovery:true:layout-rotate-dark.png"; do
    IFS=: read -r screen night out <<<"$spec"
    "$ADB" shell am force-stop "$PKG"
    "$ADB" shell am start -W -n "$PKG/com.tharmesh.ui.debug.LayoutPreviewActivity" \
      --es screen "$screen" --ez night "$night" >>"$LOG" 2>&1
    local needle="Rotate recovery key"
    if [ "$screen" = "account" ]; then
      needle="Create account"
    fi
    shot_after_ui "$out" "$needle" "$out" 30 || return 1
  done
  local l10n_ok=0
  for pair in "ur-PK:layout-account-ur.png:اکاؤنٹ بنائیں" "sd-PK:layout-account-sd.png:اڪائونٽ ٺاهيو"; do
    IFS=: read -r locale out needle <<<"$pair"
  "$ADB" shell settings put system system_locales "$locale" >>"$LOG" 2>&1 || { log "locale $locale: set failed"; continue; }
    "$ADB" shell am force-stop "$PKG"
    "$ADB" shell am start -W -n "$PKG/com.tharmesh.ui.debug.LayoutPreviewActivity" \
      --es screen account --ez night false >>"$LOG" 2>&1 || continue
    if shot_after_ui "$out" "$needle" "$locale" 45; then
      l10n_ok=$((l10n_ok + 1))
    fi
  done
  if [ "$l10n_ok" -lt 2 ]; then
    fail "ur/sd emulator layouts: need 2/2 uiautomator passes ($l10n_ok captured)"
    return 1
  fi
  log "ur/sd emulator layouts: PASS (2/2)"
}

step_layout_l10n || FAILED=1

if [ "$FAILED" -ne 0 ]; then
  log "RESULT=FAIL"
  exit 1
fi
log "RESULT=PASS"
exit 0
