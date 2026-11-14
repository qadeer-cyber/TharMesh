# TharMesh identity lifecycle

This document is the design for account identity. Crypto storage follows it.
The mesh device key that already signs bundles is not replaced by login,
recovery of a different key, token expiry, or cloud sign-out.

## Three keys

1. **Mesh device key** (existing). ECDSA P-256 in `UserPrefs`
   (`identity_private_key_b64` / `identity_public_key_b64`). It signs bundles
   and is the ECDH key for `PeerKeyRing`. QR invites and `PeerTrustStore` pin
   this public key. It stays in app-private preferences because Android
   Keystore cannot import an existing software key into a non-exportable
   hardware key, and moving it would change the public key contacts already
   trust. `android:allowBackup="false"` stays set. Sign-out does not delete it.

2. **Account key** (new, separate). Another P-256 key created only when the
   user explicitly protects or restores an identity. It certifies device keys
   by signing `accountId`, the mesh public key, the device public key, the
   device id, a nonce, and the identity generation. It is not the mesh key.
   On disk it is sealed with an Android Keystore AES-GCM key
   (`tharmesh_account_wrap_v1`). If the Keystore refuses the wrap, the account
   private key is not written in plaintext. The recovery blob is the other copy.

3. **Device enrollment key** (new). An ECDSA P-256 key generated inside
   Android Keystore (`tharmesh_device_sign_v1`) with `PURPOSE_SIGN` only.
   The private key is non-exportable (`PrivateKey.getEncoded()` is null).
   It proves this install signed an enrollment request. It does not replace
   the mesh key. JVM tests use a software signer and label
   `hardwareNonExportable = false`. They do not claim a hardware keystore.

## States

Local authorization and the cloud credential are different fields.

| Local authorization | Meaning |
| --- | --- |
| `ABSENT` | No install record. Fresh install. |
| `OFFLINE_AUTHORIZED` | This install may run the mesh with no network. |
| `AWAITING_IDENTITY` | Cloud login succeeded and this phone has no mesh key yet. Mesh key generation is held. |

| Cloud credential | Meaning |
| --- | --- |
| `NONE` | No cloud session on this install. |
| `ACTIVE` | Access token not yet expired. |
| `TOKEN_EXPIRED` | Access token is past `expires_at`. Local authorization is unchanged. |
| `SIGNED_OUT` | The user ended the cloud session. Mesh key and Room data stay. |
| `UNREACHABLE` | The last cloud call failed. Local authorization is unchanged. |

An expired token is not a sign-out. Startup does not contact the network to
decide whether the mesh may run. `AccountSession.offlineAuthorized` ignores
token expiry on purpose.

Server revocation cannot reach a device that never connects again. The Offline
Mode screen says that in the UI. There is no always-on channel that could
enforce it, and adding one would break offline-first.

## Flows

### This install, first time, with a cloud account

Sign-up does not mint a mesh key. The user opens **Secure your identity** and
explicitly creates the mesh key and the account key. That pair is generation 1,
origin `ORIGINAL`. The recovery secret is shown once. The account key certifies
this device. The mesh public key is what QR trust will pin.

### This install, already in use (anonymous or Google)

`linkLegacy` stores the cloud account id next to the existing mesh key. It
does not call `CryptoIdentity.generate`. Messages, contacts, the Room database,
and the mesh public key stay. Origin becomes `LEGACY_UNLINKED` until the user
saves a recovery blob, which wraps the **existing** mesh private key. A server
fingerprint that differs from the local fingerprint is `REFUSE_SILENT_REPLACEMENT`.
Nothing is overwritten.

### Continue without an account

Unchanged. `UserPrefs.ensureProfile` plus the existing onboarding path. No
cloud call. The mesh does not read Supabase.

### New phone, cloud login (Method B starts here)

Login stores the account id and the token. It does not create a mesh key and
it does not copy a server-supplied fingerprint into a new key.
`holdMeshGeneration` is set, so `TharMeshApp.ensureMeshReady` returns before
`ensureIdentity` can mint one. The user must either restore or explicitly reset.

### Method A — old phone is available

The new phone builds an enrollment request: mesh public key, Keystore device
public key, device id, and a random nonce. The device key signs that request.
The request is a `tharmesh://enroll` QR, which `QrCodec` does not accept as an
invite (invites stay `tharmesh://invite`).

The old phone shows the fingerprint and requires an explicit Approve action.
Approve checks the device signature and that the account id matches, then the
account key signs a certificate over those exact public keys and the nonce.
The new phone accepts the certificate only when the account signature verifies
and the keys and nonce match the request it created. A substituted public key
fails. Skipping Approve fails. This is an in-person QR exchange, not a silent
network enrollment.

### Method B — recovery secret

The recovery secret is 128 bits from `SecureRandom`, shown as eight groups of
four hex digits. It is not a draw from the old 24-word list (that list, eight
words with replacement, is about 36 bits).

The secret is stretched with scrypt (RFC 7914), N = 2^14, r = 8, p = 1,
output 32 bytes, then AES-256-GCM. Memory is 128 × r × N = 16 MiB so a phone
with a small heap can still open the blob. The secret is high-entropy, so the
KDF is a second barrier on a stolen ciphertext, not the only entropy. N, r, and
p are stored on the blob so they can be raised later. PBKDF2-HMAC-SHA256 seals
still open so a blob made by the first account commit is not stranded.
PBKDF2 is not used for new seals. 100 000 SHA-256 iterations are not
memory-hard, and the platform KDF is missing on API 24.

The plaintext inside the blob is the account private key and the mesh private
key, plus the user id and generation. The server upload JSON has `kdf`, `n`,
`r`, `p`, `salt`, `iv`, and `ciphertext` only. A wrong secret leaves the local
record unchanged. If this phone already has a different mesh public key, restore
returns a conflict and does not overwrite it.

### Explicit reset

If the recovery secret and the old phone are both gone, the user can reset.
That mints a new mesh key and a new account key, increments generation, and
sets origin `RESET`. The UI states that contacts must scan the new QR and that
this key is not the previous one. `presentsAsOriginal` is false. Old messages
that were encrypted to the lost mesh key do not become readable.

### Cloud sign-out

`UserPrefs.signOut` keeps the mesh private key, public key, fingerprint, user
id, display name, terms acceptance, block list, and the authenticated flag.
It clears the cloud-session preference file (access token, refresh token) and
records `SIGNED_OUT`. It does not stop the mesh and it does not call
`BlockedContacts.onSignOut`. Room is untouched.

## What the cloud is allowed to store

Account id (the Supabase user UUID, immutable), handle, display name, optional
https avatar URL, email-verified flag, device public keys, the account-key
certificate, and the recovery ciphertext. Not message bodies. Not recovery
secrets. Not private keys.

Mesh discovery, routing, store-and-forward, history, and encryption do not call
the cloud.

## QR and IdentityStore

`IdentityStore` used to generate its own P-256 key for My QR. Bundles are
signed by the `UserPrefs` key, so the QR could show a different fingerprint.

`QrIdentityAlign` is the rule:

- If `UserPrefs` has a signing key, the QR shows that key and the profile
  user id. A different key already in `IdentityStore` is copied to
  `archived_public_key` / `archived_private_key` and is not deleted, and it
  does not replace the signing key.
- If `UserPrefs` has no key and `IdentityStore` does, that key is imported
  into `UserPrefs`. Nothing new is generated.
- If neither exists, and generation is not held, one key is created in
  `UserPrefs` and mirrored into `IdentityStore`. Opening My QR does not
  create a second key.
