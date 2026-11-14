#!/usr/bin/env bash
# Applies 001–013 on a fresh DB, then re-applies 009–013 on the populated DB (upgrade-with-data idempotency).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DB="${1:-tharmesh_pgtest_idem2}"
export PGHOST="${THARMESH_PG_HOST:-127.0.0.1}"
export PGUSER="${THARMESH_PG_USER:-tharmesh_runner}"
export PGPASSWORD="${THARMESH_PG_PASSWORD:-pgtest_local}"
bash "$ROOT/tests/postgres-apply.sh" "$DB"
MIG_DIR="$ROOT/migrations"
for f in \
  202611120010_grant_session_atomic_recovery.sql \
  202611120011_revoke_possession_jwt_claims.sql \
  202611120012_revoke_challenge_possession.sql \
  202611120013_revoke_svc_session_assert.sql
do
  psql -h "$PGHOST" -U "$PGUSER" -d "$DB" -v ON_ERROR_STOP=1 -f "$MIG_DIR/$f"
done
echo "postgres-migration-full-apply-twice: ok ($DB)"
