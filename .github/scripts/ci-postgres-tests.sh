#!/usr/bin/env bash
# Category 3 Postgres tests only (no JVM/emulator).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
export PGHOST="${PGHOST:-localhost}"
export PGUSER="${THARMESH_PG_USER:-tharmesh_runner}"
export PGPASSWORD="${THARMESH_PG_PASSWORD:-pgtest_local}"

npm install --prefix /tmp/tharmesh-pg-tests pg@8.11.3

bash supabase/tests/postgres-ensure-test-role.sh

bash supabase/tests/postgres-apply.sh tharmesh_ci_pg
node --test supabase/tests/postgres-fresh-upgrade.test.mjs
node --test supabase/tests/postgres-migration-inventory.test.mjs
node supabase/tests/identity-postgres-multisession.test.mjs
NODE_TESTS=(
  supabase/tests/recovery-grant-atomic.test.mjs
  supabase/tests/sensitive-grant-session.test.mjs
  supabase/tests/device-revoke-challenge-pg.test.mjs
  supabase/tests/device-revoke-approve-race.test.mjs
)
for t in "${NODE_TESTS[@]}"; do
  node --test "$t"
done
bash supabase/tests/postgres-migration-full-apply-twice.sh tharmesh_ci_pg_idem2
echo "ci-postgres-tests: ok"
