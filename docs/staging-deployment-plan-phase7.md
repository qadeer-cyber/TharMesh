# Phase 7 — staging-only deployment plan (prepared, NOT executed)

**Target:** staging project `owhmuxueacjbljhpmpua` only. **No production action.**
**Status:** awaiting explicit owner approval. Nothing in this document has been applied.
**Prepared:** 2026-11-21, from the verified state below.

Owner decisions encoded: debug ref stays `example`; staging ref is
`owhmuxueacjbljhpmpua`; production ref stays unset; PR #57 stays DRAFT and PR #58
unmerged.

---

## 1. Verified current state of staging

Measured read-only with the publishable (anon) key. The OpenAPI root `/rest/v1/`
requires a **secret** key and was therefore not used; per-object probes need only the
publishable key.

| Check | Method | Result |
|---|---|---|
| DNS | `dns.google` A query, with a nonexistent-ref negative control | **resolves** (`172.64.149.246`, `104.18.38.10`); control still NXDOMAIN |
| HTTP / TLS | `GET /auth/v1/health` | **200** `{"version":"v2.198.0","name":"GoTrue"}` |
| Auth enforcement | same, no key | **401** `No API key found in request` |
| `public.profiles` / `devices` / `recovery_blobs` | `GET /rest/v1/<t>?limit=0` | **200 `[]`** → `supabase/schema.sql` **is applied** |
| nonexistent table control | same | 404 `PGRST205` |
| 4 zero-argument public RPCs | `GET /rest/v1/rpc/<f>` | **404 `PGRST202`, `hint: null`** → absent |
| 5 Edge Functions + nonexistent control | authenticated `POST /functions/v1/<f>` | **404 `NOT_FOUND`** for all, identical to control |

**Conclusion:** `schema.sql` is applied; **none of the 14 migrations are applied**; **no
Edge Function is deployed.**

A zero-argument function is the only sound probe here: `PGRST202` also fires for a
function that exists but takes arguments, so arity-bearing RPCs cannot be used to prove
absence. `tharmesh_identity_state()`, `tharmesh_list_devices()`,
`tharmesh_account_evidence()` and `tharmesh_recovery_generation()` are all zero-arg, and
all four are absent. Residual caveat: a stale PostgREST schema cache could in principle
hide applied objects; the base tables resolving while every function 404s makes that
unlikely, and `notify pgrst, 'reload schema'` would settle it during deployment.

### Error-code semantics used to classify Edge Functions

| Response | Meaning |
|---|---|
| `404` `{"code":"NOT_FOUND","message":"Requested function was not found"}` | no function of that slug exists — **nothing deployed** (observed) |
| `401` / `403` | deployed, rejected at the gateway or by the function's own check |
| `503` / boot error | deployed but the **bundle fails to start** (bad import, syntax, missing env) |

All five slugs returned the first row, identical to a slug that certainly does not
exist, so this is missing **functions**, not broken **bundles**. The authoritative
cross-check is the Management API or `supabase functions list`, which needs a personal
access token — a privileged credential that was deliberately **not** requested.

---

## 2. Migration audit — all 14, not only 006–008

Applied locally to a disposable PostgreSQL 16 cluster via the repository's own harness
(`supabase/tests/postgres-migrate-runner.sh fresh`, which applies
`postgres-bootstrap.sql` → `schema.sql` → platform defaults → migrations in order under
a tracking table), then re-applied to a second database for idempotency.

**Result: clean apply, fresh and re-apply. Category 3 suite 48/48 pass** —
including `postgres-migration-inventory`, `postgres-migration-privileges`,
`postgres-migration-authenticated-rpc-surface`, `postgres-fresh-upgrade`,
`recovery-bypass`, `schema-recovery-privileges`, `recovery-grant-atomic`,
`sensitive-grant-session`, `device-revoke-challenge-pg`, `device-revoke-approve-race`,
`device-revoke-race`, `device-lock-race`, `jwt-claims-session`, `device-revoke-crypto`,
`identity-postgres-multisession`.

