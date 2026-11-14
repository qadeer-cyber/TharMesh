# TharMesh 0.4 backend deployment package (do not apply to production)

Owner applies this in an **isolated** Supabase project first. This repository
does not deploy production or toggle admission.

## 1. Migration sequence (idempotent apply order)

1. `supabase/schema.sql` (baseline profiles, handles, devices, recovery_blobs, RLS)
2. `supabase/migrations/202611120001_identity_provisioning.sql`
3. `supabase/migrations/202611120002_identity_proof.sql`
4. `supabase/migrations/202611120003_identity_device_guard.sql`
5. `supabase/migrations/202611120004_trusted_device_enrollment.sql`

Verify: `select enabled from tharmesh_private.identity_admission` → **`false`**.

## 2. Identity admission feature flag

```sql
-- Production must stay off until Android + enrollment gates pass:
select enabled from tharmesh_private.identity_admission;
update tharmesh_private.identity_admission set enabled = false where singleton;
```

Emergency disable (same as pause in `docs/identity-provisioning.md`).

## 3. Edge Function

- Deploy `supabase/functions/identity-proof/index.ts` with platform-managed secrets.
- JWT: caller access token verified independently; only `service_role` may call
  `tharmesh_identity_challenge` / `tharmesh_identity_finish`.
- Signatures: base64url IEEE P1363 (64 bytes), message
  `tharmesh:identity:register:v1\n<account_uuid>\n<nonce>\n<spki_key>`.

## 4. Environment / JWT expectations

| Variable | Where |
| --- | --- |
| `SUPABASE_URL`, `SUPABASE_ANON_KEY` | GitHub Actions vars / local.properties |
| `GOOGLE_WEB_CLIENT_ID` | Actions var; optional until Google enabled |
| Service role | Supabase dashboard only; never in Android or CI logs |
| SMTP credentials | Supabase Auth → Emails only |

## 5. Preflight checklist (owner)

- [ ] Migrations applied in order on staging
- [ ] `identity_admission.enabled = false` on production
- [ ] Redirect URLs include `tharmesh://auth/callback`
- [ ] Magic link + Confirm signup templates include `{{ .Token }}`
- [ ] Custom SMTP + SPF/DKIM/DMARC on sending domain
- [ ] Google provider + Android OAuth SHA-1 for `tharmesh.app` debug APK
- [ ] Run `supabase/tests/identity_provisioning.sql` as migration owner
- [ ] Run `node --test supabase/tests/identity-database.test.cjs` (PGlite, single session)
- [ ] Run `bash supabase/tests/postgres-apply.sh tharmesh_pgtest` then `node --test supabase/tests/identity-postgres-multisession.test.mjs` (**local Postgres multi-session, not hosted Supabase**)
- [ ] Run `node --test supabase/tests/identity-proof.test.mjs` (mocked HTTP)
- [ ] Android QA signing environment approved before `THARMESH_QA_SIGNING_ENABLED=true`

## 6. Smoke tests (staging only)

1. Health: `GET /auth/v1/health` with `apikey` only.
2. Settings: `GET /auth/v1/settings` (booleans only in CI script).
3. Authenticated `tharmesh_identity_state` returns `legacy_unknown` for pre-admission users.

Do not run live signup spam or production data mutation from CI.

## 7. Rollback

- Set `identity_admission.enabled = false`.
- Do not drop `canonical_identities` rows after rollout experiments.
- Previous app versions without provisioning remain compatible while admission is off.

## 8. Monitoring

- Supabase Auth logs for `42501`, `email_address_not_authorized`, rate limits.
- Edge Function 4xx/5xx for identity-proof.
- Owner alert on accidental `enabled = true` in production (manual audit).
