#!/usr/bin/env bash
# Rebuild a historical debug APK with the pinned public debug.keystore (no app logic edits).
set -euo pipefail
COMMIT="${1:-9fedae917868340e9b2b93f46b847a6119c576bd}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WT="${HISTORICAL_WORKTREE:-/tmp/wt-room-v6}"
OUT="${2:-$WT/app/build/outputs/apk/debug/tharmesh.app-debug.apk}"

log() { echo "$@"; }

# Always start from a clean, freshly registered worktree. The `[ -d "$WT" ]` guard
# this replaces was wrong whenever the directory had been deleted from under git
# (emulator-evidence.yml does exactly that with `rm -rf "$HISTORICAL_WORKTREE"`):
# the stale registration survived and `worktree add` then died with
# "missing but already registered worktree". Reproduced locally on 2026-11-21.
git -C "$ROOT" worktree remove -f "$WT" 2>/dev/null || true
rm -rf "$WT"
git -C "$ROOT" worktree prune
git -C "$ROOT" worktree add --detach "$WT" "$COMMIT"

# Provenance: the tree we are about to build must be exactly the pinned commit.
ACTUAL_COMMIT="$(git -C "$WT" rev-parse HEAD)"
EXPECT_COMMIT="$(git -C "$ROOT" rev-parse "$COMMIT")"
if [ "$ACTUAL_COMMIT" != "$EXPECT_COMMIT" ]; then
  echo "FAIL historical worktree is $ACTUAL_COMMIT, expected $EXPECT_COMMIT" >&2
  exit 1
fi
log "worktree=$WT commit=$ACTUAL_COMMIT"

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
log "build_inputs worktree=$WT commit=$ACTUAL_COMMIT java=$(java -version 2>&1 | head -1)"
sha256sum "$OUT"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/home/ubuntu/android-sdk}}"
"$(ls -d "$SDK/build-tools/"*/apksigner 2>/dev/null | sort -V | tail -1)" verify --print-certs "$OUT" | grep -E 'SHA-1|SHA-256'
