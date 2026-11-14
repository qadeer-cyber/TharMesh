#!/usr/bin/env bash
# Apply TharMesh SQL to a disposable local Postgres database (tracked runner).
set -euo pipefail
DB_NAME="${1:-tharmesh_pgtest}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export PGHOST="${THARMESH_PG_HOST:-${PGHOST:-127.0.0.1}}"
export PGUSER="${THARMESH_PG_USER:-${PGUSER:-tharmesh_runner}}"
export PGPASSWORD="${THARMESH_PG_PASSWORD:-${PGPASSWORD:-pgtest_local}}"
bash "$ROOT/tests/postgres-migrate-runner.sh" fresh "$DB_NAME"
echo "Applied migrations to ${DB_NAME}"
