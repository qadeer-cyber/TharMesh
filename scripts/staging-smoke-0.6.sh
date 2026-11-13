#!/usr/bin/env bash
# Runnable 0.6 smoke: local PG + Node PG tests + JVM unit tests. Hosted Supabase is owner-only.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
DB="${THARMESH_PG_DB:-tharmesh_pgtest}"

export PGHOST="${THARMESH_PG_HOST:-${PGHOST:-127.0.0.1}}"
export PGUSER="${THARMESH_PG_USER:-${PGUSER:-tharmesh_runner}}"
export PGPASSWORD="${THARMESH_PG_PASSWORD:-${PGPASSWORD:-pgtest_local}}"
bash supabase/tests/postgres-apply.sh "$DB"
psql -h "$PGHOST" -U "$PGUSER" -d "$DB" -v ON_ERROR_STOP=1 -f supabase/staging/verify-0.6.sql

NODE_TESTS=(
  supabase/tests/recovery-grant-atomic.test.mjs
  supabase/tests/sensitive-grant-session.test.mjs
  supabase/tests/device-revoke-challenge-pg.test.mjs
  supabase/tests/device-revoke-approve-race.test.mjs
  supabase/tests/device-revoke-crypto.test.mjs
  supabase/tests/jwt-claims-session.test.mjs
)
for t in "${NODE_TESTS[@]}"; do
  node --test "$t"
done
node supabase/tests/identity-postgres-multisession.test.mjs

bash supabase/tests/postgres-migration-full-apply-twice.sh "${DB}_idem2"

./gradlew testDebugUnitTest -q

if [ "${RUN_EMULATOR:-0}" = "1" ]; then
  bash scripts/emulator-0.4-smoke.sh
fi

echo "staging-smoke-0.6: ok (db=$DB)"
