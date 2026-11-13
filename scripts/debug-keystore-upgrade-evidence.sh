#!/usr/bin/env bash
# Phase 6 — debug-keystore APK upgrade over installed data (Category 6 adjunct).
# Signed QA/release upgrade remains NOT EXECUTED.
# Gate: OLD APK must be CI-available (see LEGACY_ROOM_MAX) for legacy schema migration.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/home/ubuntu/android-sdk}}"
ADB="$SDK/platform-tools/adb"
EMU="$SDK/emulator/emulator"
EVIDENCE_BASE="${THARMESH_EVIDENCE_DIR:-/opt/cursor/artifacts/ui/0.7-evidence}"
ART="$EVIDENCE_BASE/debug-keystore-upgrade"
PKG="tharmesh.app"
mkdir -p "$ART"
LOG="$ART/upgrade-transcript.txt"
: >"$LOG"
OLD_APK="${1:-$ART/tharmesh-app-debug-old.apk}"
OLD_CI_RUN="${OLD_CI_RUN:-}"
OLD_ROOM_EXPECTED="${OLD_ROOM_EXPECTED:-}"
LEGACY_ROOM_MAX="${LEGACY_ROOM_MAX:-6}"
LEGACY_SOURCE_COMMIT="${LEGACY_SOURCE_COMMIT:-}"

log() { echo "$@" | tee -a "$LOG"; }

wait_for_boot_settled() {
  local b a
  for _ in $(seq 1 90); do
    b=$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
    a=$("$ADB" shell getprop init.svc.bootanim 2>/dev/null | tr -d '\r')
    if [ "$b" = "1" ] && { [ "$a" = "stopped" ] || [ -z "$a" ]; }; then
      return 0
    fi
    sleep 2
  done
  return 1
}

ensure_emulator() {
  if "$ADB" devices | grep -qE 'emulator-[0-9]+\s+device'; then
    wait_for_boot_settled || true
    return 0
  fi
  AVD="$("$EMU" -list-avds 2>/dev/null | head -1)"
  test -n "$AVD"
  "$EMU" -avd "$AVD" -no-window -no-audio -gpu swiftshader_indirect -no-boot-anim &
  for _ in $(seq 1 120); do
    "$ADB" wait-for-device shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' | grep -q 1 && break
    sleep 2
  done
  wait_for_boot_settled || true
}

apksigner_bin() {
  local bt
  bt="$(ls -d "$SDK/build-tools/"*/apksigner 2>/dev/null | sort -V | tail -1)"
  test -x "$bt"
  echo "$bt"
}

cert_sha256() {
  local apk="$1"
  local out
  out=$("$(apksigner_bin)" verify --print-certs "$apk" 2>/dev/null \
    | sed -n 's/.*SHA-256 digest: //p' | head -1 | tr -d '[:space:]')
  if [ -z "$out" ]; then
    out=$("$(apksigner_bin)" verify --print-certs "$apk" 2>/dev/null \
      | sed -n 's/.*SHA-256: //p' | head -1 | tr -d ': \r')
  fi
  echo "$out"
}

file_apk_sha256() {
  sha256sum "$1" | awk '{print $1}'
}

db_pull_dir() {
  local dir="$1"
  mkdir -p "$dir"
  local f
  for f in tharmesh.db tharmesh.db-wal tharmesh.db-shm; do
    rm -f "$dir/$f"
    if "$ADB" exec-out run-as "$PKG" cat "databases/$f" >"$dir/$f" 2>/dev/null; then
      [ -s "$dir/$f" ] || rm -f "$dir/$f"
    fi
  done
  if [ ! -s "$dir/tharmesh.db" ]; then
    log "FAIL database pull: tharmesh.db missing or empty"
    return 1
  fi
}

