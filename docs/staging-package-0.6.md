# Staging package (0.6 P1 draft) — no production changes

## Migrations (forward-only through 013)

Apply in order: `202611120001` … `202611120013`. Do not edit applied migrations on hosted projects.

**013** adds `p_session_id` to `tharmesh_device_revoke_svc` with SQL assert `ch.session_id = p_session_id` (DB invariant, not Edge-only).

## Scripts (agent-runnable)

| Script | Purpose |
|--------|---------|
| `scripts/staging-0.6-deploy.sh` | Apply 001–013 + run `supabase/staging/verify-0.6.sql` |
| `scripts/staging-smoke-0.6.sh` | Local PG apply + Node PG suite + `testDebugUnitTest` |
| `scripts/emulator-0.6-activation-smoke.sh` | Layout/interruption previews (wraps `emulator-0.4-smoke.sh`) |

## Preconditions

- `tharmesh_private.identity_admission.enabled = false` before and after staging tests.
- Separate Supabase project URL and anon key for staging Android flavor (not production).

## Deploy checklist

1. Run SQL migrations.
2. Deploy Edge: `identity-proof`, `device-enrollment`, `sensitive-auth`, `recovery-rotate`, `device-revoke` (include `_shared/amr.ts`, `_shared/session.ts`).
3. Verify grants: `recovery_blobs` has no INSERT/UPDATE for `authenticated` (`schema-recovery-privileges.test.mjs`).
4. Configure Auth JWT to include `amr` with interactive method timestamps and a stable `session_id` (or `jti`) claim per session.

## Local verification (agent-runnable)

```bash
bash supabase/tests/postgres-apply.sh tharmesh_pgtest
node --test supabase/tests/recovery-bypass.test.mjs
node --test supabase/tests/recovery-grant-atomic.test.mjs
node --test supabase/tests/sensitive-grant-session.test.mjs
node --test supabase/tests/schema-recovery-privileges.test.mjs
node --test supabase/tests/device-revoke-race.test.mjs
node --test supabase/tests/device-lock-race.test.mjs
node --test supabase/tests/device-revoke-crypto.test.mjs
node --test supabase/tests/device-revoke-challenge-pg.test.mjs
node --test supabase/tests/device-revoke-approve-race.test.mjs
node --test supabase/tests/jwt-claims-session.test.mjs
node supabase/tests/identity-postgres-multisession.test.mjs
```

## Owner-only staging smoke

Hosted Supabase project, Google OAuth staging client, SMTP for Email OTP — not available in cloud agent without owner credentials.

## Track A

Track A (admission / canonical activation) remains **out of scope** for PR #52. Open standalone PRs when `identity_admission.enabled` gating is ready; do not enable admission on production.
