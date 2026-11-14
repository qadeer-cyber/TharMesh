# CI revoke security case → test mapping (Category 3 / 4)

| Case | Test file | Test name |
|------|-----------|-----------|
| Forged key | `device-revoke-crypto.test.mjs` | device-revoke edge rejects bad challenge proofs |
| Wrong target | `device-revoke-crypto.test.mjs` | (same; wrongTargetSig branch) |
| Wrong account | `device-revoke-race.test.mjs` | cross-account revoke denied |
| Wrong session | `device-revoke-crypto.test.mjs` | (same; wrongSessionSig branch) |
| Expired challenge | `device-revoke-challenge-pg.test.mjs` | revoke challenge replay and expiry… |
| Replayed challenge | `device-revoke-challenge-pg.test.mjs` | (same) |
| Revoked signer | `identity-postgres-multisession.test.mjs` | revocation and authenticated device list |
| Wrong private key | `device-revoke-crypto.test.mjs` | forgedSig vs DB pubkey |
| Approve ‖ revoke | `device-revoke-approve-race.test.mjs` | approve vs revoke device A… |
| 2 simultaneous revokes | `device-revoke-race.test.mjs` | two devices, two simultaneous revokes… |
| 3 simultaneous revokes | `device-revoke-race.test.mjs` | three devices, three simultaneous revokes… |
| Duplicate same-device revoke | `device-revoke-race.test.mjs` | same device revoked twice concurrently |
| Revoke during enrollment finish | `device-lock-race.test.mjs` | revoke races enrollment_finish under account lock |
| Wrong session on grant | `sensitive-grant-session.test.mjs` | sensitive grants: session, action, expiry, replay |
| Mis-matched challenge issue | `device-revoke-challenge-pg.test.mjs` | wrong challenge target not revocable… |
| Last-device protection | `identity-postgres-multisession.test.mjs` | last active device revoke denied |
| Direct authenticated revoke RPC fail-closed | `identity-postgres-multisession.test.mjs` | authenticated direct tharmesh_device_revoke fail-closed |