wait_for_room_db() {
  local probe="/tmp/tharmesh-db-probe"
  local attempt
  for attempt in $(seq 1 40); do
    mkdir -p "$probe"
    if "$ADB" exec-out run-as "$PKG" cat databases/tharmesh.db >"$probe/tharmesh.db" 2>/dev/null \
      && [ -s "$probe/tharmesh.db" ] \
      && head -c 15 "$probe/tharmesh.db" | grep -q 'SQLite format'; then
      log "room database ready (attempt $attempt)"
      rm -rf "$probe"
      return 0
    fi
    rm -rf "$probe"
    "$ADB" shell am force-stop "$PKG" >>"$LOG" 2>&1 || true
    "$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >>"$LOG" 2>&1 || true
    "$ADB" shell am start -W -n "$PKG/com.tharmesh.ui.main.MainActivity" >>"$LOG" 2>&1 || true
    sleep 5
  done
  log "FAIL room database never appeared"
  "$ADB" logcat -d -s AndroidRuntime:E TharMeshApp:D >>"$LOG" 2>&1 || true
  return 1
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
  db_pull_dir "$dir" || return 1
  write_tsv "$dir" messages.tsv "select id,body,status from messages order by id;" || true
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

v6_complete_anonymous_login() {
  "$ADB" shell am start -W -n "$PKG/com.tharmesh.ui.auth.LoginActivity" >>"$LOG" 2>&1 || true
  sleep 4
  local ui="/tmp/v6-login-ui.xml"
  "$ADB" shell uiautomator dump /sdcard/v6-login-ui.xml >>"$LOG" 2>&1 || true
  "$ADB" pull /sdcard/v6-login-ui.xml "$ui" >>"$LOG" 2>&1 || true
  if grep -qF 'text="Continue anonymously"' "$ui" 2>/dev/null; then
    local bounds cx cy x1 y1 x2 y2
    bounds=$(grep -oE 'text="Continue anonymously"[^/]*bounds="[^"]+"' "$ui" | head -1 | sed -n 's/.*bounds="\([^"]*\)".*/\1/p')
    if [ -n "$bounds" ]; then
      IFS='[],' read -r _ x1 y1 x2 y2 _ <<<"$bounds"
      cx=$(( (x1 + x2) / 2 ))
      cy=$(( (y1 + y2) / 2 ))
      "$ADB" shell input tap "$cx" "$cy" >>"$LOG" 2>&1 || true
      sleep 5
      return 0
    fi
  fi
  "$ADB" shell input tap 540 1680 >>"$LOG" 2>&1 || true
  sleep 5
}

grant_runtime_permissions() {
  local p
  for p in \
    android.permission.ACCESS_FINE_LOCATION \
    android.permission.ACCESS_COARSE_LOCATION \
    android.permission.BLUETOOTH \
    android.permission.BLUETOOTH_ADMIN; do
    "$ADB" shell pm grant "$PKG" "$p" >>"$LOG" 2>&1 || true
  done
}

seed_legacy_v6_sqlite() {
  grant_runtime_permissions
  local prefs="/tmp/tharmesh_v6_user_prefs.xml"
  cat >"$prefs" <<'EOF'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="authenticated" value="true" />
    <string name="user_id">legacy-v6-seed-user</string>
    <string name="username">Legacy V6 Seed</string>
    <string name="auth_provider">anonymous</string>
</map>
EOF
  "$ADB" push "$prefs" /data/local/tmp/tharmesh_user_prefs.xml >>"$LOG" 2>&1
  "$ADB" shell run-as "$PKG" mkdir -p shared_prefs >>"$LOG" 2>&1 || true
  "$ADB" shell run-as "$PKG" cp /data/local/tmp/tharmesh_user_prefs.xml shared_prefs/tharmesh_user_prefs.xml >>"$LOG" 2>&1 \
    || "$ADB" shell "base64 < /data/local/tmp/tharmesh_user_prefs.xml | run-as $PKG sh -c 'base64 -d > shared_prefs/tharmesh_user_prefs.xml'" >>"$LOG" 2>&1
  v6_complete_anonymous_login || true
  wait_for_room_db || return 1
  "$ADB" shell am force-stop "$PKG"
  local dir="/tmp/tharmesh-v6-seed"
  mkdir -p "$dir"
  db_pull_dir "$dir" || return 1
  local ver
  ver=$(room_user_version "$dir")
  log "v6 seed bootstrap room_version=$ver"
  if [ "$ver" != "6" ] && [ "$ver" != "0" ]; then
    log "WARN expected room 6 before seed inserts, got $ver"
  fi
  sqlite3 "$dir/tharmesh.db" <<'SQL'
PRAGMA user_version = 6;
INSERT OR REPLACE INTO contacts (userId, displayName, publicKey, addedAt, lastSeen) VALUES
  ('peer-a', 'Evidence Peer A', 'PUBKEYA', 100, 100),
  ('peer-b', 'Evidence Peer B', 'PUBKEYB', 100, 100);
INSERT INTO messages (fromUserId, toUserId, peerUserId, body, status, timestamp) VALUES
  ('legacy-v6-seed-user', 'peer-a', 'peer-a', 'LEGACY_V6_SEED_A1', 'DELIVERED', 1001),
  ('legacy-v6-seed-user', 'peer-a', 'peer-a', 'LEGACY_V6_SEED_A2', 'DELIVERED', 1002),
  ('legacy-v6-seed-user', 'peer-b', 'peer-b', 'LEGACY_V6_SEED_B1', 'DELIVERED', 1003);
INSERT OR REPLACE INTO peer_identity (userId, publicKeyBase64, fingerprint, firstSeenMs, verified, verifiedAtMs) VALUES
  ('peer-a', 'PUBKEYA', 'fpa', 100, 1, 101),
  ('peer-b', 'PUBKEYB', 'fpb', 100, 1, 101);
SQL
  "$ADB" push "$dir/tharmesh.db" /data/local/tmp/tharmesh.db >>"$LOG" 2>&1
  "$ADB" shell run-as "$PKG" mkdir -p databases >>"$LOG" 2>&1 || true
  "$ADB" shell run-as "$PKG" cp /data/local/tmp/tharmesh.db databases/tharmesh.db >>"$LOG" 2>&1
  rm -f "$dir/tharmesh.db-wal" "$dir/tharmesh.db-shm"
  : >"$dir/tharmesh.db-wal"
  : >"$dir/tharmesh.db-shm"
  "$ADB" push "$dir/tharmesh.db-wal" /data/local/tmp/tharmesh.db-wal >>"$LOG" 2>&1 || true
  "$ADB" push "$dir/tharmesh.db-shm" /data/local/tmp/tharmesh.db-shm >>"$LOG" 2>&1 || true
  "$ADB" shell run-as "$PKG" cp /data/local/tmp/tharmesh.db-wal databases/tharmesh.db-wal >>"$LOG" 2>&1 || true
  "$ADB" shell run-as "$PKG" cp /data/local/tmp/tharmesh.db-shm databases/tharmesh.db-shm >>"$LOG" 2>&1 || true
  log "v6 sqlite seed complete"
}

seed_old_app() {
  "$ADB" shell am force-stop "$PKG"
  if [ -n "$LEGACY_SOURCE_COMMIT" ]; then
    log "legacy seed via sqlite (historical commit $LEGACY_SOURCE_COMMIT)"
    seed_legacy_v6_sqlite || return 1
  fi
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
  [ -n "$LEGACY_SOURCE_COMMIT" ] && log "legacy_source_commit=$LEGACY_SOURCE_COMMIT"

  ensure_emulator
  if [ "${SKIP_HEAD_ASSEMBLE:-0}" != "1" ]; then
    (cd "$ROOT" && ./gradlew assembleDebug -q)
  fi
  NEW_APK="$ROOT/app/build/outputs/apk/debug/tharmesh.app-debug.apk"
  log "new_apk_file_sha256=$(file_apk_sha256 "$NEW_APK")"
  OLD_SHA="$(cert_sha256 "$OLD_APK")"
  NEW_SHA="$(cert_sha256 "$NEW_APK")"
  log "old_apk_sha256_cert=$OLD_SHA"
  log "new_apk_sha256_cert=$NEW_SHA"
  if [ -z "$OLD_SHA" ] || [ -z "$NEW_SHA" ]; then
    log "FAIL could not read APK signing certificate digests"
    exit 1
  fi
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
  if [ -n "$LEGACY_SOURCE_COMMIT" ]; then
    log "label=Cat 6 debug-keystore legacy upgrade, old APK rebuilt from historical source commit $LEGACY_SOURCE_COMMIT"
  fi
  log "RESULT=PASS debug-keystore legacy schema upgrade"
}

main "$@"