### Dependency and compatibility notes

* **`supabase/schema.sql` is a hard prerequisite** — it creates `public.profiles`,
  `handles`, `devices`, `recovery_blobs`. Migration 0009 attaches policies to
  `public.recovery_blobs`, so the migrations cannot be applied to an empty project. It
  is already applied to staging, so the ordering requirement is satisfied.
* **Apply all 14 in order; do not cherry-pick.** The Edge Functions call RPCs whose
  *final* definitions land in 0002, 0006, 0007, 0009, 0010, 0011, 0012, 0013, but the
  remaining files (0001, 0003, 0004, 0005, 0008, 0014) create the tables, guards and
  privilege lockdowns those functions depend on. 0014 (`svc_execute_lockdown`) is the
  privilege hardening step and must not be skipped.
* **Signature churn is intentional:** every `drop function if exists` is immediately
  followed by a recreation with a new signature (0005, 0006, 0007, 0010, 0012, 0013).
  Dropping and recreating a function is not data-destructive.
* **Two in-place column renames**, both guarded and idempotent:
  * 0007 `tharmesh_private.auth_freshness`: `session_iat` → `token_iat`, wrapped in
    `exception when undefined_column then null`.
  * 0010 `tharmesh_private.sensitive_action_grants`: `session_sub` → `session_id`, with
    `exception when undefined_column then add column if not exists`.
  Both affect only `tharmesh_private` tables, which hold short-lived challenge/grant
  rows, and on a staging project with no migrations applied they simply take the
  add-column path.
* **No `truncate`, no `drop table`, no `drop column`, no migration-time `delete`.** The
  only `delete from` is inside a function body in 0002 (expired-challenge cleanup).
* 0010 requires the `pgcrypto` extension (`create extension if not exists pgcrypto`),
  available on Supabase.
* `plpython3u` is **local-harness only** — `supabase/tests/fixtures/verify_plpython_local.sql`
  must never be applied to a hosted project (already stated in `docs/deployment-0.5.md`).

---

## 3. Edge Function audit — all five

| Function | Required RPCs | Shared imports |
|---|---|---|
| `identity-proof` | `tharmesh_identity_challenge`, `tharmesh_identity_finish` | — |
| `device-enrollment` | `tharmesh_enrollment_begin_svc`, `_approve_svc`, `_finish_svc`, `_pending_get_svc`, `tharmesh_bootstrap_challenge_svc`, `tharmesh_bootstrap_device_svc`, `tharmesh_trusted_device_pubkey_svc` | — |
| `sensitive-auth` | `tharmesh_auth_fresh_record_svc`, `tharmesh_sensitive_grant_issue_svc` | `amr.ts`, `session.ts` |
| `recovery-rotate` | `tharmesh_recovery_rotate_commit_svc` | `amr.ts`, `session.ts` |
| `device-revoke` | `tharmesh_device_pubkey_lookup_svc`, `_revoke_challenge_issue_svc`, `_revoke_challenge_get_svc`, `tharmesh_device_revoke_svc` | `amr.ts`, `session.ts`, `p256.ts` |

All 16 RPCs were confirmed present with full signatures in the locally migrated database,
so the migration set closes the functions' dependency graph.

**Imports:** no external/remote imports at all — only relative `../_shared/*.ts`, which
`supabase functions deploy` bundles automatically. No import map is needed, and there is
no third-party supply-chain surface.

**Authorization, uniform across all five:**
1. non-`POST` → `405`;
2. missing `SUPABASE_URL` / `SUPABASE_ANON_KEY` / `SUPABASE_SERVICE_ROLE_KEY` → `503 unavailable` (fail closed);
3. no `Authorization: Bearer …` → `401 sign_in_required`;
4. the bearer token is **validated by GoTrue** (`GET /auth/v1/user` with the *anon* key), never trusted from its own claims;
5. **`email_confirmed_at` is mandatory** — an unconfirmed account is rejected;
6. the JWT `sub` must equal the validated user id, else `403 account_mismatch`;
7. `sensitive-auth`, `recovery-rotate` and `device-revoke` additionally require a parseable `amr` auth-event timestamp and session id, else `403 invalid_session`, and enforce a 900 s freshness window.

