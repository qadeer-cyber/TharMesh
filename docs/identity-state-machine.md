# Identity and device state machine (TharMesh 0.4)

This document is the single reference for cloud identity, mesh keys, devices, and
recovery. Production admission stays **disabled** until every gate in
`docs/deployment-0.4.md` is satisfied.

## Actors

| Actor | Holds |
| --- | --- |
| GoTrue session | Verified `auth.users.id`, JWT access token |
| Mesh identity | ECDSA P-256 keypair in app storage (UserPrefs / Keystore staging) |
| Account key | Separate signing key for enrollment approval (optional until protect) |
| Device key | Per-install enrollment signer (Android Keystore when available) |
| Recovery secret | Offline phrase; never stored on server |
| Recovery blob | Scrypt-sealed ciphertext on server |

## Server states (`tharmesh_identity_state`)

| State | Meaning | Client may |
| --- | --- | --- |
| `legacy_unknown` | No canonical row for this auth user | Keep local mesh; no server-first registration |
| `unprovisioned` | Admitted row, no mesh key committed | Stage key + proof path when admission is on |
| `active` | Canonical mesh public key committed | Match local key or enter recovery |
| `recovery_required` | Mismatch, prior devices, or missing admission | Recovery or trusted enrollment only |

Automatic insert into `tharmesh_private.canonical_identities` happens only when
`identity_admission.enabled` is true (default **false**).

## Client transitions (Track B, PR #52)

1. **New registration (Google / email OTP)** — Welcome → verified session →
   `IdentityProvisioning.activate` with staged mesh key (Keystore slot per account).
   Success → canonical user id + Chats. `Retry` → same staged key, no regeneration.
   `RecoveryRequired` → recovery or legacy reconcile screen, never silent replace.
2. **First device (canonical)** — Challenge → Edge Function P-256 proof →
   `tharmesh_identity_finish` in one transaction. Not available while admission is off.
3. **Existing device auth** — Password / OTP / Google → session; if local mesh
   matches cloud record → Chats offline-capable.
4. **Additional device** — New phone: enrollment QR + explicit approval on trusted
   device. Certificate must match request (account, mesh, device, nonce). Replay of
   nonce rejected locally. Canonical accounts cannot use direct `devices` PostgREST
   insert (migration 003).
5. **Legacy reconciliation** — `LegacyReconciliation.decide`: authenticated user,
   inspect server state + local mesh + reset flags; never guess from partial data.
6. **Recovery activation** — Phrase opens sealed blob; wrong phrase fails closed.
7. **Recovery rotation** — New phrase + new seal + upload; old ciphertext copies
   remain decryptable with the old secret (documented residual risk).
8. **Revocation** — Server/device record updates are future trusted-RPC work;
   UI must not claim remote wipe of keys or messages on other phones.

## Privileged operations

| Operation | Caller | Must verify |
| --- | --- | --- |
| `tharmesh_identity_challenge/finish` | service_role (Edge Function) | JWT subject, nonce, key shape, challenge TTL |
| `tharmesh_identity_state` | authenticated | `auth.uid()` |
| `tharmesh_provision_identity` | service_role only (post-002) | N/A for client |
| Device directory write | authenticated | Blocked when `tharmesh_uses_canonical_identity()` |
| Recovery upload | authenticated | RLS + scrypt-only blob policy |

No service-role key in the Android app.

## Interrupted operations

- Staged mesh identity in `AndroidAccountKeyBox` survives process death until
  activation succeeds or the user clears app data.
- A lost HTTP response after server commit must retry with the **same** staged public
  key (`IdentityProvisioningTest.lostResponseReusesOnlyTheCommittedKey`).
- OTP / enrollment cooldowns use local timestamps; no blind overwrite on timeout.
