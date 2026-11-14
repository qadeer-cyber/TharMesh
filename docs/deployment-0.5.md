# TharMesh Update 0.5 — deployment notes (Track B draft)

## P0 crypto architecture

- **No in-database ECDSA verification.** Migration `202611120006_enrollment_fail_closed_edge.sql` drops `tharmesh_private.verify_ecdsa_p256_spki` and removes authenticated access to enrollment write RPCs.
- **Edge Functions (Web Crypto):**
  - `supabase/functions/device-enrollment/index.ts` — enrollment `begin` / `approve` / `finish` and `bootstrap_challenge` + `bootstrap_commit` (dual mesh + device proofs).
  - `supabase/functions/sensitive-auth/index.ts` — server-enforced fresh auth from JWT `amr` timestamps.
  - `supabase/functions/recovery-rotate/index.ts` — recovery upload with fresh auth + generation CAS.
- **Service-role RPCs only for writes:** `tharmesh_enrollment_*_svc`, `tharmesh_bootstrap_device_svc`, `tharmesh_auth_fresh_record_svc`.
- **Bootstrap enrollment without an active trusted device row is denied** (no `bootstrap-mesh` path).

Deploy order after 001–005:

1. `202611120006_enrollment_fail_closed_edge.sql`
2. `202611120007_bootstrap_proof_and_auth_amr.sql`
3. `202611120008_recovery_cas_revoke_fresh.sql`
4. Edge functions `identity-proof`, `device-enrollment`, `sensitive-auth`, `recovery-rotate` with `SUPABASE_SERVICE_ROLE_KEY`.
3. Keep `identity_admission.enabled = false` until all gates pass.

Local tests:

```bash
node --test supabase/tests/enrollment-crypto.test.mjs
node --test supabase/tests/identity-proof.test.mjs
bash supabase/tests/postgres-apply.sh tharmesh_pgtest
node supabase/tests/identity-postgres-multisession.test.mjs
```

Do **not** apply `supabase/tests/fixtures/verify_plpython_local.sql` to hosted Supabase; plpython is local harness only.
