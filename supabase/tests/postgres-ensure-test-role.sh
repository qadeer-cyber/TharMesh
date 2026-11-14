#!/usr/bin/env bash
# CI (postgres superuser) and local dev (tharmesh_runner): ensure login role for node tests.
set -euo pipefail
export PGHOST="${PGHOST:-localhost}"
ADMIN_USER="${THARMESH_PG_ADMIN_USER:-${PGUSER:-postgres}}"
ADMIN_PASS="${THARMESH_PG_ADMIN_PASSWORD:-${PGPASSWORD:-postgres}}"
export PGPASSWORD="$ADMIN_PASS"
psql -h "$PGHOST" -U "$ADMIN_USER" -d postgres -v ON_ERROR_STOP=1 <<'SQL'
DO $$ BEGIN
  CREATE ROLE tharmesh_runner LOGIN PASSWORD 'pgtest_local';
EXCEPTION WHEN duplicate_object THEN
  ALTER ROLE tharmesh_runner WITH LOGIN PASSWORD 'pgtest_local' CREATEDB;
END $$;
ALTER ROLE tharmesh_runner CREATEDB;
SQL
echo "postgres-ensure-test-role: tharmesh_runner ok"
