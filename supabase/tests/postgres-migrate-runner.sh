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
  $PSQL -d "$DB_NAME" -c "CREATE EXTENSION IF NOT EXISTS plpython3u;" 2>/dev/null || \
    echo "note: plpython3u extension unavailable (skipped)"
  $PSQL -d "$DB_NAME" -f "$ROOT/tests/postgres-bootstrap.sql"
  migration_ensure_tracking "$DB_NAME" "$ROOT"
  $PSQL -d "$DB_NAME" -f "$ROOT/schema.sql"
  $PSQL -d "$DB_NAME" -f "$ROOT/tests/postgres-supabase-platform-defaults.sql"
  migration_apply_dir "$DB_NAME" "$MIG_DIR"
  echo "postgres-migrate-runner: fresh ok ($DB_NAME)"
elif [ "$MODE" = "reapply" ]; then
  migration_ensure_tracking "$DB_NAME" "$ROOT"
  migration_apply_dir "$DB_NAME" "$MIG_DIR"
  echo "postgres-migrate-runner: reapply ok ($DB_NAME)"
else
  echo "unknown mode $MODE" >&2
  exit 1
fi
