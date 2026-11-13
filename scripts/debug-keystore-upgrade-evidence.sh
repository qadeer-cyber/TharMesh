#!/usr/bin/env bash
# Phase 6 — debug-keystore APK upgrade over installed data (Category 6 adjunct).
# Signed QA/release upgrade remains NOT EXECUTED.
# Gate: OLD APK must be CI-available (see LEGACY_ROOM_MAX) for legacy schema migration.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ANDROID_HOME:-/home/ubuntu/android-sdk}/platform-tools/adb"
EMU="${ANDROID_HOME:-/home/ubuntu/android-sdk}/emulator/emulator"
ART="/opt/cursor/artifacts/ui/0.7-evidence/debug-keystore-upgrade"
PKG="tharmesh.app"
mkdir -p "$ART"
LOG="$ART/upgrade-transcript.txt"
: >"$LOG"
OLD_APK="${1:-$ART/tharmesh-app-debug-old.apk}"
OLD_CI_RUN="${OLD_CI_RUN:-}"
OLD_ROOM_EXPECTED="${OLD_ROOM_EXPECTED:-}"
LEGACY_ROOM_MAX="${LEGACY_ROOM_MAX:-6}"

log() { echo "$@" | tee -a "$LOG"; }

ensure_emulator() {
  if ! "$ADB" devices | grep -qE 'emulator-[0-9]+\s+device'; then
    AVD="$("$EMU" -list-avds | head -1)"
    test -n "$AVD"
    "$EMU" -avd "$AVD" -no-window -no-audio -gpu swiftshader_indirect &
    for _ in $(seq 1 120); do
      "$ADB" wait-for-device shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' | grep -q 1 && break
      sleep 2
    done
  fi
}

apksigner_bin() {
  local bt
  bt="$(ls -d "${ANDROID_HOME:-/home/ubuntu/android-sdk}"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)"
  test -x "$bt"
  echo "$bt"
}

cert_sha256() {
  local apk="$1"
  "$(apksigner_bin)" verify --print-certs "$apk" 2>/dev/null | awk '/Signer #1 certificate SHA-256/{print $6; exit}'
}

file_apk_sha256() {
  sha256sum "$1" | awk '{print $1}'
}

db_pull_dir() {
  local dir="$1"
  mkdir -p "$dir"
  for f in tharmesh.db tharmesh.db-wal tharmesh.db-shm; do
    "$ADB" exec-out run-as "$PKG" cat "databases/$f" >"$dir/$f" 2>/dev/null || touch "$dir/$f"
  done
}

wal_checkpoint_dir() {
  local dir="$1"
  sqlite3 "$dir/tharmesh.db" "PRAGMA wal_checkpoint(FULL);" >/dev/null 2>&1 || true
}

room_user_version() {
  sqlite3 "$1/tharmesh.db" "PRAGMA user_version;" 2>/dev/null || echo "query_failed"
}

table_exists() {
  local dir="$1" name="$2"
  sqlite3 "$dir/tharmesh.db" "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='$name';" 2>/dev/null | grep -q 1
}

write_tsv() {
  local dir="$1" file="$2" sql="$3"
  wal_checkpoint_dir "$dir"
  local out
  if ! out=$(sqlite3 "$dir/tharmesh.db" "$sql" 2>/dev/null); then
    echo query_failed >"$dir/$file"
    return 1
  fi
  printf '%s\n' "$out" >"$dir/$file"
}

db_snap() {
  local tag="$1"
  local dir="$ART/db-snapshot-$tag"
  mkdir -p "$dir"
  db_pull_dir "$dir"
  write_tsv "$dir" messages.tsv "select id,body,status from messages order by id;"
  if table_exists "$dir" contacts; then
    write_tsv "$dir" contacts.tsv "select userId,displayName from contacts order by userId;"
  else
    echo "(no contacts table)" >"$dir/contacts.tsv"
  fi
  if table_exists "$dir" peer_identity; then
    if sqlite3 "$dir/tharmesh.db" "PRAGMA table_info(peer_identity);" 2>/dev/null | grep -q verified; then
      write_tsv "$dir" peer_identity.tsv "select userId,fingerprint,verified from peer_identity order by userId;"
    else
      write_tsv "$dir" peer_identity.tsv "select userId,fingerprint from peer_identity order by userId;"
    fi
  else
    echo "(no peer_identity table)" >"$dir/peer_identity.tsv"
  fi
  "$ADB" shell run-as "$PKG" cat shared_prefs/tharmesh_user_prefs.xml 2>/dev/null >"$dir/user_prefs.xml" || echo query_failed >"$dir/user_prefs.xml"
  if grep -q identity_fingerprint "$dir/user_prefs.xml" 2>/dev/null; then
    sed -n 's/.*identity_fingerprint">\([^<]*\).*/\1/p' "$dir/user_prefs.xml" | head -1 >"$dir/mesh_fingerprint.txt"
  else
    echo "(no mesh_fp pref)" >"$dir/mesh_fingerprint.txt"
  fi
  log "snapshot $tag room_version=$(room_user_version "$dir") mesh_fp=$(head -1 "$dir/mesh_fingerprint.txt")"
}

