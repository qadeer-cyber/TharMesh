#!/usr/bin/env bash
# Staging 0.6: apply forward-only migrations and list Edge deploy targets. Does not touch production.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MIG_DIR="$ROOT/supabase/migrations"
DB_NAME="${1:-postgres}"

if [ -z "${DATABASE_URL:-}" ] && [ -z "${PGHOST:-}" ]; then
  echo "Set DATABASE_URL or PG* env for remote apply; or pass a local DB name for psql."
fi

apply_local() {
  local db="$1"
  for f in \
    202611120001_identity_provisioning.sql \
    202611120002_identity_proof.sql \
    202611120003_identity_device_guard.sql \
    202611120004_trusted_device_enrollment.sql \
    202611120005_device_current_explicit_reset.sql \
    202611120006_enrollment_fail_closed_edge.sql \
    202611120007_bootstrap_proof_and_auth_amr.sql \
    202611120008_recovery_cas_revoke_fresh.sql \
    202611120009_recovery_lock_device_serialize.sql \
    202611120010_grant_session_atomic_recovery.sql \
    202611120011_revoke_possession_jwt_claims.sql \
    202611120012_revoke_challenge_possession.sql \
    202611120013_revoke_svc_session_assert.sql
  do
    echo "apply: $f"
    psql -v ON_ERROR_STOP=1 -d "$db" -f "$MIG_DIR/$f"
  done
  psql -v ON_ERROR_STOP=1 -d "$db" -f "$ROOT/supabase/staging/verify-0.6.sql"
}

if [ -n "${DATABASE_URL:-}" ]; then
  for f in "$MIG_DIR"/2026111200*.sql; do
    echo "apply: $(basename "$f")"
    psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f "$f"
  done
  psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f "$ROOT/supabase/staging/verify-0.6.sql"
else
  apply_local "$DB_NAME"
fi

cat <<'EDGE'
Edge functions to deploy (staging project):
  supabase/functions/identity-proof
  supabase/functions/device-enrollment
  supabase/functions/sensitive-auth
  supabase/functions/recovery-rotate
  supabase/functions/device-revoke
Shared: supabase/functions/_shared/amr.ts, session.ts, p256.ts
EDGE
