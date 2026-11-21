# Phase 7 — staging-only deployment plan (prepared, NOT executed)

**Target:** STAGING = `uxntqgspxrepvhelhqfs` (**TharMesh-Staging**) — the only
authorized target.
**PROTECTED PRODUCTION:** `owhmuxueacjbljhpmpua` (**tharmesh**) — do not modify its
database, functions, configuration or data.
**Status:** awaiting the deployment approval gate. Nothing has been applied to any
hosted project.
**Prepared:** 2026-11-21. Project assignments confirmed by the owner after the
identity halt recorded in section 0.

Owner decisions encoded: debug ref stays `example`; staging ref is
`owhmuxueacjbljhpmpua`; production ref stays unset; PR #57 stays DRAFT and PR #58
unmerged.

---

## 0. RESOLVED — project identity (was a halt)

Authenticated `supabase projects list` (CLI 3.2.0) returns **two** projects:

| Ref | Name | Region | Status | Created |
|---|---|---|---|---|
| `owhmuxueacjbljhpmpua` | **tharmesh** | ap-northeast-2 | ACTIVE_HEALTHY | 2026-11-11 |
| `uxntqgspxrepvhelhqfs` | **TharMesh-Staging** | ap-northeast-1 | ACTIVE_HEALTHY | 2026-11-13 |

The repository's `SUPABASE_URL`, every probe in this document, and the owner's stated
target all point at `owhmuxueacjbljhpmpua` — which is named plainly **`tharmesh`**, not
`TharMesh-Staging`. A separately named staging project exists and is referenced nowhere
in the repository or in CI.

Nothing in the repository records which ref is which: neither ref appears in any tracked
file (the only occurrences are in this document and in `supabase/.temp/`, which is local
CLI state and now gitignored). So the naming is the only available signal, and it points
the opposite way from the assumption this plan was built on.

**Consequences, which is why this is a halt and not a note:**

1. Applying 14 migrations to `owhmuxueacjbljhpmpua` may be a mutation of the **primary
   or production** project. That is the one outcome this whole plan exists to prevent.
2. Setting `THARMESH_STAGING_PROJECT_REF=owhmuxueacjbljhpmpua` — the value currently
   instructed — compiles that ref into `BuildConfig.SUPABASE_STAGING_PROJECT_REF`.
   `SupabaseEnvironmentGuard` then **permits `qa` and `staging` builds against it**. If
   `owhmuxueacjbljhpmpua` is production, signed QA artifacts would be pointed at
   production while every guard still reports fail-closed, because the guard enforces
   *ref equality*, not *environment intent*.
3. Conversely, if `owhmuxueacjbljhpmpua` genuinely is staging and `TharMesh-Staging` is
   a newer empty replacement, then the repository is correct and only the project name
   is misleading — but that cannot be established from outside.

**Supporting (not decisive) evidence:** `public.profiles`, `public.devices` and
`public.recovery_blobs` on `owhmuxueacjbljhpmpua` all return `200 []` through the
publishable key, so it holds no rows in the user-facing tables. That argues against it
being a populated production database, but says nothing about which project is *intended*
for staging, and RLS could mask rows from the anon role.

`uxntqgspxrepvhelhqfs` was not inspected at the time: the owner had authorized one ref,
and reading another project — even read-only — was outside that authorization. It has
since been authorized and audited (section 9).

### Owner decision (received 2026-11-21)

| Role | Project | Ref |
|---|---|---|
| **STAGING** — only authorized target | TharMesh-Staging | `uxntqgspxrepvhelhqfs` |
| **PROTECTED PRODUCTION** — do not modify | tharmesh | `owhmuxueacjbljhpmpua` |

The halt was correct: the repository is pointed at **production**. Required changes,
none of which have been applied:

1. Repository variable `SUPABASE_URL` → `https://uxntqgspxrepvhelhqfs.supabase.co`.
2. Repository variable `SUPABASE_ANON_KEY` → the publishable key **of TharMesh-Staging**.
   Never reuse the production project's key.
3. Repository variable `THARMESH_STAGING_PROJECT_REF` → `uxntqgspxrepvhelhqfs`.
4. `THARMESH_DEBUG_PROJECT_REF` stays `example`; the production ref stays unset.

