# TharMesh — Offline Mesh Communication System

A delay-tolerant, multi-hop offline communication system designed for low-connectivity environments.

TharMesh lets Android phones exchange messages without any internet, cellular data, or central server. Devices talk directly over Bluetooth and Wi-Fi using Google Nearby Connections, relay each other's messages through a multi-hop mesh, and store-and-forward bundles until they reach their destination — even hours later, when a peer comes back into range.

---

## Table of Contents

- [Demo](#demo)
- [Key Features](#key-features)
- [How It Works](#how-it-works)
- [Architecture Overview](#architecture-overview)
- [Real-World Use Cases](#real-world-use-cases)
- [Installation and Usage](#installation-and-usage)
- [Field Testing Guide](#field-testing-guide)
- [Diagnostics](#diagnostics)
- [Known Limitations](#known-limitations)
- [Roadmap](#roadmap)
- [Contributing](#contributing)
- [License](#license)

---

## Demo

A short demo video walking through device pairing, a two-phone message exchange, and a three-phone relay test will be linked here once recorded.

> Demo video: _placeholder — coming soon._

### Test it yourself

1. Download the latest debug APK from the [Releases](../../releases) page.
2. Install it on **two or more** Android 7.0+ devices.
3. Grant the Bluetooth, Location, and Nearby permissions when prompted.
4. Launch TharMesh on each device, register with a display name, and follow the [Field Testing Guide](#field-testing-guide).

No SIM card, no Wi-Fi network, and no internet connection is required at any point.

---

## Key Features

The features listed below are implemented in the current build and covered by unit or integration tests:

- **Multi-hop relay messaging.** Bundles are forwarded through intermediate peers until they reach the destination. Loop prevention via a seen-set; duplicate floods bounded by a bundle-id LRU cache.
- **Store-carry-forward delivery.** Bundles are persisted in Room and re-broadcast on every retry tick, so a message sent while a peer is out of range is delivered as soon as the peer (or any relay) reconnects.
- **Truthful message status pipeline.** `QUEUED → SENDING → SENT → DELIVERED → READ`, with rank-protected status transitions that prevent regression on concurrent acks. A `FAILED` state is surfaced for transport-level rejections and supports manual retry.
- **Exponential retry with jittered backoff.** Per-bundle policy: 5s → 10s → 20s → 40s → 60s with ±20 % jitter and a 60s ceiling. No fixed cap on attempts — DTN principle: a peer may legitimately return after hours.
- **Aggressive SOS path.** Priority bundles use a compressed retry curve (1s → 2s → 4s → 8s, no jitter) and bypass per-peer pacing, so an SOS broadcast fans out at full rate.
- **TTL and hop-count enforcement.** A per-bundle TTL (default 24 h) and hop counter are checked at every receive, forward, and retry path. Expired or hop-exhausted bundles are dropped at the source.
- **Cryptographic identity.** Each device generates an ECDSA P-256 keypair on first launch. Bundles are signed (`SHA256withECDSA`); peers exchange public keys via QR invite codes and verify signatures before accepting bundles.
- **Per-peer send pacing.** A 40 ms minimum gap between sends to the same peer prevents the Nearby send buffer from being overwhelmed under burst conditions.
- **Peer-churn debounce.** Rapid PeerConnected events for the same peer are coalesced into a single trailing retry-flush (1.5 s window) to avoid retry storms on flaky links.
- **Permission and connection visibility.** A status banner surfaces missing permissions, Bluetooth-off, and Location-off states, plus searching / connected / no-devices indicators when the mesh is healthy.
- **Diagnostics and Field Test Mode.** An on-device diagnostics screen exposes counters (peers seen, retries, paced sends, TTL drops, stuck-sending recoveries) and exports a JSON snapshot via the share intent. Field-test toggles re-shape the retry curve to make behaviour visible in seconds rather than minutes.
- **Loopback transport for tests.** A purely in-process transport implementation drives 129 unit tests covering routing, retries, pacing, and crash recovery without requiring a real radio.

---

## How It Works

```
[Sender]                 [Relay]                  [Destination]
   │                        │                          │
   │  queueText(toUserId,…) │                          │
   ▼                        │                          │
 MessageRepository           │                          │
   │  Room.insert(QUEUED)    │                          │
   ▼                        │                          │
 MeshEngine                  │                          │
   │  bundle = sign(payload) │                          │
   │  cache.put(bundle)       │                          │
   ▼                        │                          │
 Nearby (BT + Wi-Fi)         │                          │
   ──────── BUNDLE ────────► │                          │
                              │  hopsLeft--, verify sig │
                              │  cache.put + relay      │
                              ──────── BUNDLE ────────►  │
                                                          │
                                              destId == me │
                                                          ▼
                                              Room.insert(DELIVERED)
                              ◄─────── ACK ────────────  │
   ◄──────── ACK ─────────── │                          │
   Room.update(DELIVERED)
```

A typical message flow:

1. The user composes a message; the UI calls `MessageRepository.send(toUserId, body)`.
2. The repository persists a `QUEUED` row and asks `MeshEngine` to wrap the payload in a signed `MeshBundle` with a fresh `bundleId`, TTL, and hop count.
3. `MeshEngine` puts the bundle in its in-memory cache and broadcasts it to every connected peer through the `Transport` abstraction (Google Nearby Connections in production).
4. Each receiving peer verifies the signature, decrements the hop counter, drops the bundle if TTL has expired, and either delivers it locally (if it's the destination) or relays it onwards.
5. The destination emits a `BundleAcked` event back along the mesh; the sender's `MessageRepository` advances the row to `DELIVERED`.
6. If no peer is currently in range, the bundle stays in the cache and the persistent retry loop re-broadcasts it on every tick using the per-bundle backoff curve, until it is delivered, the TTL expires, or the user signs out.

---

## Architecture Overview

| Component | Responsibility |
| --- | --- |
| `MeshEngine` (`com.tharmesh.dtn`) | Bundle lifecycle, routing, signature verification, INV/GET anti-entropy, hop and TTL enforcement. |
| `MeshBundle` / `BundleCodec` | The on-wire unit of transport. Pipe-delimited frame format (`TYPE\|FROM\|PAYLOAD`). The `priority` bit is origination-only and is **not** serialised on the wire — relays can't forge it. |
| `RetryPolicy` / `RetryConfig` | Per-bundle exponential backoff with jitter. Three configs ship: `DEFAULT`, `SOS`, and two field-test variants (`FIELD_TEST_FAST`, `FIELD_TEST_FLAT`). |
| `PerPeerSendPacer` | Enforces a 40 ms minimum gap between sends to the same peer at every send site (broadcast, forward, INV/GET response). Priority bundles bypass it. |
| `PeerChurnDebouncer` | Coalesces rapid PeerConnected events into a single trailing retry-flush. |
| `Transport` (`com.tharmesh.transport`) | Pluggable transport abstraction. `NearbyConnectionsTransport` uses `Strategy.P2P_CLUSTER`; `LoopbackTransport` drives the unit tests. |
| `MeshDataSource` / `NearbyDirectory` | Peer discovery, ranking, and the source of truth for "who is online right now". |
| `MessageRepository` (`com.tharmesh.data`) | Glues the mesh to Room. Owns the store-and-forward retry loop, the SOS priority set, churn debounce, and the `FAILED → QUEUED` manual retry hook. |
| `AppDatabase` / DAOs (`com.tharmesh.db`) | Room persistence for messages, conversations, contacts, peer identities, and the persistent bundle cache (`BundleDao` / `RoomBundleStore`). |
| `CryptoIdentity` / `IdentityStore` (`com.tharmesh.identity`) | ECDSA P-256 keypair generation, `SHA256withECDSA` signing and verification, QR invite-code encoding. |
| `DiagnosticsCollector` (`com.tharmesh.diagnostics`) | In-memory counters and a recent-events ring buffer. Exported as JSON via the share intent. |
| `FieldTestMode` | SharedPreferences-backed toggles that re-shape the retry curve. Surfaced in the Diagnostics screen. |

---

## Real-World Use Cases

- **Low-connectivity regions.** Villages, remote farming areas, and arid regions like the Thar desert where mobile coverage is patchy or absent.
- **Disaster communication.** Earthquakes, floods, and grid failures that take cell towers offline. TharMesh continues to work as long as devices have charge.
- **Network shutdowns.** Communication during deliberate internet shutdowns or in environments where centralised messaging is restricted.
- **Offline group coordination.** Field teams, expeditions, festivals, and construction sites where the operating area is well-defined but connectivity is unreliable.
- **Last-mile relay.** A single internet-connected phone can act as a gateway by carrying bundles into and out of an offline cluster.

TharMesh is not intended to replace the internet for large-volume transfer. It is a small-payload messaging substrate optimised for delivery, not throughput.

---

## Installation and Usage

### Requirements

- Android 7.0 (API 24) or newer.
- Bluetooth and Location enabled at the OS level.
- Two or more devices physically near each other (Nearby Connections range, typically 30–100 m line-of-sight over Wi-Fi-Direct).

### Installing the APK

1. Download the latest debug APK from the [Releases](../../releases) page.
2. On each device, enable **Settings → Security → Install unknown apps** for your file manager.
3. Open the APK file and accept the install prompt.
4. Launch TharMesh and grant the Bluetooth, Nearby, and Location permissions.

### Building from source

The project targets the older Android toolchain to keep the build reproducible on older developer machines:

- JDK 11
- Gradle 6.5
- Android Gradle Plugin 4.1.3
- Kotlin 1.6.21
- `compileSdk` and `targetSdk` 30, `minSdk` 24

```bash
git clone https://github.com/qadeer-cyber/TharMesh.git
cd TharMesh
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

The debug APK is written to `app/build/outputs/apk/debug/`.

### First-run pairing

1. Open TharMesh on each phone and pick a display name.
2. On phone A, open **My QR**; on phone B, open **Scan QR** and scan A's code. Repeat in the other direction so each side has the other's verified public key.
3. The two phones now appear in each other's contact list and can exchange signed messages.

---

## Field Testing Guide

These scenarios are the on-device equivalents of the unit-test suite. Each one exercises a different reliability property.

### 1. Two-phone direct test

1. Pair two phones (A, B) using QR.
2. Send a message from A → B with both phones in range. The status should advance `QUEUED → SENDING → SENT → DELIVERED → READ` within a couple of seconds.
3. Repeat in the other direction.

### 2. Store-and-forward test

1. With phones A and B paired, turn off Bluetooth on B.
2. Send a message from A → B. The row stays at `QUEUED` (or transitions to `SENDING` and back) — confirm via the chat list.
3. Turn Bluetooth on B back on. Within one retry tick (≤ 5 s on the default curve), the message advances to `DELIVERED`.
4. The Diagnostics screen's `retryAttempts` counter should reflect the number of retries that fired during the outage.

### 3. Three-phone relay test

1. Pair A↔C and B↔C, but **not** A↔B directly. The simplest way to enforce this is to keep A and B physically out of range of each other but both in range of C.
2. Send a message from A → B. C should relay it (visible as a `bundlesSent` increment in C's diagnostics) and B should receive it.
3. Verify A's row reaches `DELIVERED` after C forwards B's ACK back.

### 4. Airplane-mode survival test

1. Send a message from A → B; immediately switch A to Airplane mode before B has acknowledged.
2. The row stays in `SENDING` on A.
3. Force-stop the app on A (`adb shell am force-stop tharmesh.app`) and re-open it.
4. After Airplane mode is turned off and B is in range, the message should still deliver. The Diagnostics counter `stuckSendingRecovered` should increment.

### 5. SOS broadcast test

1. With one or more peers paired, fire an SOS from the Status screen.
2. With at least one peer in range, the toast reads "SOS sent to N nodes" within ≈ 1 s.
3. With no peer in range, the toast reads "No devices in mesh range — SOS cannot be delivered right now" but the bundle is still cached and will fan out as soon as a peer connects.

---

## Diagnostics

Open the Diagnostics screen from the app's settings menu. It exposes the live counters maintained by `DiagnosticsCollector`:

| Counter | Meaning |
| --- | --- |
| `peersFound` / `peersConnected` | Lifetime count of distinct peers seen / handshaken since the engine started. |
| `peersCurrentlyConnected` | Real-time number of peers in the active session. |
| `bundlesSending` / `bundlesSent` / `bundlesDelivered` / `bundlesAcked` / `bundlesRead` | Counters for each terminal status of locally originated bundles. |
| `bundlesFailed` | Bundles that hit a transport-level rejection. Eligible for manual retry from the chat UI. |
| `retryAttempts` | Number of retry-tick re-broadcasts. A long drought followed by a reconnect should produce a visible spike. |
| `peerChurnEvents` | PeerConnected events suppressed by the 1.5 s debounce window — i.e. how flaky the link is. |
| `sendRejected` | Transport returned `false` from `send()`. |
| `sendPaced` | Sends deferred by the 40 ms per-peer pacer. |
| `ttlExpiredDrops` | Bundles whose 24 h TTL elapsed before delivery. |
| `stuckSendingRecovered` | Rows recovered from `SENDING` after a process restart. |

The screen also includes:

- A recent-events ring buffer (last N `MeshEvent`s with timestamps).
- A **Share** action that exports the full snapshot as JSON via the system share intent — useful for attaching to bug reports.
- Two field-test toggles — **Disable retry backoff** (flat 1 s curve) and **Force high-frequency retries** (250 ms tick) — which take effect on the next sign-in.

---

## Known Limitations

- **Android only.** TharMesh depends on Google Nearby Connections, which is Android-specific. There is no iOS or desktop client.
- **Nearby radio range.** Bundles only propagate when peers are physically within Bluetooth or Wi-Fi-Direct range (typically 30–100 m line-of-sight). TharMesh does not use the internet as a fallback.
- **Payload is signed but not end-to-end encrypted yet.** Bundles are authenticated with ECDSA P-256 signatures, so a peer cannot forge a message from someone else. However, the message body itself currently travels as plaintext on the wire — encryption is wired through `CryptoBox` (AES/GCM) for storage and is on the roadmap for transport.
- **Battery cost.** Continuous Nearby advertising and discovery is non-trivial — expect a measurable battery hit when the mesh is left running for hours. Power-aware duty cycling is on the roadmap.
- **SOS priority is in-memory.** The "SOS curve" applies for as long as the app process is alive. After a forced restart, SOS bundles revert to the default retry curve until manually re-fired. A persisted-priority schema bump is planned.
- **Field-test toggles take effect on next sign-in.** They are not hot-swappable while the engine is running.
- **No CI yet.** All verification is local (`./gradlew testDebugUnitTest`, currently 129 tests) plus the on-device field-test checklist above.
- **No file or media transfer.** TharMesh today is a small-text-payload messaging substrate.

---

## Roadmap

The following items are tracked but not yet implemented. They are listed in rough priority order, not as commitments:

- End-to-end payload encryption on the wire (AES/GCM keyed by the per-contact ECDH-derived shared secret).
- Persisted SOS priority bit and persisted retry-policy state across process restarts.
- File and image transfer over multi-bundle assembly.
- Broadcast channels (1-to-many) with TTL-bounded fan-out.
- Battery-aware duty cycling for the Nearby radio.
- iOS client investigation (likely via a different transport — Nearby is Android-only).
- GitHub Actions CI for `assembleDebug` and `testDebugUnitTest` on every PR.
- Per-peer relayed-bytes accounting for fairness and incentive policies.

---

## Contributing

**This is NOT open source.** TharMesh is proprietary software. Forks, pull requests, and external modifications are **not accepted** unless explicitly approved in writing by the copyright owner. Submitting code changes without prior written authorization is a violation of the license. See the [License](#license) section below.

---

## License

**TharMesh Proprietary License — Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.**

**This is NOT open source. Use is strictly prohibited without prior written permission from the copyright owner.**

This software and its source code are proprietary and confidential. No permission is granted to copy, modify, merge, publish, distribute, sublicense, sell, host, deploy, reverse engineer, rebrand, or use this software or any substantial portion of it for commercial or non-commercial purposes without prior written permission from the copyright owner.

Additional restrictions apply, including but not limited to:

- **No public hosting** — you may not host the software on any public or private server, deploy it as a service, or expose it through an API.
- **No benchmark or competitive use** — you may not use the software to build, train, or evaluate competing products, nor benchmark / reverse-engineer it for competitive purposes.
- **Termination** — any violation of these terms immediately terminates all rights granted to you under this license.
- **Governing law** — this license is governed by the laws of Pakistan.

Viewing or accessing this repository does not grant any license or usage rights. Any unauthorized use, reproduction, redistribution, or derivative work is strictly prohibited.

The full license text is in the [LICENSE](./LICENSE) file at the repository root.

TharMesh™ is a trademark of Abdul Qadeer (Qadeer Cyber).

`SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary`
