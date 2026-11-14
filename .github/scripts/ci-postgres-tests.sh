#!/usr/bin/env bash
# Category 3 Postgres tests only (no JVM/emulator).
set -euo pipefail
export THARMESH_PG_STATEMENT_TIMEOUT_MS="${THARMESH_PG_STATEMENT_TIMEOUT_MS:-90000}"
export THARMESH_PG_LOCK_TIMEOUT_MS="${THARMESH_PG_LOCK_TIMEOUT_MS:-20000}"
export THARMESH_PG_IDLE_TX_TIMEOUT_MS="${THARMESH_PG_IDLE_TX_TIMEOUT_MS:-60000}"
export THARMESH_NODE_TEST_TIMEOUT_MS="${THARMESH_NODE_TEST_TIMEOUT_MS:-180000}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
export PGHOST="${PGHOST:-localhost}"
export PGUSER="${THARMESH_PG_USER:-tharmesh_runner}"
export PGPASSWORD="${THARMESH_PG_PASSWORD:-pgtest_local}"

npm install --prefix /tmp/tharmesh-pg-tests pg@8.11.3 tsx@4.19.2
TSX_LOADER="/tmp/tharmesh-pg-tests/node_modules/tsx/dist/loader.mjs"
NODE_TEST=(node --import "$TSX_LOADER" --test "--test-timeout=${THARMESH_NODE_TEST_TIMEOUT_MS}")

bash supabase/tests/postgres-ensure-test-role.sh

bash supabase/tests/postgres-apply.sh tharmesh_ci_pg
"${NODE_TEST[@]}" supabase/tests/postgres-fresh-upgrade.test.mjs
"${NODE_TEST[@]}" supabase/tests/postgres-migration-inventory.test.mjs
"${NODE_TEST[@]}" supabase/tests/postgres-migration-privileges.test.mjs
"${NODE_TEST[@]}" supabase/tests/postgres-migration-authenticated-rpc-surface.test.mjs
"${NODE_TEST[@]}" supabase/tests/identity-postgres-multisession.test.mjs
NODE_TESTS=(
  supabase/tests/recovery-bypass.test.mjs
  supabase/tests/schema-recovery-privileges.test.mjs
  supabase/tests/recovery-grant-atomic.test.mjs
  supabase/tests/sensitive-grant-session.test.mjs
  supabase/tests/device-revoke-challenge-pg.test.mjs
  supabase/tests/device-revoke-approve-race.test.mjs
  supabase/tests/device-revoke-race.test.mjs
  supabase/tests/device-lock-race.test.mjs
  supabase/tests/jwt-claims-session.test.mjs
  supabase/tests/device-revoke-crypto.test.mjs
)
for t in "${NODE_TESTS[@]}"; do
  "${NODE_TEST[@]}" "$t"
done
bash supabase/tests/postgres-migration-full-apply-twice.sh tharmesh_ci_pg_idem2
echo "ci-postgres-tests: ok"