Until (1)-(3) are done, CI builds still point at production, and the guard would permit
a `qa` build against production if `THARMESH_STAGING_PROJECT_REF` were set to the
production ref. The CLI's auto-created `supabase/.temp/linked-project.json`, which had
linked this checkout to the production ref, has been deleted.

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
# Gate A — confirm the target (DONE, and it FAILED: see section 0)
supabase projects list                       # returned two projects; the target is named "tharmesh"
export TM_REF=<ref the owner confirms is staging>

# Gate B — rehearse on a disposable local database first (already done, repeatable)
bash supabase/tests/postgres-ensure-test-role.sh
bash .github/scripts/ci-postgres-tests.sh    # expect 48/48

# Gate C — migrations, staging only. Review the plan before applying.
# CLI 3.2.0: target remotes with --remote; `supabase link` is not required and
# --remote=<ref> is explicit, so no ambient linked-project state can misdirect it.
supabase migration list --remote="$TM_REF"   # the REAL history table (see section 8)
supabase db push --remote="$TM_REF" --dry-run  # APPROVAL GATE: read the statement list
supabase db push --remote="$TM_REF"          # applies schema-tracked migrations in order

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

---

## 8. Second blocker — no direct Postgres path from the cloud container

Independent of section 0, the read-only steps that need a database connection cannot run
from this environment.

| Attempt | Result |
|---|---|
| `supabase projects list` (Management API over HTTPS) | **works** |
| `supabase migration list --remote=owhmuxueacjbljhpmpua` | **hangs, killed at 60 s (exit 124)**, no output |
| `supabase migration list --remote=… --debug` | same; no connection diagnostics emitted before the timeout |

Cause: the CLI opens a direct PostgreSQL connection for history, dump and push.
`db.owhmuxueacjbljhpmpua.supabase.co` publishes **AAAA only** (no A record), and this
container's egress is an HTTPS forward proxy — it carries `CONNECT` to allowed hosts on
443 and cannot carry raw TCP on 5432/6543 at all. Allowing more hostnames cannot fix
this; the limitation is the protocol, not the allowlist.

**Therefore the following must run from the owner's machine** (or any host with ordinary
outbound TCP), not from this session:

```bash
supabase login                                   # browser flow, interactive
supabase migration list --remote=<staging-ref>   # item 3: the real history table
supabase db dump --remote=<staging-ref> -f staging-schema.sql          # item 4: backup
supabase db dump --remote=<staging-ref> --data-only -f staging-data.sql
sha256sum staging-*.sql                          # record the digests
supabase db push --remote=<staging-ref> --dry-run # item 5
```

What this session **can** still do over HTTPS once the identity question is settled:
`projects list`, `functions deploy`, `functions list`, `functions logs`, and all
REST/Auth verification including the negative authentication cases in section 6. So
Edge Function deployment and verification are feasible from here; **migrations are not**.

Note on backups: the dashboard's automatic daily backup depends on the project's plan,
and a logical `db dump` is the no-cost, verifiable alternative — but it needs the same
direct connection, so the backup evidence this plan requires before any mutation must
also be produced from the owner's machine.


---

## 9. Staging audit (`uxntqgspxrepvhelhqfs`), read-only over HTTPS

Performed with the Management API SQL endpoint,
`POST /v1/projects/{ref}/database/query` with `"read_only": true`, which executes as
the `supabase_read_only_user` role.

| Check | Result |
|---|---|
| Project | TharMesh-Staging, ap-northeast-1, ACTIVE_HEALTHY, created 2026-11-13 |
| Postgres | **17.11** (the local verification cluster is 16.15 — see the caveat below) |
| `public` schema tables | **none** |
| `public` schema functions | **0** |
| Non-system schemas | `auth`, `extensions`, `graphql`, `graphql_public`, `public`, `realtime`, `storage`, `vault` — stock Supabase; no `tharmesh_private` |
| `supabase_migrations` schema | **does not exist** — no migration history at all |
| `pgcrypto` | available **and installed**, 1.3 (migration 0010 needs it) |
| `uuid-ossp` | installed, 1.1 |
| Database branches | `[]` — no Supabase branching configured |

The project is pristine: nothing of TharMesh's has ever been applied to it.

**Version caveat:** staging is PostgreSQL 17, the local verification cluster is 16, and
production is a third instance. Nothing in these migrations uses 16/17-divergent
syntax, but the local green result is evidence *for PostgreSQL 16*. The dry-run against
staging is what confirms 17.

