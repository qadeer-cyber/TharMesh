# TharMesh 0.9 — Engineering report

**Branch:** `cursor/tharmesh-0.9-engineering-5aa9`
**Base (stacked):** `cursor/tharmesh-0.8-field-mesh-5aa9` @ `4ec45fe`
**Draft PR:** [#57](https://github.com/qadeer-cyber/TharMesh/pull/57) — **DRAFT**, stacked on 0.8, does not touch `main`, PR #52, #55 or #56
**Report date:** 2026-11-21
**Supersedes:** the `b3d1b37` edition of this report, whose Phase 4 / Phase 9 rows
are corrected below (they claimed more than the evidence supported, and the green
run they cited was obtained with APK hash pinning disabled).

> **0.9 is NOT production-ready and is NOT release-qualified.** Physical UAT,
> hosted staging, measured battery and signed-QA upgrade remain NOT EXECUTED or
> BLOCKED. Nothing in this document should be read as production admission.

---

## 1. Verdict summary

| Area | Verdict | Basis |
|------|---------|-------|
| 0.9 feature code (transport, relay, UX, permissions) | **PASS (JVM)** with 4 defects **fixed in this correction** | §2, §4 |
| APK SHA-256 pinning / reproducibility | **RESTORED** (was silently disabled) | §3 |
| JVM unit suite | **PASS** — 536 tests / 103 classes, 0 failures, run locally | §4 |
| Regression guards bite when functionality breaks | **PASS** — mutation-tested, 6 induced failures | §4.2 |
| Emulator Cat 6 (API 30) Gate A + B×3 | **PASS** — 5/5 jobs, strict pin enforced | run 44884492675 |
| Android CI `build` (cloud assemble + unit tests) | **PASS** at `052adb2` (FAIL at `51c32ab` — external: staging host does not resolve) | §5.3 |
| Android API 33/34/35 | **PASS (install + permission declaration only)** — *not* a functional mesh gate | §5 |
| Physical 2/3-phone UAT | **NOT EXECUTED** | §6 |
| Hosted staging OTP / Edge | **BLOCKED** (no owner credentials) | §6 |
| Measured battery (60 min) | **NOT EXECUTED** | §6 |
| Signed-QA upgrade | **NOT EXECUTED** | §6 |
| Production admission | **NO** | owner decision, not an engineering one |

---

## 2. Defects found in PR #57 and fixed in this correction

All four were present at `fea0836` and are shipped green in the branch's prior CI.

### D1 — Delivery receipts were dead on device (critical)

`NearbyOutboundSendTracker.STATUS_SUCCESS` was `3`. In
`com.google.android.gms.nearby.connection.PayloadTransferUpdate.Status`, `3` is
**IN_PROGRESS**; **SUCCESS is `1`** (FAILURE 2, CANCELED 4). `NearbyConnectionsTransport`
matched the *real* GMS constant and then handed the raw status to the tracker, which
compared it against `3` and returned `null`. Consequence on a real device:

* `TransportEvent.PayloadSent` was **never** dispatched,
* so `MeshEvent.BundleSent` never fired,
* so every outgoing message stayed `SENDING` forever and `messagesSent` never incremented.

This defeats the headline Phase 1 claim ("PayloadSent only after transfer SUCCESS").
It was invisible to CI because the only existing test passed `STATUS_SUCCESS`
*symbolically*, so it never pinned the numeric contract.

**Fix:** correct all four mirrors; add `onTransferSuccess(payloadId, bytesTransferred,
totalBytes)` so the transport's `when` branch carries the meaning and no status integer
crosses the boundary; treat `CANCELED` as a failure alongside `FAILURE`.
**Guard:** `NearbyOutboundSendTrackerStatusContractTest` (7 tests) asserts the literal
values 1/2/3/4 and that raw status 3 must not emit.

### D2 — Relayed messages silently lost their status tick (user-visible regression)

0.9 added `MessageStatus.RELAYING` and routed `BundleSent` to it whenever the final
recipient is not directly connected, but did not add it to any glyph renderer:
`ChatsActivity` (chat list), `ChatActivity` (bubble) and `DeliveryGlyph` all fell
through to `else -> ""`. Every message delivered via a relay therefore showed **no
indicator at all** — strictly worse than 0.8, where the same message showed `✓`.

**Fix:** render `RELAYING` with the in-flight glyph (`⏳`) in both renderers; keep it
tick-less in `DeliveryGlyph` (a relayed bundle has no signed receipt, so it must not
borrow SENT's `✓`); add `MessageStatus.isInFlight()` / `allStatuses()` as the single
source of truth.
**Guard:** `MessageStatusGlyphCoverageTest` — every declared status must be ranked,
`advance()` must not regress across all 49 status pairs, and no in-flight status may
render a checkmark.

### D3 — Data race between the chat UI and Nearby callbacks

0.9 exposed `NearbyMeshConnectPolicy.pendingConnectionCount()` to
`ChatActivity.computeMeshState()` (main thread) while Nearby lifecycle callbacks and
the connect-timeout runnable mutate the same **unsynchronized `HashMap`s** on other
threads. Symptom: `ConcurrentModificationException` or a corrupted bucket chain while
the user simply looks at a chat.

**Fix:** one lock guarding every policy method (the failure-streak and backoff updates
must be atomic *with respect to each other*, which a `ConcurrentHashMap` would not give).
**Guard:** `NearbyMeshConnectPolicyConcurrencyTest` — 6 threads × 2 000 iterations of
mixed lifecycle writes and UI reads, failing on any escaped throwable.

### D4 — Unbounded loop in the backoff curve

`reconnectBackoffMs(streak)` executed `repeat(streak - 1)`, so a pathological streak
value spins for billions of iterations. **Fix:** clamp to 32 doublings (the curve
saturates long before that). **Guard:** asserted at `streak = Int.MAX_VALUE / 2`.

### Not fixed — pre-existing on the 0.8 base, reported only

`.github/workflows/android.yml` sets `THERMESH_DEBUG_PROJECT_REF`, but
`app/build.gradle` reads `THARMESH_DEBUG_PROJECT_REF`: the CI variable is dead.
Left untouched on purpose — it predates 0.9, and "fixing" it would start populating
`BuildConfig.SUPABASE_DEBUG_PROJECT_REF` in CI, changing build semantics inside a
correction PR. **Owner action required** on the 0.8 base or `main`, not here.

---

## 3. Security and reproducibility restoration

### 3.1 What had been removed

Commits `b8275ea`, `5ff631c` and `08875ba` converted every APK verification to
`cert-only`, which set `EXPECT_FILE_SHA=""` and skipped the hash comparison entirely.
After those commits **no APK anywhere in the pipeline was hash-verified**:

| Call site | At `fea0836` | Now |
|-----------|--------------|-----|
| `prepare-apks` → v6 APK | `cert-only` (no hash) | `pinned` — committed pin + cert |
| `prepare-apks` → head APK | not verified at all | `expected:<sha>` from `SHA256SUMS.txt` |
| Gate A → v6 APK | `cert-only` | `pinned` |
| Gate A → head APK | existence check only | `expected:<sha>` |
| Gate B → head APK | `cert-only`; digest check **fail-open** (skipped when the manifest or its line was missing) | `expected:<sha>`, manifest **mandatory** |
| API 33–35 matrix → head APK | `cert-only` | `expected:<sha>` from a digest manifest now published by that workflow |

Two environment-variable typos also made the intended overrides dead code:
`THERMESH_EXPECT_DEBUG_CERT_SHA256` and `THERMESH_VERIFY_CERT_ONLY` in
`verify-pinned-apk.sh`, and `THERMESH_EVIDENCE_DIR` in `api-emulator-evidence.sh`
(the API-matrix evidence landed in the right directory only by coincidence of the
working directory). All corrected to the `THARMESH_` prefix the workflows export.

### 3.2 Root cause of the hash mismatch that triggered the downgrade

The pinned digest `e2f7145b…` is the SHA-256 of the v6 debug APK rebuilt from source
commit `9fedae9`. The timeline from the actual run logs:

| Head | Verify mode | Cat 6 result | v6 file SHA-256 |
|------|-------------|--------------|-----------------|
| `7b354ac` | **strict hash** | [**PASS** 43731539047](https://github.com/qadeer-cyber/TharMesh/actions/runs/43731539047) | matched `e2f7145b…` |
| `2620190` | strict hash | [**FAIL** 43735655678](https://github.com/qadeer-cyber/TharMesh/actions/runs/43735655678) | `857dd3d59111a2ca9c2d3a8d06f3ca3c370c91b08657039a13dd6868b2db7000` |
| `08875ba` | `cert-only` | PASS (hash check disabled) | not checked |

`2620190` changed exactly three things, and only one of them is an input to the v6
build: **`HISTORICAL_WORKTREE` moved from `/tmp/wt-room-v6` to
`${{ github.workspace }}/ci-wt-room-v6`.** The other two (`rm -rf` of a worktree that
CI had already deleted, and a separate `GRADLE_USER_HOME` for the *head* build) cannot
change v6's bytes. The AGP 4.1.3 debug output is therefore path-dependent, and the pin
was broken by relocating the build, not by any tampering.

So the fix is to restore reproducibility rather than to delete the check:
`HISTORICAL_WORKTREE` is back to `/tmp/wt-room-v6`, with the cross-contamination fix
from `2620190` (fresh worktree per run, isolated head Gradle home) **kept**.

**This was confirmed by direct experiment, not inference.** `9fedae9` was rebuilt in
this correction's container — a different machine and OS image from the GitHub runner
(Ubuntu 24.04, Temurin/OpenJDK 11.0.33, SDK platform 30, build-tools 30.0.3) — at
`/tmp/wt-room-v6`, and it reproduced the pinned digest exactly:

```
e2f7145b975ff099566f3a8a2d02cbd236ba75ae67af7a29f4ffd8d43f900c89  tharmesh.app-debug.apk
Signer #1 certificate SHA-256 digest: ac1a511884df9ec2cd8b0b8d696c3192b671cddc6c93f8e83ae9efd08221c2ec
```

`scripts/verify-pinned-apk.sh <apk> pinned` then returned `verify ok` against that
binary. The v6 rebuild is therefore byte-reproducible **and** toolchain-robust, which
contradicts the "toolchain-neutral rebuild" justification given in `08875ba` for
disabling the hash check. The pin was never stale; only the build path had moved.

A second, latent defect surfaced while reproducing this: the workflow does
`rm -rf "$HISTORICAL_WORKTREE"` before calling the build script, but the script's
`if [ -d "$WT" ]` guard then skipped `git worktree remove`, leaving the worktree
registered and killing the next `git worktree add` with *"missing but already
registered worktree"*. Harmless on a fresh `actions/checkout`, fatal on any reused
checkout. The script now always removes, prunes and re-adds.

### 3.3 Hardening added

* `scripts/pinned/v6-room6-debug.sha256` and `scripts/pinned/debug-cert.sha256` —
  committed pins with written provenance and an explicit "do not edit this to make CI
  green" note. They are the single source of truth.
* An env override that **disagrees** with a committed pin is now **rejected**, so a red
  gate cannot be turned green by editing a workflow `env:` block. The workflow's in-band
  restatement is cross-checked against the pin file.
* The retired bypass is refused, not honoured: `THARMESH_VERIFY_CERT_ONLY`,
  `THERMESH_VERIFY_CERT_ONLY` and `mode cert-only` all hard-fail with an explanation.
* Empty / malformed expectations hard-fail instead of disabling the check.
* `scripts/build-historical-debug-apk.sh` asserts the worktree really is the pinned
  commit, and logs the build inputs the digest depends on.
* On mismatch the verifier prints reproducibility diagnostics (APK size, pinned source
  commit, worktree path, JDK, build-tools, runner image) so one run yields the root cause.
* `scripts/selftest-verify-pinned-apk.sh` — 14 checks proving the verifier fails closed,
  wired into `prepare-apks` so it runs before any gate trusts a verdict. Local result:
  **RESULT=PASS**.

### 3.4 Re-pinning policy

If the v6 digest ever mismatches again, the correct responses are, in order:
investigate reproducibility (the diagnostics block names the drifting input); if the
drift is a legitimate toolchain change, record the new digest **and its provenance** in
`scripts/pinned/v6-room6-debug.sha256` with owner sign-off recorded in this document.
Disabling the check is not an accepted response.

---

## 4. Local verification actually executed

Environment: Ubuntu 24.04 container, JDK 11.0.33 (Gradle/AGP) + JDK 17 (sdkmanager),
Android SDK platform 30, build-tools 30.0.3, Gradle 6.5, AGP 4.1.3 — the same toolchain
versions the `Android CI` workflow uses.

| Check | Command | Result |
|-------|---------|--------|
| JVM unit tests | `./gradlew :tharmesh.app:testDebugUnitTest` | **PASS — 536 tests, 103 classes, 0 failures, 0 errors, 0 skipped** |
| Verifier self-test | `scripts/selftest-verify-pinned-apk.sh` | **PASS — 14/14 fail-closed checks** |
| Shell syntax | `bash -n` on every modified script | **PASS** |
| Workflow syntax | `yaml.safe_load` on all three workflows | **PASS** |
| Mutation test | see §4.2 | **PASS — guards bite** |
| Android lint / release build | not run | **NOT EXECUTED** (not part of the 0.9 gates) |
| Instrumented / emulator tests | not runnable in this container (no KVM, no emulator) | **BLOCKED locally**, executed in CI (§5) |

### 4.1 Tests added

| Test | Proves | Honest scope |
|------|--------|--------------|
| `NearbyOutboundSendTrackerStatusContractTest` (7) | GMS status mirrors; SUCCESS(1) emits once, IN_PROGRESS(3) never emits; short transfers, duplicates, inbound payloads and `sendId = 0` frames emit nothing | JVM |
| `NearbyMeshConnectPolicyConcurrencyTest` (2) | no corruption under 6 threads × 2 000 mixed lifecycle/UI operations; backoff curve bounded and monotonic | JVM |
| `MessageStatusGlyphCoverageTest` (5) | every status ranked; `advance()` never regresses over all status pairs; `RELAYING` is in-flight and never renders a checkmark | JVM |
| `NearbyPermissionsManifestRegressionTest` (6) | every runtime permission requested on API 24/29/30/31/32/33/34/35 is declared in `AndroidManifest.xml` (an undeclared request is a silent no-op on Android); `NEARBY_WIFI_DEVICES` absent below API 33; 34/35 identical to 33 | JVM static contract — **not** device evidence |
| `MeshOfflineReconnectRecoveryTest` (3) | compose with no peers is retained (not dropped/failed); reconnect flush delivers exactly once; a second flush does not duplicate; the sender gets an acknowledgment; link churn still converges on one delivery | **JVM simulation — no radio, no Play Services, no Doze** |

Existing 0.9 coverage retained unchanged: relay duplication, three-hop relay, relay
process restart, partition heal, signature/security negative paths, store-and-forward
Cat 2, send correlation, foreground-service guard, Room 4→5/5→6/6→7 migrations.

### 4.2 Mutation test — the guards fail when functionality breaks

Three fixed defects were deliberately reintroduced and the targeted tests re-run:

| Mutation | Induced failures |
|----------|------------------|
| `STATUS_SUCCESS` back to `3` | `mirrorsMatchPlayServicesStatusValues`, `rawStatus1_isTreatedAsCompletedTransfer`, `rawStatus3_isInProgress_andMustNotEmit` |
| `isInFlight` drops `RELAYING` | `relayingIsInFlightAndCarriesNoSignedReceipt` |
| `NEARBY_WIFI_DEVICES` removed from the manifest | `api33to35_runtimePermissionsAreAllDeclaredInManifest`, `api33PlusDeclaresNearbyWifiDevicesAndPostNotifications` |

`17 tests completed, 6 failed` → the new assertions are load-bearing, not decorative.
All mutations were reverted; the final tree is the green one in §4.

### 4.3 Simulation vs physical — explicit

Every mesh behaviour above (offline queueing, reconnect, relay, deduplication,
encryption, delivery acknowledgment, background recovery) is verified on the real
`MeshEngine` over an **in-process bridged transport**. There is no radio, no Play
Services, no Doze and no second device. **None of it is physical-device validation**
and it is not presented as such anywhere in this report. Physical UAT is §6.1.

---

## 5. GitHub Actions evidence

Filled in from actual run logs for the pushed correction commit. CI consumption was
minimised: the whole correction is a **single commit**, and the API-matrix workflow is
now push-only (it previously started two runs of three emulators for every commit,
because the `push` and `pull_request` triggers have different concurrency keys). The
`b3d1b37` push-only Cat 6 deduplication is preserved verbatim.

<!-- CI-EVIDENCE-BEGIN -->
**Correction commit: `51c32ab698bbddfa0b2dcd4f21bfaae91ab58ea1`** (pushed 2026-11-21 08:21 UTC;
no overlapping run was active at push time).

| Workflow | Run | Event | Result |
|----------|-----|-------|--------|
| Emulator evidence (Cat 6), API 30 | [**44884492675**](https://github.com/qadeer-cyber/TharMesh/actions/runs/44884492675) | push | **PASS — 5/5 jobs** |
| Android API 33–35 evidence | [**44884492722**](https://github.com/qadeer-cyber/TharMesh/actions/runs/44884492722) | push | **PASS — 33 / 34 / 35** |
| Android CI | [**44884492629**](https://github.com/qadeer-cyber/TharMesh/actions/runs/44884492629) | push | **FAIL** — `build` job, external cause (§5.3) |
| Android CI | [**44884498171**](https://github.com/qadeer-cyber/TharMesh/actions/runs/44884498171) | pull_request | **FAIL** — same job, same cause |

### 5.1 Cat 6 — strict APK hash pinning verified green on the runner

| Job | Result |
|-----|--------|
| [`prepare-apks`](https://github.com/qadeer-cyber/TharMesh/actions/runs/44884492675/job/136786708528) | **PASS** — verifier self-test, strict v6 pin, head digest |
| [`gate-a-legacy-upgrade`](https://github.com/qadeer-cyber/TharMesh/actions/runs/44884492675/job/136787730332) | **PASS** — v6→v7 in-place upgrade; step "Re-verify v6 APK hash and cert (strict)" PASS |
| [`gate-b-persistence (1)`](https://github.com/qadeer-cyber/TharMesh/actions/runs/44884492675/job/136788256268) | **PASS** |
| [`gate-b-persistence (2)`](https://github.com/qadeer-cyber/TharMesh/actions/runs/44884492675/job/136788256259) | **PASS** |
| [`gate-b-persistence (3)`](https://github.com/qadeer-cyber/TharMesh/actions/runs/44884492675/job/136788256287) | **PASS** |

Verbatim from the `prepare-apks` log, with `HISTORICAL_WORKTREE: /tmp/wt-room-v6`:

```
e2f7145b975ff099566f3a8a2d02cbd236ba75ae67af7a29f4ffd8d43f900c89  apk-artifacts/v6-room6-debug.apk
verify ok mode=pinned file=e2f7145b975ff099566f3a8a2d02cbd236ba75ae67af7a29f4ffd8d43f900c89 cert=ac1a511884df9ec2cd8b0b8d696c3192b671cddc6c93f8e83ae9efd08221c2ec
verify ok mode=expected:e52541b6c93dd6e2ef2cf7987d9a237f165aefc52946183d801867bc0e455341 file=e52541b6c93dd6e2ef2cf7987d9a237f165aefc52946183d801867bc0e455341 cert=ac1a511884df9ec2cd8b0b8d696c3192b671cddc6c93f8e83ae9efd08221c2ec
```

**This is the proof that the cert-only downgrade was never necessary.** The same pin
that failed at `2620190` (`857dd3d5…`) matches again on GitHub's runner with nothing
changed but the build path — and matches the independent off-runner rebuild in §3.2.

### 5.2 Android API 33–35 — honest scope

All three legs PASS: [33](https://github.com/qadeer-cyber/TharMesh/actions/runs/44884492722/job/136787252062),
[34](https://github.com/qadeer-cyber/TharMesh/actions/runs/44884492722/job/136787252110),
[35](https://github.com/qadeer-cyber/TharMesh/actions/runs/44884492722/job/136787252183).
Each leg asserts `ro.build.version.sdk` equals the matrix level, strictly verifies the
head APK digest and certificate, installs with `-g`, and captures `aapt dump
permissions` plus `dumpsys package`. It proves **install and permission declaration**
per API level. It does **not** exercise messaging, relay or reconnect on those API
levels, and its verdict file now says so. Treating this as a functional Android 13/14/15
mesh gate — as the previous edition of this report did — overstates it.

Evidence artifacts (expire 2027-02-19): `api-33-evidence` **14699576077**,
`api-34-evidence` **14699602012**, `api-35-evidence` **14700170341**.

### 5.3 Android CI `build` — FAIL, external, root-caused

`postgres` PASS in both runs. `build` **FAIL** in both, at step **"Supabase auth
health"**, 26 seconds in:

```
curl: (6) Could not resolve host: owhmuxueacjbljhpmpua.supabase.co
##[error]Process completed with exit code 6
```

Root cause, and what it is *not*:

* **Not caused by this correction.** `.github/workflows/android.yml` is byte-identical
  between `fea0836` and `51c32ab` (`git diff fea0836 51c32ab -- .github/workflows/android.yml`
  is empty). The step is from `589f454`.
* The step is gated on `if: env.SUPABASE_URL != '' && env.SUPABASE_ANON_KEY != ''`.
  Those are now populated as repository secrets/variables, so **this is the first run in
  which the step executed at all** — which is why the earlier 0.9 runs were green.
* The configured staging project host **does not resolve**. The project is deleted,
  paused or the ref is wrong. That is a real Phase 7 finding, recorded as such in §6.2.
* The step's first `curl` had no `|| true` (its second one does), so a DNS failure
  aborted the job, **skipping `Assemble debug APK` and `Run unit tests` entirely**. An
  availability probe of owner-owned infrastructure was able to cancel the two checks
  this job actually certifies.

**Fix applied in the follow-up commit** (`android.yml` + this document only — it matches
no emulator-workflow path filter, so it consumes no further emulator minutes): the probe
keeps running, reports `auth_health_http` and `curl_exit`, emits a
`::warning title=Supabase staging unreachable` annotation, and exits 0. The acceptance
checks were not touched or relaxed; they are simply allowed to run. Verified locally
under GitHub's own `bash -e -o pipefail` against an unreachable host: the step reports
`staging_reachable=false` and exits 0.

**Outcome of that fix, at `052adb2`:** Android CI `build` is **PASS** in both runs
([push 44886839234](https://github.com/qadeer-cyber/TharMesh/actions/runs/44886839234),
[pull_request 44886843940](https://github.com/qadeer-cyber/TharMesh/actions/runs/44886843940)),
so `Assemble debug APK` and `Run unit tests` ran in the cloud for the first time on this
branch since the staging values were added. Artifacts produced:
`tharmesh-app-debug` **14700014150** and `unit-test-reports` **14700710211** (232 KB of
JUnit XML — the suite genuinely executed). `postgres` PASS in both. No emulator workflow
was re-triggered by that commit, as intended.

The probe still reports the staging project as unreachable; that remains a Phase 7
BLOCKED finding (§6.2), now as a warning annotation instead of a dead build.

### 5.4 APK artifacts at `51c32ab`

| Artifact | Run | ID | Contents |
|----------|-----|----|----------|
| `cat6-apk-artifacts` | [44884492675](https://github.com/qadeer-cyber/TharMesh/actions/runs/44884492675) | **14698949815** | head + v6 APKs and `SHA256SUMS.txt` |
| `api-matrix-apk` | [44884492722](https://github.com/qadeer-cyber/TharMesh/actions/runs/44884492722) | **14698529845** | head APK and `SHA256SUMS.txt` |
| `tharmesh-app-debug` (Android CI) | [44886839234](https://github.com/qadeer-cyber/TharMesh/actions/runs/44886839234) @ `052adb2` | **14700014150** | head debug APK (not produced at `51c32ab`; `build` failed before assemble) |
| `unit-test-reports` (Android CI) | [44886839234](https://github.com/qadeer-cyber/TharMesh/actions/runs/44886839234) @ `052adb2` | **14700710211** | JUnit XML for the cloud unit-test run |

| Field | Value |
|-------|-------|
| Head debug APK SHA-256 | `e52541b6c93dd6e2ef2cf7987d9a237f165aefc52946183d801867bc0e455341` |
| Signing cert SHA-256 | `ac1a511884df9ec2cd8b0b8d696c3192b671cddc6c93f8e83ae9efd08221c2ec` |
| Pinned v6 APK SHA-256 | `e2f7145b975ff099566f3a8a2d02cbd236ba75ae67af7a29f4ffd8d43f900c89` |

Both digests were produced and verified by `verify-pinned-apk.sh` inside the green runs
above; neither is a local claim.

Android CI did not print the digest of the APK it uploads, so the `tharmesh-app-debug`
artifact could not be tied to a hash from its own log. A `Record debug APK digest` step
now does that; its value appears in the run log and job summary from the next Android CI
run onward. Until then, the only in-pipeline measured head-APK digest for this branch is
the `e52541b6…` above, measured at `51c32ab` by the Cat 6 and API-matrix runs.

### 5.5 CI consumption for this correction

One commit → one Cat 6 run (5 jobs, 4 emulators) + one API-matrix run (3 emulators) +
two Android CI runs. The API matrix previously started **two** runs of three emulators
per commit; the push-only change halves that. The remaining Android CI duplication
(`push` + `pull_request` on the same SHA) is untouched: it is a cheap JVM job shared
with `main`, and narrowing it would have meant editing trigger logic shared with other
branches inside a correction PR. **Owner decision, documented not silently changed.**
<!-- CI-EVIDENCE-END -->

---

## 6. Remaining blockers and exactly what is required to clear them

### 6.1 Physical 2/3-phone UAT — NOT EXECUTED

Blocked by hardware: this is a cloud container with no Bluetooth, no Wi-Fi Direct and
no attached device. Steps: 3 physical Android devices spanning API 30 / 33 / 35; install
the CI head APK (§5) on each; run the matrix in `docs/field-uat-0.9.md` — two-device
direct chat, three-device relay with the middle device as the only path, airplane-mode
store-and-forward, 30-minute idle reconnect; capture `adb bugreport` plus screen
recordings per case; attach them under `ci-evidence-preserved/uat-0.9/`.
**Cannot be simulated. No JVM or emulator result substitutes for it.**

### 6.2 Hosted staging OTP / Edge — BLOCKED (and now with a concrete failure)

Credentials **are** now configured as repository secrets/variables, but the staging
project host does not resolve (§5.3): `curl: (6) Could not resolve host:
owhmuxueacjbljhpmpua.supabase.co`. So the blocker is no longer "no credentials" — it is
**the configured staging project is unreachable**. Owner action: confirm whether that
project ref is live, restore or recreate it, and update the repository values. Until
`staging_reachable=true` appears in the Android CI log, Phase 7 stays BLOCKED.

Required as GitHub repository secrets/variables (names only; values must never be
committed):

| Name | Purpose |
|------|---------|
| `SUPABASE_URL` | staging `https://<ref>.supabase.co` only |
| `SUPABASE_ANON_KEY` | publishable anon key only |
| `GOOGLE_WEB_CLIENT_ID` | OAuth web client for staging |
| `THARMESH_STAGING_PROJECT_REF` | exact staging project ref (fail-closed guard) |
| `THARMESH_PRODUCTION_PROJECT_REF` | exact production ref (release builds) |

(The previous edition of this report listed these with a `THERMESH_` prefix;
`app/build.gradle` reads `THARMESH_…`, so those names would not have worked.)
Then, in the Supabase dashboard: enable Email OTP + SMTP; register Google OAuth redirect
URLs for application id `tharmesh.app`; deploy Edge Functions `device-enrollment` and
`sensitive-auth` to **staging only**.

### 6.3 Measured battery — NOT EXECUTED

`scripts/battery-measurement-60m.sh` exists and is unexecuted. It needs a physical
device on `adb`: `adb shell dumpsys batterystats --reset`, run the mesh scenario for
3 600 s, capture `batterystats` and `dumpsys power`. Run it at least twice — mesh idle
and mesh active — and record mAh/hour against a radios-off baseline. A container cannot
produce this number, and no emulator figure may stand in for it.

### 6.4 Signed-QA upgrade — NOT EXECUTED

The `signed-qa` job is gated on the repository variable `THARMESH_QA_SIGNING_ENABLED`,
which is not set, and on a QA keystore that is not available to automation. Owner steps:
provision the QA keystore as a repository secret, set
`THARMESH_QA_SIGNING_ENABLED=true`, then prove install-over-install from the previous
signed QA build to the new one with no data loss and no uninstall.

### 6.5 Stacked merge order

PR #57 targets `cursor/tharmesh-0.8-field-mesh-5aa9`, not `main`. It stays **DRAFT**
and merges only after the owner's #52 / #56 ordering is resolved. Nothing in this
correction touches `main`, PR #52, #55 or #56.

---

## 7. Gate table (explicit, no optimistic rounding)

| Gate | Verdict |
|------|---------|
| JVM engineering suite (536 tests) | **PASS** (local; CI run in §5) |
| APK SHA-256 + certificate pinning | **RESTORED and enforced** (§3) |
| Verifier fails closed | **PASS** (14/14, §3.3) |
| Regression guards fail on breakage | **PASS** (mutation-tested, §4.2) |
| Emulator API 30 Gate A + Gate B 3/3 | **PASS** — run 44884492675, strict pin enforced |
| Android 13/14/15 install + permission declaration | **PASS** — run 44884492722 |
| Android CI `build` (assemble + unit tests in cloud) | **FAIL** at `51c32ab` (external, §5.3) → **PASS** at `052adb2` |
| Android 13/14/15 **functional mesh** | **NOT EXECUTED** (no such gate exists) |
| Physical mesh UAT | **NOT EXECUTED** |
| Hosted staging | **BLOCKED** |
| Measured battery | **NOT EXECUTED** |
| Signed-QA upgrade | **NOT EXECUTED** |
| Production admission | **NO** |
