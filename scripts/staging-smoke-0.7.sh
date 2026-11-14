#!/usr/bin/env bash
# Category 3 local PG + Category 1 JVM. Hosted staging NOT EXECUTED.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
bash scripts/staging-smoke-0.6.sh
node --test supabase/tests/postgres-migration-inventory.test.mjs
echo "staging-smoke-0.7: ok"
