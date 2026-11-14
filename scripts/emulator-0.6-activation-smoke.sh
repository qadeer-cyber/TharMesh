#!/usr/bin/env bash
# 0.6 activation + recovery layout/interruption preview on emulator (no live Supabase).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export RUN_EMULATOR=1
bash "$ROOT/scripts/emulator-0.4-smoke.sh"
# Recovery resume + rotation auth screens are captured in emulator-0.4-smoke.sh (rotate_recovery, rotation_auth).
echo "emulator-0.6-activation-smoke: layout artifacts under /opt/cursor/artifacts/ui/0.4"
