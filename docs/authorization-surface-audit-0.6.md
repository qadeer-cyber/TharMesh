# Authorization surface audit (0.6 P0)

## Edge functions

| Function | JWT user lookup | Account UUID bind | Interactive AMR only | Action grant |
|----------|-----------------|-------------------|------------------------|--------------|
| `identity-proof` | yes | `sub` vs user id | (via GoTrue session) | n/a |
| `device-enrollment` | yes | request body + user | yes (`_shared/amr.ts`) | n/a (crypto + svc) |
| `sensitive-auth` | yes | `sub` vs user id | yes | issues session-bound grants (`recovery_rotate`, `device_revoke`) |
| `recovery-rotate` | yes | `sub` vs user id | yes | `tharmesh_recovery_rotate_commit_svc` (grant consume + CAS put, one txn) |
| `device-revoke` | yes | `sub` vs user id | yes (grant path) | Server challenge (`tharmesh_device_revoke_challenge_issue_svc`); Edge verifies `TM-DEVICE-REVOKE-CHALLENGE-v1` with DB pubkey lookup; `tharmesh_device_revoke_svc` |

## Write RPCs (service_role only)

`tharmesh_enrollment_*_svc`, `tharmesh_bootstrap_device_svc`, `tharmesh_recovery_put_svc`, `tharmesh_sensitive_grant_*_svc`, `tharmesh_auth_fresh_record_svc` — `REVOKE` from `authenticated`/`anon` in migrations 006–009.

## Authenticated RPCs (guarded)

`tharmesh_device_revoke(text,uuid,text)` — **fail-closed** (Edge only). `tharmesh_device_revoke_svc` — **service_role** after possession proof. Session id via `tharmesh_jwt_session_id()` / `auth.jwt()` claims JSON (hosted PostgREST compatible).

## Direct table writes closed (009)

`recovery_blobs`: `authenticated` has **SELECT** only; INSERT/UPDATE/DELETE revoked; insert/update policies dropped.

## AMR interactive methods (Edge)

Counted: `password`, `otp`, `oauth`, `magiclink`, `email`, `sso`, `totp`, `google`, `sign_in`.

Excluded: `anonymous`, `token_refresh`, `refresh`.

## Device mutation serialization

`tharmesh_private.account_device_mutation_lock` advisory xact lock used in `tharmesh_device_revoke`, `tharmesh_enrollment_finish_svc`, `tharmesh_enrollment_approve_svc`, `tharmesh_bootstrap_device_svc`, `tharmesh_commit_explicit_reset` (migration 009–010).

## Known issue (gate 2): revoked device JWT still account-scoped

Supabase user JWTs are account-level, not device-level. A phone that was revoked in `devices` but still holds a refreshable session can pass fresh AMR (`sensitive-auth`) and obtain a `device_revoke` grant while only the non-revoked device’s pubkey satisfies `p_active_device_pubkey`. Mitigation in 010: revoke requires proof from an **active** device key on the account. Remaining gap: any active device holder can revoke others (intended for account holder); a stale session on a **revoked** device cannot satisfy pubkey proof but could still disturb enroll/read paths until refresh tokens expire—monitor session revocation on device revoke in a future gate.

## All-devices-lost recovery

If every device is revoked, the account cannot enroll via trusted-device approval until the user restores mesh identity from a recovery phrase on a new install, or owner runs a controlled support flow with `identity_admission` staging (production admission remains OFF).
