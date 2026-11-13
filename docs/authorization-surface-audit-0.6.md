# Authorization surface audit (0.6 P0)

## Edge functions

| Function | JWT user lookup | Account UUID bind | Interactive AMR only | Action grant |
|----------|-----------------|-------------------|------------------------|--------------|
| `identity-proof` | yes | `sub` vs user id | (via GoTrue session) | n/a |
| `device-enrollment` | yes | request body + user | yes (`_shared/amr.ts`) | n/a (crypto + svc) |
| `sensitive-auth` | yes | `sub` vs user id | yes | issues `sensitive_action_grants` |
| `recovery-rotate` | yes | `sub` vs user id | yes + `tharmesh_auth_fresh_required` | consumes `recovery_rotate` grant |

## Write RPCs (service_role only)

`tharmesh_enrollment_*_svc`, `tharmesh_bootstrap_device_svc`, `tharmesh_recovery_put_svc`, `tharmesh_sensitive_grant_*_svc`, `tharmesh_auth_fresh_record_svc` — `REVOKE` from `authenticated`/`anon` in migrations 006–009.

## Authenticated RPCs (guarded)

`tharmesh_device_revoke`, `tharmesh_recovery_generation`, `tharmesh_list_devices`, `tharmesh_account_evidence` — require `auth.uid()` and fresh auth where applicable.

## Direct table writes closed (009)

`recovery_blobs`: `authenticated` has **SELECT** only; INSERT/UPDATE/DELETE revoked; insert/update policies dropped.

## AMR interactive methods (Edge)

Counted: `password`, `otp`, `oauth`, `magiclink`, `email`, `sso`, `totp`, `google`, `sign_in`.

Excluded: `anonymous`, `token_refresh`, `refresh`.

## Device mutation serialization

`tharmesh_private.account_device_mutation_lock` advisory xact lock used in `tharmesh_device_revoke`, `tharmesh_enrollment_finish_svc`, `tharmesh_bootstrap_device_svc` (migration 009).

## All-devices-lost recovery

If every device is revoked, the account cannot enroll via trusted-device approval until the user restores mesh identity from a recovery phrase on a new install, or owner runs a controlled support flow with `identity_admission` staging (production admission remains OFF).
