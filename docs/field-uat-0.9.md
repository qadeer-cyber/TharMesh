# TharMesh 0.9 — Physical field UAT procedure

**Status:** **NOT EXECUTED** (requires owner phones; JVM/simulation is not a substitute).

**APK source:** CI artifact `tharmesh-app-debug` from Android CI on branch `cursor/tharmesh-0.9-engineering-5aa9` (record run URL + SHA-256 from engineering report).

## Prerequisites

- Three Android devices (A, B, C), Bluetooth + Location on, TharMesh debug APK installed (same CI build on all).
- No cellular/Wi‑Fi internet required for mesh legs.
- Pair contacts via QR between A↔B, B↔C; **do not** pair A↔C for relay test.

## A↔B direct (20 messages)

1. Place A and B within Nearby range; open chat A→B.
2. Send 20 unique plaintext messages from A; record header states (Queued → Sending → Sent/Delivered only after peer ack).
3. Verify B receives all 20 in order, no duplicates, no wrong sender.
4. **Pass criteria:** 20/20 delivered with signed confirmation; zero duplicate UI rows.

## A↔B↔C relay (20 messages)

1. A and C out of direct range; B relay midway.
2. From A send 20 messages to C; confirm A shows **Relaying** when C not directly connected.
3. Confirm A and C cannot connect directly during test (disable pairing / range).
4. **Pass criteria:** 20/20 at C; B never shows plaintext in logs (see evidence script).

## Disconnect / restore

1. With pending messages, force-stop B; restore B and mesh service.
2. Pending must recover without duplicate delivery.

## Force-stop / background

1. Force-stop A, B, C one at a time while messages pending; reopen.
2. Screen off 5 minutes with pending queue; verify delivery on unlock.

## Evidence collection (adb)

```bash
export THERMESH_UAT_LOG_TAG=TharMesh MeshEngine NearbyTransport
adb logcat -c
adb logcat -s "$THERMESH_UAT_LOG_TAG" MeshLog:* > field-uat-logcat.txt
adb shell dumpsys activity services tharmesh.app | tee field-uat-fgs.txt
```

Archive: `field-uat-logcat.txt`, screenshots of chat headers, `ci-evidence/` if using debug build.

## Scripts in repo

- `scripts/battery-measurement-60m.sh` — owner lab batterystats (**NOT EXECUTED** in CI).
- `scripts/api-emulator-evidence.sh` — **simulation** API 33–35 install/permission evidence only.