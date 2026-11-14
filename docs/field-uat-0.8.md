# TharMesh 0.8 — Physical device UAT protocol

**Status: NOT EXECUTED** in Cloud Agent runs (no paired physical radios in CI).

This document is the owner-executable field acceptance package. **Simulated JVM / loopback / emulator evidence does not satisfy these categories.**

## Prerequisites

- Three Android phones (A, B, C) on supported API (min 24; match release target).
- Debug or signed-QA APK from a tagged CI artifact; record **file SHA-256** and **cert SHA-256** before install.
- Bluetooth on; grant **Nearby** + **Location** per `NearbyPermissions` prompts.
- **No internet** required for mesh sections (airplane mode or Wi‑Fi/cellular off after accounts provisioned, if testing offline-only path).

## Category P1 — Two-phone direct (A ↔ B)

| Step | Action | Evidence to capture |
|------|--------|---------------------|
| 1 | Install same build on A and B; complete anonymous or staging account as needed | Screenshot of About → commit hash |
| 2 | Trust/bootstrap peers (QR or in-app flow) | UI dump / photo of verified fingerprint |
| 3 | Send 10 text messages A→B and B→A | Export diagnostics JSON from both |
| 4 | Force-stop app on B; send from A; relaunch B | Message appears without duplicate rows |
| 5 | Walk out of range 2 min; return | Queued messages deliver; status shows queued→sent/delivered |

**PASS:** No duplicate conversation entries; delivery states truthful; no plaintext in logcat for bodies.

## Category P2 — Three-phone relay (A — C — B), no A↔B direct path

| Step | Action | Evidence |
|------|--------|----------|
| 1 | Place C between A and B so A and B are **not** in direct radio range | Photo of physical layout |
| 2 | A sends to B userId | B receives within agreed SLA; C relay counters increase in diagnostics |
| 3 | Kill app on C mid-relay; restart C | Message still delivers after recovery |
| 4 | Verify ACK/read on A | ACK path matches message id |

**PASS:** B receives without A–B direct transport; relay bytes in diagnostics on C.

## Category P3 — Background / battery (optional extended)

| Step | Action | Evidence |
|------|--------|----------|
| 1 | Screen off 15 min with mesh idle | Battery historian / `dumpsys batterystats` excerpt |
| 2 | OEM battery saver on | User-visible explanation when discovery paused |

**PASS:** No silent “delivered” while app force-stopped.

## Artifacts to upload per run

- `adb logcat` (redact tokens)
- Diagnostics export from each device
- APK SHA-256 + cert from `apksigner verify --print-certs`
- Photos or screen recordings of chat state

Label all uploads **`PHYSICAL_EVIDENCE`** in the 0.8 engineering report matrix.