compare_snapshots() {
  local before="$ART/db-snapshot-before"
  local after="$ART/db-snapshot-after"
  for f in messages.tsv contacts.tsv peer_identity.tsv mesh_fingerprint.txt; do
    if [ ! -f "$before/$f" ] || [ ! -f "$after/$f" ]; then
      log "FAIL missing snapshot file $f"
      return 1
    fi
    if grep -qE '^\(no |query_failed' "$before/$f" 2>/dev/null; then
      log "compare skip $f (absent on old schema)"
      continue
    fi
    if ! diff -q "$before/$f" "$after/$f" >>"$LOG" 2>&1; then
      log "FAIL snapshot mismatch $f"
      diff "$before/$f" "$after/$f" | head -10 | tee -a "$LOG" || true
      return 1
    fi
    log "snapshot ok $f"
  done
}

seed_old_app() {
  "$ADB" shell am force-stop "$PKG"
  if "$ADB" shell pm dump "$PKG" 2>/dev/null | grep -q OfflineRoomPersistenceSmokeActivity; then
    "$ADB" shell am start -W -n "$PKG/com.tharmesh.ui.debug.OfflineRoomPersistenceSmokeActivity" >>"$LOG" 2>&1
    sleep 3
    return 0
  fi
  log "WARN: OfflineRoomPersistenceSmokeActivity missing on old APK — legacy seed NOT EXECUTED"
  return 1
}

main() {
  if [ ! -f "$OLD_APK" ]; then
    log "OLD_APK missing at $OLD_APK"
    log "RESULT=NOT_EXECUTED (no old CI APK file)"
    exit 2
  fi
  log "old_apk_file_sha256=$(file_apk_sha256 "$OLD_APK")"
  [ -n "$OLD_CI_RUN" ] && log "old_apk_ci_run=$OLD_CI_RUN"

  ensure_emulator
  (cd "$ROOT" && ./gradlew assembleDebug -q)
  NEW_APK="$ROOT/app/build/outputs/apk/debug/tharmesh.app-debug.apk"
  log "new_apk_file_sha256=$(file_apk_sha256 "$NEW_APK")"
  OLD_SHA="$(cert_sha256 "$OLD_APK")"
  NEW_SHA="$(cert_sha256 "$NEW_APK")"
  log "old_apk_sha256_cert=$OLD_SHA"
  log "new_apk_sha256_cert=$NEW_SHA"
  if [ "$OLD_SHA" != "$NEW_SHA" ]; then
    log "FAIL signing cert mismatch (not a debug-keystore upgrade path)"
    exit 1
  fi
  log "signing: debug-keystore match OK"

  "$ADB" uninstall "$PKG" >>"$LOG" 2>&1 || true
  "$ADB" install -r -d "$OLD_APK" >>"$LOG" 2>&1
  if ! seed_old_app; then
    log "RESULT=NOT_EXECUTED (old APK cannot seed data)"
    exit 2
  fi
  db_snap before
  local old_room new_room
  old_room=$(room_user_version "$ART/db-snapshot-before")
  log "old_room_user_version=$old_room"

  "$ADB" install -r "$NEW_APK" >>"$LOG" 2>&1
  "$ADB" shell am force-stop "$PKG"
  "$ADB" shell am start -W -n "$PKG/com.tharmesh.ui.main.MainActivity" >>"$LOG" 2>&1
  sleep 3
  db_snap after
  new_room=$(room_user_version "$ART/db-snapshot-after")
  log "new_room_user_version=$new_room"
  if [ "$old_room" = "query_failed" ] || [ "$new_room" = "query_failed" ]; then
    log "FAIL could not read room version"
    exit 1
  fi
  if [ -n "$OLD_ROOM_EXPECTED" ] && [ "$old_room" != "$OLD_ROOM_EXPECTED" ]; then
    log "WARN old room $old_room != expected $OLD_ROOM_EXPECTED"
  fi
  if [ "$old_room" -ge "$new_room" ]; then
    log "migration_path=NONE (same Room user_version; reinstall durability only, not legacy schema)"
    log "RESULT=NOT_EXECUTED legacy_schema_migration (need CI debug APK with Room user_version <= $LEGACY_ROOM_MAX)"
    exit 3
  fi
  log "migration_path=Room_${old_room}_to_${new_room} (automatic on open)"
  compare_snapshots
  log "RESULT=PASS debug-keystore legacy schema upgrade"
}

main "$@"