### Item 8 — database access without direct PostgreSQL, solved

`db.<ref>.supabase.co` is AAAA-only and this container's egress is an HTTPS proxy, so
`supabase migration list`, `db dump` and `db push` all hang (confirmed: 60 s timeout,
exit 124). The Management API SQL endpoint is the working alternative:

```bash
curl -sS -X POST \
  -H "Authorization: Bearer $(cat ~/.supabase/access-token)" \
  -H "Content-Type: application/json" \
  --data '{"query":"select 1;","read_only":true}' \
  "https://api.supabase.com/v1/projects/uxntqgspxrepvhelhqfs/database/query"
```

`"read_only": true` runs as `supabase_read_only_user` and cannot mutate. Dropping that
flag is what would make it a write path — which is **not** to be done without approval.
This gives a complete read-only audit capability from this session, and, if approved, an
HTTPS route to apply SQL without direct PostgreSQL.

### Item 9 — automatic deployments

`GET /v1/projects/uxntqgspxrepvhelhqfs/branches` returns `[]`, so **no Supabase
branching and therefore no branch-based automatic migration deploys are configured**.
The dashboard project card does show a GitHub badge reading `qadeer-cyber/TharMesh`, so
the repository is connected to the project; the Management API exposes no endpoint for
that connection's settings, so **one item remains for the owner to confirm in the
dashboard**: Project Settings → Integrations → GitHub, specifically whether
"deploy migrations on push" (and the configured supabase directory/branch) is enabled.
If it is, merging anything into the watched branch could auto-apply migrations — which
would bypass this approval gate entirely.

---

## 10. Items 6-7 — `db push` alone CANNOT bootstrap staging

`supabase/schema.sql` is not a migration. Its own header says "Run in the Supabase SQL
editor", and that is how production got its `profiles` / `handles` / `devices` /
`recovery_blobs` tables — by hand, untracked. `supabase db push` only applies files in
`supabase/migrations/`, so on a project that never had schema.sql pasted in, the base
tables never appear.

**Verified locally, not inferred.** A disposable PostgreSQL database was given the
platform emulation only (roles + `auth` schema, no schema.sql), then the migrations were
applied in order:

```
ok    202611120001_identity_provisioning.sql
ok    202611120002_identity_proof.sql
FAIL  202611120003_identity_device_guard.sql
      ERROR:  relation "public.devices" does not exist
```

So a migration-only push against the empty staging project would **record 0001 and 0002
as applied, then fail** — leaving a half-written migration history that needs exactly
the repair this plan forbids doing automatically.

### The fix: a tracked base-schema migration

`supabase/migrations/202611120000_base_schema.sql` is generated from
`supabase/schema.sql` with one transformation: each `create policy` is preceded by
`drop policy if exists`, because `create policy` is not idempotent and the file must be
safe both on a fresh project and on one where schema.sql was already applied by hand.

Verification performed:

| Check | Result |
|---|---|
| Migrations-only apply, no schema.sql step (simulates `db push` on empty staging) | **all 15 applied cleanly** |
| Resulting schema vs the harness path (schema.sql + migrations) | **identical** — 59/59 objects (tables, functions with full signatures, policies), the only delta being the harness's own `tharmesh_private.schema_migrations` bookkeeping table |
| Repository inventory guard | updated from 14 to 15 files and 0001-0014 to 0000-0014; it correctly rejected the new file until updated |
| Drift guard | `supabase/tests/schema-bootstrap-parity.test.mjs` regenerates the migration from schema.sql and fails if they diverge; mutation-tested (editing schema.sql alone fails 1 of 3) |
| Category 3 suite | re-run after the change |

Note the two tracking tables are different and independent: the repository harness
records applied files in `tharmesh_private.schema_migrations`, while `supabase db push`
uses `supabase_migrations.schema_migrations`. Staging has neither, so a push would
create the latter and record all 15.

### Raw re-application is not idempotent, and that is by design

Applying the migration files a second time *without* a tracking table fails at 0001
(`relation "identity_admission" already exists`): migrations 0001-0014 use bare
`create table`. Re-run safety comes from the tracking table, which both the harness and
`db push` consult. This is pre-existing repository behaviour, unchanged here, and is
only a hazard if someone applies the files by hand.
