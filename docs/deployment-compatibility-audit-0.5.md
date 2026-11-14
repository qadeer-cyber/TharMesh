# TharMesh 0.5 — deployment compatibility audit (fail-closed)

This document records what must exist on a hosted Supabase project before enabling canonical identity admission. **Do not set `identity_admission.enabled = true` until every gate below is verified.**

## Fail-closed flags

| Flag / setting | Required value | Notes |
|----------------|----------------|-------|
| `tharmesh_private.identity_admission.enabled` | **OFF (false)** | Default until audit complete |
| In-DB ECDSA verifier (`verify_ecdsa_p256_spki`) | **absent** | Dropped in `202611120006` |
| Authenticated enrollment write RPCs (`tharmesh_enrollment_begin` etc.) | **raise / revoked** | Edge-only crypto in `device-enrollment` |
| Unsigned `bootstrap_device` (pre-007) | **absent** | Challenge + `bootstrap_commit` only |
| Direct recovery REST upsert without CAS | **discouraged** | Clients should use `recovery-rotate` Edge |

## Migrations (apply in order)

1. `202611120001` … `202611120005` — identity, devices, evidence
2. `202611120006_enrollment_fail_closed_edge.sql`
3. `202611120007_bootstrap_proof_and_auth_amr.sql`
4. `202611120008_recovery_cas_revoke_fresh.sql`

**Do not** apply `supabase/tests/fixtures/verify_plpython_local.sql` on hosted projects.

## Extensions

- Standard Supabase stack only; no `plpython3u` on hosted.

## Edge Functions (deploy with service role secret)

| Function | Purpose |
|----------|---------|
| `identity-proof` | Canonical activation challenges |
| `device-enrollment` | Enrollment + bootstrap proofs (Web Crypto) |
| `sensitive-auth` | AMR-based fresh auth recording |
| `recovery-rotate` | Recovery upload with fresh auth + generation CAS |

Each function needs `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `SUPABASE_SERVICE_ROLE_KEY`.

## Grants (spot-check)

- `tharmesh_*_svc` RPCs: **service_role** only
- `tharmesh_list_devices`, `tharmesh_device_revoke`, `tharmesh_recovery_generation`: **authenticated**
- `tharmesh_recovery_put_svc`: **service_role** only (via Edge)
- RLS on `profiles`, `handles`, `devices`, `recovery_blobs`: own-row policies only

## Local verification commands

```bash
bash supabase/tests/postgres-apply.sh tharmesh_pgtest
node --test supabase/tests/enrollment-crypto.test.mjs
node --test supabase/tests/bootstrap-crypto.test.mjs
node --test supabase/tests/sensitive-auth-amr.test.mjs
node supabase/tests/identity-postgres-multisession.test.mjs
```

## Admission OFF checklist

- [ ] All migrations through `008` applied
- [ ] Edge functions deployed and reachable
- [ ] `identity_admission.enabled` confirmed false
- [ ] No permissive in-DB signature verifier
- [ ] PG multi-session suite green locally

When all boxes pass, admission may be enabled in a controlled rollout (out of scope for Track B draft).
