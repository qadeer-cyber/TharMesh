#!/usr/bin/env bash
# Rebuild a historical debug APK with the pinned public debug.keystore (no app logic edits).
set -euo pipefail
COMMIT="${1:-9fedae917868340e9b2b93f46b847a6119c576bd}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WT="${HISTORICAL_WORKTREE:-/tmp/wt-room-v6}"
OUT="${2:-$WT/app/build/outputs/apk/debug/tharmesh.app-debug.apk}"

log() { echo "$@"; }

if [ -d "$WT" ]; then
  git -C "$ROOT" worktree remove -f "$WT" 2>/dev/null || true
  rm -rf "$WT"
fi
git -C "$ROOT" worktree add --detach "$WT" "$COMMIT"

cp -f "$ROOT/app/debug.keystore" "$WT/app/debug.keystore"
if ! grep -q 'storeFile file("debug.keystore")' "$WT/app/build.gradle" 2>/dev/null; then
  python3 - "$WT/app/build.gradle" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
text = p.read_text()
if 'signingConfigs' in text:
    sys.exit(0)
old = "    buildTypes {\n        release {"
new = """    signingConfigs {
        debug {
            storeFile file("debug.keystore")
            storePassword "android"
            keyAlias "androiddebugkey"
            keyPassword "android"
        }
    }

    buildTypes {
        debug {
            signingConfig signingConfigs.debug
        }
        release {"""
if old not in text:
    raise SystemExit("build.gradle patch point not found")
p.write_text(text.replace(old, new, 1))
PY
fi

export GRADLE_USER_HOME="${HISTORICAL_GRADLE_USER_HOME:-$WT/.gradle-user-home}"
rm -rf "$WT/app/build" "$WT/.gradle" "$GRADLE_USER_HOME"
log "building assembleDebug at $COMMIT (gradle_user_home=$GRADLE_USER_HOME)"
(cd "$WT" && ./gradlew --no-daemon clean assembleDebug -q)
log "apk=$OUT"
sha256sum "$OUT"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/home/ubuntu/android-sdk}}"
"$(ls -d "$SDK/build-tools/"*/apksigner 2>/dev/null | sort -V | tail -1)" verify --print-certs "$OUT" | grep -E 'SHA-1|SHA-256'
