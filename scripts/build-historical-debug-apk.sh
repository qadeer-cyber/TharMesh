#!/usr/bin/env bash
# Rebuild a historical debug APK with the pinned public debug.keystore (no app logic edits).
set -euo pipefail
COMMIT="${1:-9fedae917868340e9b2b93f46b847a6119c576bd}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WT="${HISTORICAL_WORKTREE:-/tmp/wt-room-v6}"
OUT="${2:-$WT/app/build/outputs/apk/debug/tharmesh.app-debug.apk}"

log() { echo "$@"; }

if [ ! -d "$WT/.git" ]; then
  git -C "$ROOT" worktree add "$WT" "$COMMIT"
else
  git -C "$WT" checkout -f "$COMMIT"
fi

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

log "building assembleDebug at $COMMIT"
(cd "$WT" && ./gradlew assembleDebug -q)
log "apk=$OUT"
sha256sum "$OUT"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/home/ubuntu/android-sdk}}"
"$(ls -d "$SDK/build-tools/"*/apksigner 2>/dev/null | sort -V | tail -1)" verify --print-certs "$OUT" | grep -E 'SHA-1|SHA-256'
