#!/usr/bin/env bash
# fresh | reapply — uses postgres-migrate-lib.sh (tracking table + schema.sql + 001-013).
set -euo pipefail
MODE="${1:?mode fresh|reapply}"
DB_NAME="${2:?database name}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MIG_DIR="${MIGRATIONS_DIR:-$ROOT/migrations}"
LIB="$ROOT/tests/postgres-migrate-lib.sh"
# shellcheck source=/dev/null
source "$LIB"

export PGHOST="${THARMESH_PG_HOST:-127.0.0.1}"
export PGUSER="${THARMESH_PG_USER:-tharmesh_runner}"
export PGPASSWORD="${THARMESH_PG_PASSWORD:-pgtest_local}"
PSQL="psql -h $PGHOST -U $PGUSER -v ON_ERROR_STOP=1"

if [ "$MODE" = "fresh" ]; then
  $PSQL -d postgres -c "DROP DATABASE IF EXISTS ${DB_NAME};"
  $PSQL -d postgres -c "CREATE DATABASE ${DB_NAME};"
  $PSQL -d "$DB_NAME" -c "CREATE EXTENSION IF NOT EXISTS plpython3u;"
  $PSQL -d "$DB_NAME" -f "$ROOT/tests/postgres-bootstrap.sql"
  migration_ensure_tracking "$DB_NAME" "$ROOT"
  $PSQL -d "$DB_NAME" -f "$ROOT/schema.sql"
  migration_apply_dir "$DB_NAME" "$MIG_DIR"
  $PSQL -d "$DB_NAME" -c "GRANT USAGE ON SCHEMA public TO authenticated, anon;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO service_role;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO authenticated, service_role;
GRANT USAGE ON SCHEMA tharmesh_private TO service_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA tharmesh_private TO service_role;
REVOKE INSERT, UPDATE, DELETE ON TABLE public.recovery_blobs FROM authenticated, anon, public;
GRANT SELECT ON TABLE public.recovery_blobs TO authenticated;
GRANT INSERT ON auth.users TO service_role;"
  echo "postgres-migrate-runner: fresh ok ($DB_NAME)"
elif [ "$MODE" = "reapply" ]; then
  migration_ensure_tracking "$DB_NAME" "$ROOT"
  migration_apply_dir "$DB_NAME" "$MIG_DIR"
  echo "postgres-migrate-runner: reapply ok ($DB_NAME)"
else
  echo "unknown mode $MODE" >&2
  exit 1
fi
