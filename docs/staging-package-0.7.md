# TharMesh staging package 0.7 (Track B)

Category 5 (hosted staging): **NOT EXECUTED** without owner Supabase project credentials.

## Separate plane

- Dedicated Supabase project / DB / auth users (not production).
- OAuth redirect URLs and Android staging `applicationIdSuffix` or build flavor when wired.
- `identity_admission.enabled` starts **OFF**; enable only after owner authorization (remain OFF for this track).

## Apply order

1. `supabase/schema.sql`
2. `supabase/tests/postgres-supabase-platform-defaults.sql` — documents hosted `PUBLIC` function defaults that migrations must re-lock (`anon` / `authenticated` / `service_role`).
3. Forward migrations `202611120001` … `202611120014` (see `supabase/migrations/`)
4. Edge Functions (Deno), deployed with project **service_role** only (never in the app):
   - `identity-proof`
   - `device-enrollment` (`begin` / `approve` / `finish` / `bootstrap_challenge` / `bootstrap_commit`)
   - `sensitive-auth`
   - `recovery-rotate`
   - `device-revoke` (`issue` / `commit`)

Migration **014** (`202611120014_svc_execute_lockdown.sql`) is **defense-in-depth**: migrations **009–013** already revoke `public.*_svc` and `tharmesh_private` execute from client roles; **014** repeats lockdown after any `CREATE OR REPLACE` that re-grants `PUBLIC`.

## Authenticated RPC surface (PostgREST)

After 001–014, client-callable `authenticated` EXECUTE is documented in `docs/authorization-surface-audit.md`:

- Product: `tharmesh_identity_state`, `tharmesh_uses_canonical_identity`, `tharmesh_list_devices(text)`, `tharmesh_commit_explicit_reset`, `tharmesh_account_evidence`, `tharmesh_auth_fresh_required`, `tharmesh_recovery_generation`
- JWT helper: `tharmesh_jwt_session_id`
- **Not** client-callable: every `*_svc`, every `tharmesh_private.*`, and `tharmesh_device_revoke` (Edge-only)

## Local verification (Category 3)

```bash
bash scripts/staging-smoke-0.7.sh
bash .github/scripts/ci-postgres-tests.sh
```

## Owner checklist (hosted)

- Google OAuth client + redirect allowlist
- Email OTP SMTP sender + `{{ .Token }}` template
- Android deep link / callback URL
- Rate limits in GoTrue dashboard
- Deploy Edge bundle with service_role to project only (never in app)
- Keep `tharmesh_private.identity_admission.enabled = false` until explicit owner go-live
