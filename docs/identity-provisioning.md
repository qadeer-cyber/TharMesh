# Canonical identity rollout

The migration adds an authenticated RPC with a per-account row lock and an
insert-only admission path from the Auth signup trigger. It has no reset
operation. A missing row is legacy unknown. No historical accounts are
backfilled. Recovery and enrolled-device records block first registration.
The RPC returns the full canonical key so a client can compare it exactly.

## Owner steps

1. Apply `supabase/schema.sql` prerequisites if they are missing, then apply
   `supabase/migrations/202611120001_identity_provisioning.sql` as the migration
   owner in an isolated Supabase project. Run
   `supabase/tests/identity_provisioning.sql` there as the migration owner.
2. Keep admission disabled. Android does not yet consume the RPC. Before
   enabling it, require provisioning-aware clients for new cloud accounts
   and verify that legacy clients cannot create a local identity for newly
   admitted accounts outside this protocol.
3. Before production activation, verify two concurrent requests with distinct
   keys, interrupted activation and same-key retry, server authorization,
   device storage, and legacy recovery. The RPC validates wire shape only.
   Cryptographic key validation and possession proof need completion before
   admission is enabled.

To pause rollout, run
`update tharmesh_private.identity_admission set enabled = false where singleton;`
as the migration owner. This preserves existing canonical records and stops
new admissions and initial commits. Do not drop committed records after
rollout. No production migration or policy changes were applied by this PR.
