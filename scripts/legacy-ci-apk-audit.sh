#!/usr/bin/env bash
# Inventory downloadable CI debug APKs vs Room user_version (supervisor legacy gate).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="/opt/cursor/artifacts/ui/0.7-evidence/debug-keystore-upgrade/legacy-ci-apk-audit.txt"
mkdir -p "$(dirname "$OUT")"
: >"$OUT"
log() { echo "$@" | tee -a "$OUT"; }

log "=== legacy CI debug APK audit ==="
log "date_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"

found_lt7=0
shas=$(gh api 'repos/qadeer-cyber/TharMesh/actions/artifacts?per_page=100&name=tharmesh-app-debug' --paginate 2>/dev/null \
  | jq -s '[.[].artifacts[]? | select(.expired==false) | .workflow_run.head_sha] | unique | .[]' 2>/dev/null || true)

while read -r sha; do
  [ -z "$sha" ] && continue
  ver=$(git -C "$ROOT" show "$sha:app/src/main/java/com/tharmesh/db/AppDatabase.kt" 2>/dev/null \
    | awk '/version =/{print $3}' | tr -d ',')
  [ -z "$ver" ] && ver="unknown"
  log "head_sha=$sha room_version=$ver"
  if [ "$ver" != "unknown" ] && [ "$ver" -lt 7 ] 2>/dev/null; then
    found_lt7=1
  fi
done <<<"$shas"

if [ "$found_lt7" -eq 0 ]; then
  log "RESULT=NOT_EXECUTED legacy_schema_migration"
  log "reason=No non-expired tharmesh-app-debug CI artifact maps to a commit with Room version < 7 (main 31a9dbb and a2e14177 are v7; PR #52 earliest artifacts are v7; v5/v6 commits e.g. 9fedae9 have zero retained Actions artifacts — April 2026 runs expired)"
else
  log "RESULT=CI_APK_AVAILABLE (room < 7 found — run debug-keystore-upgrade-evidence.sh with that artifact)"
fi
