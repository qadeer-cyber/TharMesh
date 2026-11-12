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

## Proof-of-possession migration

Apply both migrations in order in isolation. The earlier SQL fixture covers
migration 001 alone and should be run before 002. Migration 002 removes the
authenticated role's write RPC and introduces a read-only state RPC plus
service-role-only challenge and finish operations. Deploy
`supabase/functions/identity-proof` with the platform-managed server
credentials. The function independently checks the user's access token
through Supabase Auth. The client must encode signatures as unpadded
base64url IEEE P1363 (64 bytes for P-256), rather than ASN.1 DER.

The signed UTF-8 message consists of four newline-separated fields with
no trailing newline: `tharmesh:identity:register:v1`, the verified Auth
account UUID, the server nonce UUID, and the canonical SPKI public key.
Successful verification invokes the transaction that consumes the bound
challenge and attempts the canonical commit.

Run `node --test supabase/tests/identity-proof.test.mjs` on Node 24.
It exercises the real Web Crypto implementation with mocked HTTP.
Database replay, race behavior, and grants require isolated Supabase
integration testing. Keep admission disabled until Android integration,
legacy-client rollout controls, and device enrollment invariants are complete.