**Service-role use:** the service-role key is used **only** for server-side `POST
/rest/v1/rpc/<name>` calls from inside the function, and is never echoed to the client.

**JWT verification:** there is no `supabase/config.toml`, so `supabase functions deploy`
applies the platform default `verify_jwt = true`. The functions' own `401
sign_in_required` is therefore a second, independent gate. Deploy with the default; do
**not** pass `--no-verify-jwt`.

**Item 6 dependency chain (important):** because every function requires
`email_confirmed_at`, **working email delivery is a hard prerequisite for any end-to-end
enrollment test**, not an optional extra. Until OTP/SMTP delivers, every function will
answer `401 sign_in_required` even when correctly deployed.

---

## 4. `docs/deployment-0.5.md` — required sequence, and why it cannot be followed verbatim

That document says: *after 001–005, apply 0006 → 0007 → 0008, then deploy
`identity-proof`, `device-enrollment`, `sensitive-auth`, `recovery-rotate` "with
`SUPABASE_SERVICE_ROLE_KEY`", and keep `identity_admission.enabled = false`.*

Four defects make it unsafe to follow as written:

1. **It stops at 0008.** Migrations 0009–0014 now exist and the functions need them
   (`device-revoke` needs 0012/0013; `recovery-rotate` needs 0010;
   `sensitive-auth` needs 0010). Following the doc literally leaves six migrations
   unapplied and three functions permanently broken.
2. **`device-revoke` is missing** from the function list.
3. **"with `SUPABASE_SERVICE_ROLE_KEY`" is wrong and dangerous today.** The code reads
   `SUPABASE_URL`, `SUPABASE_ANON_KEY` and `SUPABASE_SERVICE_ROLE_KEY` from
   `Deno.env`, and Supabase injects all three into the Edge runtime automatically. No
   secret needs to be set, and the service-role key must not be pasted anywhere.
4. **`schema.sql` is not listed** as the prerequisite it is, and the numbering repeats
   step "3".

Updating that document is a follow-up; this plan supersedes it for Phase 7.

---

## 5. Proposed staging-only execution sequence — requires owner approval

Run from a checkout of the 0.8 base, authenticated as the project owner
(`supabase login`; a personal access token stays on the operator's machine and is never
committed or pasted into CI).

```bash
# Gate A — confirm the target, so nothing can be aimed at production by accident
supabase projects list                       # expect owhmuxueacjbljhpmpua = staging, ACTIVE_HEALTHY
export TM_REF=owhmuxueacjbljhpmpua

# Gate B — rehearse on a disposable local database first (already done, repeatable)
bash supabase/tests/postgres-ensure-test-role.sh
bash .github/scripts/ci-postgres-tests.sh    # expect 48/48

# Gate C — migrations, staging only. Review the plan before applying.
supabase link --project-ref "$TM_REF"
supabase db push --dry-run                   # APPROVAL GATE: read the statement list
supabase db push                             # applies schema-tracked migrations in order
notify_reload() { :; }                       # PostgREST reloads automatically after DDL

# Gate D — functions, staging only, platform-default JWT verification
supabase functions deploy identity-proof     --project-ref "$TM_REF"
supabase functions deploy device-enrollment  --project-ref "$TM_REF"
supabase functions deploy sensitive-auth     --project-ref "$TM_REF"
supabase functions deploy recovery-rotate    --project-ref "$TM_REF"
supabase functions deploy device-revoke      --project-ref "$TM_REF"
supabase functions list --project-ref "$TM_REF"

