# TharMesh 0.8 — Engineering report

**Branch:** `cursor/tharmesh-0.8-field-mesh-5aa9`  
**Draft PR:** #56 (must remain **draft**; **blocked from `main`** until PR #52 merges and gates below are green)  
**Supervisor verdict on prior head `1a3e020`:** **REJECTED** as 0.8 checkpoint — **withdrawn** “0.8 Engineering GO (simulated)”.  
**Current engineering HEAD:** `4bf1241c60604f4749d6d5879bd5784127ddd465`  
**0.7 gate baseline:** `9f7f50f570a4d765f28debd183817b23bbb7ad27` · Cat 6 run [**43547434502**](https://github.com/qadeer-cyber/TharMesh/actions/runs/43547434502)  
**Report date:** 2026-11-14  

> **0.8 is not complete.** JVM/simulation acceptance improved on this HEAD; **fresh emulator Gate A+B 3/3 on this commit** and owner-hosted credentials are still **NOT EXECUTED**.

## Dependency / merge policy (PR #56)

- Contains all account/identity work from **PR #52** lineage — **do not merge #56 before #52** without owner direction.
- **Do not merge** to `main` while any row below is **FAIL** or **NOT EXECUTED** where required.

## Phase acceptance matrix

| Phase | Scope | Verdict | Executable evidence |
|-------|--------|---------|---------------------|
| **0** | 0.7 closure | **PASS** | Run **43547434502**, SHA `9f7f50f`, APK `3feee5d4…`, cert `ac1a5118…` |
| **1** | Transport reliability (simulated radio boundary) | **PASS** (JVM) | `NearbyMeshConnectPolicyTest`, `MeshEngineSendCorrelationTest`, `MeshEngineComposeToAckPathTest`, `MessageStatusTransportFailureTest`, `NearbyPermissionsSdkMatrixTest`, `MeshPermissionDiagnosticsTest`; prod: `NearbyMeshConnectPolicy` + `NearbyConnectionsTransport` |
| **2** | Multi-hop / relay | **PASS** (JVM **simulation**) | `MeshEngineFaultInjectionTest` (unconditional drop), `MeshEngineRelayProcessRestartTest`, `MeshEngineInvGetAntiEntropyTest`, `MeshEnginePartitionHealTest`, `MeshEngineMalformedBundleTest`, existing `MeshEngineRelayThreeHopTest` |
| **3** | E2EE / identity negatives | **PASS** (JVM) | Existing `MeshEngineSecurityTest`, `MeshStoreAndForwardCat2Test`; new `DiagnosticsExportNoSecretsTest` |
| **4** | Diagnostics UX strings | **PASS** (JVM) | Existing `ChatStatusFormatterTest`, `SystemStatusTest`; permission banners via `MeshPermissionDiagnosticsTest` |
| **5** | Battery / lifecycle | **PARTIAL** | `PowerManagementExplanationTest`, `MessageRepositoryStuckSendingRecoveryTest`; UI: `mesh_power_doze_explanation` in Settings → Notifications. **Measured battery:** **NOT EXECUTED** |
| **6** | Optional features | **DEFERRED** | — |
| **7** | Staging guard (exact project ref) | **PASS** (JVM) | `SupabaseEnvironmentGuardTest`, `BuildConfig.SUPABASE_*_PROJECT_REF`, `AuthBackend.configured` |

## Phase 7 owner build config (publishable key only)

```properties
THARMESH_STAGING_PROJECT_REF=<exact-staging-ref>
THARMESH_PRODUCTION_PROJECT_REF=<exact-production-ref>
SUPABASE_URL=https://<ref>.supabase.co
SUPABASE_ANON_KEY=<publishable-anon-key>
```

QA/staging build type sets `SUPABASE_BACKEND_ENV=staging` and **fail-closed** unless URL ref matches `THARMESH_STAGING_PROJECT_REF`. Release build type sets `release` and **rejects** staging ref.

## Evidence matrix (this HEAD)

| Category | Run / command | SHA / artifact | Result |
|----------|----------------|----------------|--------|
| JVM unit | `./gradlew :tharmesh.app:testDebugUnitTest` | `4bf1241` (agent workspace) | **PASS** (498 tests) |
| Local debug APK | `:tharmesh.app:assembleDebug` | file `ee5a793b…` | **PASS** (build) |
| Local debug cert | `apksigner verify --print-certs` | `ac1a511884df9ec2cd8b0b8d696c3192b671cddc6c93f8e83ae9efd08221c2ec` | **PASS** (matches pinned debug cert) |
| **Fresh** emulator Gate A + B 1–3 | `emulator-evidence.yml` | — | **NOT EXECUTED** (pending CI on `4bf1241` after push) |
| Prior JVM CI on rejected head | run **43550303575** / `1a3e020` | — | **Superseded** (not a 0.8 checkpoint) |
| Physical 2-phone / 3-phone | — | — | **NOT EXECUTED** |
| Hosted staging OTP/Edge | — | — | **NOT EXECUTED** / **NO** owner credentials |
| Signed QA workflow | — | — | **NOT EXECUTED** |
| API 33+ emulator | — | — | **NOT EXECUTED** |

## GO / NO-GO

| Gate | Verdict |
|------|---------|
| **0.7 Engineering (emulator Cat 6)** | **GO** (`9f7f50f`) |
| **0.8 Engineering (code + JVM)** | **NO-GO for release** until fresh emulator **3/3** on current HEAD |
| **0.8 Physical / hosted** | **NO-GO** |
| **Simulated-only GO** | **Withdrawn** — not acceptable per owner directive |

## Commits per phase (this remediation)

| Phase | Commit |
|-------|--------|
| 1 | `0a4658b` |
| 2 | `7f4df4e` |
| 3 | `0575302` |
| 4 | (covered by existing formatter tests; no new commit) |
| 5 | `c5b7b9e` |
| 7 | `bc809bf` |
| Chore | `4bf1241` (remove empty `ci-evidence-preserved` stubs) |