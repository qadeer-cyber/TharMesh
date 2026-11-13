# Authorization surface audit (local Postgres after migrations 001–014)

## Scope

This documents the **intended client-callable PostgreSQL API** for role `authenticated` after `schema.sql`, documented Supabase platform defaults, migrations **001–013**, and forward migration **014**.

Migration **014** is **defense-in-depth** only: migrations **009–013** already `REVOKE` execute on every `public.*_svc` and re-grant `service_role`. PostgreSQL also re-grants `PUBLIC` execute when routines are `DROP`/`CREATE`’d; **014** repeats that lockdown once at the end of the chain. It does **not** document a previously unpatched production hole on hosted Supabase.

## Authenticated callers (`GRANT EXECUTE` intended)

These are the only `public.tharmesh_*` routines that must remain executable by `authenticated` (non-`_svc`). Edge Functions use `service_role` for `*_svc` paths.

| Function | Purpose | Migrations |
|----------|---------|------------|
| `tharmesh_identity_state()` | Read identity activation / mesh / generation | 002 |
| `tharmesh_uses_canonical_identity()` | Feature gate | 003 |
| `tharmesh_list_devices(text)` | Device list (optional self pubkey) | 005 (replaces no-arg form) |
| `tharmesh_commit_explicit_reset()` | Explicit reset evidence | 005 |
| `tharmesh_account_evidence()` | Account evidence timestamps | 004–005 |
| `tharmesh_auth_fresh_required(integer)` | AMR freshness gate | 006 |
| `tharmesh_recovery_generation()` | Read recovery blob generation | 008 |
| `tharmesh_jwt_session_id()` | Read `session_id` / `jti` from JWT claims (PostgREST / `auth.jwt()` plumbing) | 011 |

### Direct client RPC disabled (`REVOKE EXECUTE` from authenticated)

| Function | Behavior | Migration |
|----------|----------|-----------|
| `tharmesh_device_revoke(text, uuid, text)` | No `EXECUTE` for authenticated; use `device-revoke` Edge | 011 |

## Not authenticated (must deny `EXECUTE`)

- Every `public.tharmesh_*_svc` (Edge / `service_role` only).
- Every `tharmesh_private.*` function.
- `tharmesh_identity_challenge`, `tharmesh_identity_finish` — `service_role` only (002).
- `tharmesh_provision_identity` — `authenticated` revoked (002).
- Legacy enrollment/bootstrap RPCs dropped in 006.

## Verification

Cat 3 CI: `supabase/tests/postgres-migration-authenticated-rpc-surface.test.mjs` (allowlist + smoke under `withSession`) and `postgres-migration-privileges.test.mjs` (`has_function_privilege` for `_svc` / private).