# Gate E — keep admission closed until the gates below pass
#   identity_admission.enabled must stay false (docs/deployment-0.5.md step 3)
```

Nothing above may be run against any ref other than `$TM_REF`, and no `db reset`,
`db remote commit` or production link is part of this plan.

**Risk notes:** `supabase db push` on a project whose migration-tracking table is empty
but whose `schema.sql` objects already exist is the normal case here — the migrations
are written with `if not exists` / guarded renames and were proven idempotent locally.
Take a backup snapshot from the dashboard before Gate C anyway; staging currently holds
no rows in `profiles`, `devices` or `recovery_blobs`, so the blast radius is minimal.

---

## 6. Post-deployment verification to run after approval (item 8)

Read-only and non-destructive; no account creation without separate authorization.

1. **Presence:** `supabase functions list` shows all five; the four zero-arg RPCs stop
   returning `PGRST202`.
2. **Authentication enforcement (negative cases, no account needed):**
   * `POST /functions/v1/<f>` with no `Authorization` → expect `401 sign_in_required`
     (not `404`, which would mean still undeployed);
   * `GET` instead of `POST` → `405 method_not_allowed`;
   * `Authorization: Bearer not-a-jwt` → `401`;
   * a JWT for a different subject → `403 account_mismatch`;
   * a stale `amr` timestamp on `sensitive-auth` → `403 invalid_session`.
3. **Privilege boundary:** the service-role key must never appear in any response body;
   `anon`/`authenticated` must not be able to call the `_svc` RPCs directly — covered
   locally by `postgres-migration-privileges` and
   `postgres-migration-authenticated-rpc-surface`, to be re-checked against staging with
   the publishable key (expect permission denied, not success).
4. **Runtime health:** `supabase functions logs <f>` shows a clean boot, no
   `BOOT_ERROR`.
5. **End-to-end enrollment:** blocked until email delivery works (§3) **and** the owner
   authorizes a test account.

---

## 7. Signed-QA CI branch restriction (documented, NOT changed)

`.github/workflows/android.yml`, job `signed-qa`:

```yaml
if: github.event_name == 'push'
    && github.repository == 'qadeer-cyber/TharMesh'
    && github.ref == 'refs/heads/cursor/universal-account-0939'
    && vars.THARMESH_QA_SIGNING_ENABLED == 'true'
```

Four conditions must all hold, and the third pins the job to a **single branch**:

| Condition | Effect |
|---|---|
| `github.event_name == 'push'` | never runs on a pull request |
| `github.repository == 'qadeer-cyber/TharMesh'` | never runs on a fork |
| `github.ref == 'refs/heads/cursor/universal-account-0939'` | runs **only** on PR #52's branch |
| `vars.THARMESH_QA_SIGNING_ENABLED == 'true'` | currently unset, so the job is skipped even there |

It also declares `environment: tharmesh-qa-signing`, so a GitHub Environment of that
name gates it in addition to the four conditions.

**Consequences, stated rather than worked around:**

* Signed-QA evidence **cannot** be produced on `cursor/tharmesh-0.8-field-mesh-5aa9`,
  `cursor/tharmesh-0.9-engineering-5aa9`, or this PR's branch. Every observed run of
  this job on those branches is `skipped`, which is the correct behaviour and not a
  failure.
* The `THARMESH_STAGING_PROJECT_REF` forwarding added to `signed-qa` in this PR is
  therefore correct but **dormant** on the current branches: it takes effect the first
  time the job actually runs.
* Phase 8 (signed-QA upgrade) consequently needs an explicit owner decision about where
  that job may run. **No trigger change is included in this PR.** The options, for the
  owner to choose between, are: push the QA-signing work to
  `cursor/universal-account-0939`; add the target branch to the `github.ref` condition;
  or convert the condition to a `workflow_dispatch` input so QA signing is run
  deliberately rather than on every push. Each widens who can produce a signed artifact,
  so none should be applied silently.
