# Staging package (0.6 P1 draft) — no production changes

## Migrations (forward-only through 009)

Apply in order: `202611120001` … `202611120009`. Do not edit applied migrations on hosted projects.

## Preconditions

- `tharmesh_private.identity_admission.enabled = false` before and after staging tests.
- Separate Supabase project URL and anon key for staging Android flavor (not production).

## Deploy checklist

1. Run SQL migrations.
2. Deploy Edge: `identity-proof`, `device-enrollment`, `sensitive-auth`, `recovery-rotate` (include `_shared/amr.ts`).
3. Verify grants: `recovery_blobs` has no INSERT/UPDATE for `authenticated`.
4. Configure Auth JWT to include `amr` with interactive method timestamps.

## Local verification (agent-runnable)

```bash
bash supabase/tests/postgres-apply.sh tharmesh_pgtest
node --test supabase/tests/recovery-bypass.test.mjs
node --test supabase/tests/device-revoke-race.test.mjs
node supabase/tests/identity-postgres-multisession.test.mjs
```

## Owner-only staging smoke

Hosted Supabase project, Google OAuth staging client, SMTP for Email OTP — not available in cloud agent without owner credentials.
