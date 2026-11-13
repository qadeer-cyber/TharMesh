# TharMesh staging package 0.7 (Track B)

Category 5 (hosted staging): **NOT EXECUTED** without owner Supabase project credentials.

## Separate plane

- Dedicated Supabase project / DB / auth users (not production).
- OAuth redirect URLs and Android staging `applicationIdSuffix` or build flavor when wired.
- `identity_admission.enabled` starts **OFF**; enable only after owner authorization.

## Apply order

1. `supabase/schema.sql`
2. Migrations `202611120001` … `202611120013` (see `scripts/staging-0.6-deploy.sh`)
3. Edge Functions: `identity-proof`, `device-enrollment`, `sensitive-auth`, `recovery-rotate`, `device-revoke`

## Local verification (Category 3)

```bash
bash scripts/staging-smoke-0.7.sh
```

## Owner checklist (hosted)

- Google OAuth client + redirect allowlist
- Email OTP SMTP sender + `{{ .Token }}` template
- Android deep link / callback URL
- Rate limits in GoTrue dashboard
- Deploy Edge bundle with service_role to project only (never in app)
