#!/usr/bin/env bash
# Apply TharMesh SQL to a disposable local Postgres database.
set -euo pipefail
DB_NAME="${1:-tharmesh_pgtest}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export PGHOST="${THARMESH_PG_HOST:-${PGHOST:-127.0.0.1}}"
export PGUSER="${THARMESH_PG_USER:-${PGUSER:-tharmesh_runner}}"
export PGPASSWORD="${THARMESH_PG_PASSWORD:-${PGPASSWORD:-pgtest_local}}"
if [ "$PGUSER" = "postgres" ] && [ "$PGHOST" = "/var/run/postgresql" ]; then
  PSQL="sudo -u postgres psql"
else
  PSQL="psql -h $PGHOST -U $PGUSER"
fi
PSQL="$PSQL -v ON_ERROR_STOP=1"
$PSQL -d postgres -c "DROP DATABASE IF EXISTS ${DB_NAME};"
$PSQL -d postgres -c "CREATE DATABASE ${DB_NAME};"
$PSQL -d "$DB_NAME" -c "CREATE EXTENSION IF NOT EXISTS plpython3u;"
$PSQL -d "$DB_NAME" -f "$ROOT/tests/postgres-bootstrap.sql"
$PSQL -d "$DB_NAME" -f "$ROOT/schema.sql"
for f in 202611120001_identity_provisioning.sql \
         202611120002_identity_proof.sql \
         202611120003_identity_device_guard.sql \
         202611120004_trusted_device_enrollment.sql \
         202611120005_device_current_explicit_reset.sql \
         202611120006_enrollment_fail_closed_edge.sql \
         202611120007_bootstrap_proof_and_auth_amr.sql \
         202611120008_recovery_cas_revoke_fresh.sql \
         202611120009_recovery_lock_device_serialize.sql; do
  $PSQL -d "$DB_NAME" -f "$ROOT/migrations/$f"
done
$PSQL -d "$DB_NAME" -c "GRANT USAGE ON SCHEMA public TO authenticated, anon;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO service_role;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO authenticated, service_role;
GRANT USAGE ON SCHEMA tharmesh_private TO service_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA tharmesh_private TO service_role;
REVOKE INSERT, UPDATE, DELETE ON TABLE public.recovery_blobs FROM authenticated, anon, public;
GRANT SELECT ON TABLE public.recovery_blobs TO authenticated;
GRANT INSERT ON auth.users TO service_role;"
echo "Applied migrations to ${DB_NAME}"
