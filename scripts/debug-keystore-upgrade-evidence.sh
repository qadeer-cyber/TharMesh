#!/usr/bin/env bash
# Phase 6 — debug-keystore APK upgrade over installed data (Category 6 adjunct).
# Signed QA/release upgrade remains NOT EXECUTED.
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

log() { echo "$@" | tee -a "$LOG"; }

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
  write_tsv "$dir" contacts.tsv "select userId,displayName from contacts order by userId;"
  write_tsv "$dir" peer_identity.tsv "select userId,fingerprint,verified from peer_identity order by userId;"
  "$ADB" shell run-as "$PKG" cat shared_prefs/tharmesh_user_prefs.xml 2>/dev/null >"$dir/user_prefs.xml" || echo query_failed >"$dir/user_prefs.xml"
  sed -n 's/.*identity_fingerprint">\([^<]*\).*/\1/p' "$dir/user_prefs.xml" | head -1 >"$dir/mesh_fingerprint.txt"
}

compare_snapshots() {
  local before="$ART/db-snapshot-before"
  local after="$ART/db-snapshot-after"
  for f in messages.tsv contacts.tsv peer_identity.tsv mesh_fingerprint.txt; do
    if ! diff -q "$before/$f" "$after/$f" >>"$LOG" 2>&1; then
      log "FAIL snapshot mismatch $f"
      diff "$before/$f" "$after/$f" | head -10 | tee -a "$LOG" || true
      return 1
    fi
    log "snapshot ok $f"
  done
}

main() {
  if [ ! -f "$OLD_APK" ]; then
    log "OLD_APK missing at $OLD_APK — download CI artifact (e.g. 14015582952) first"
    exit 2
  fi
  ensure_emulator
  (cd "$ROOT" && ./gradlew assembleDebug -q)
  NEW_APK="$ROOT/app/build/outputs/apk/debug/tharmesh.app-debug.apk"
  OLD_SHA="$(cert_sha256 "$OLD_APK")"
  NEW_SHA="$(cert_sha256 "$NEW_APK")"
  log "old_apk_sha256_cert=$OLD_SHA"
  log "new_apk_sha256_cert=$NEW_SHA"
  if [ "$OLD_SHA" != "$NEW_SHA" ]; then
    log "FAIL signing cert mismatch (not a debug-keystore upgrade path)"
    exit 1
  fi
  log "signing: debug-keystore match OK"

  "$ADB" install -r -d "$OLD_APK" >>"$LOG" 2>&1
  "$ADB" shell am force-stop "$PKG"
  "$ADB" shell am start -W -n "$PKG/com.tharmesh.ui.debug.OfflineRoomPersistenceSmokeActivity" >>"$LOG" 2>&1
  sleep 3
  db_snap before

  "$ADB" install -r "$NEW_APK" >>"$LOG" 2>&1
  "$ADB" shell am force-stop "$PKG"
  "$ADB" shell am start -W -n "$PKG/com.tharmesh.ui.main.MainActivity" >>"$LOG" 2>&1
  sleep 2
  db_snap after
  compare_snapshots
  log "RESULT=PASS debug-keystore upgrade"
}

main "$@"
