# TharMesh 0.8 — Engineering report

**Branch:** `cursor/tharmesh-0.8-field-mesh-5aa9`  
**Draft PR:** #56 (must remain **draft**; **blocked from `main`** until PR #52 merges)  
**Supervisor verdict on prior heads:** `1a3e020` and `f3ffa46` **progress accepted**, **not complete** (directive **t148u** still in force).  
**Current engineering HEAD:** `15503802040f77f671eda174ddc11f87b53954af`  
**0.7 gate baseline:** `9f7f50f570a4d765f28debd183817b23bbb7ad27` · Cat 6 run [**43547434502**](https://github.com/qadeer-cyber/TharMesh/actions/runs/43547434502)  
**Report date:** 2026-11-14  

> **0.8 is not complete.** Fresh **emulator Gate A + Gate B 3/3 PASS** on this HEAD is recorded below; **physical devices**, **hosted staging OTP/Edge**, **signed-QA**, **API 33+**, and **measured battery** remain **NOT EXECUTED**.

## Dependency / merge policy (PR #56)

- Contains account/identity work from **PR #52** lineage — **do not merge #56 before #52** without owner direction.
- **Do not merge** to `main` while required physical/hosted rows are **NOT EXECUTED**.

## Phase acceptance matrix

| Phase | Scope | Verdict | Executable evidence |
|-------|--------|---------|---------------------|
| **0** | 0.7 closure | **PASS** (prior baseline) | Run **43547434502**, SHA `9f7f50f` |
| **1** | Transport reliability (simulated radio boundary) | **PASS** (JVM **simulation**) | `NearbyMeshConnectPolicyTest`, `MeshEngineSendCorrelationTest`, hardened `MeshEngineFaultInjectionTest`, transport path tests |
| **2** | Multi-hop / relay | **PASS** (JVM **simulation**) | `MeshEngineRelayProcessRestartTest`, `MeshEngineInvGetAntiEntropyTest`, `MeshEnginePartitionHealTest`, `MeshEngineMalformedBundleTest`, relay path tests |
| **3** | E2EE / identity negatives | **PASS** (JVM **simulation**) | `MeshEngineSecurityTest`, `MeshEnginePhase3NegativeTest` (key substitution, GCM tamper, malicious relay / no plaintext on wire) |
| **4** | Outbound message UX states | **PASS** (JVM) | `ChatStatusFormatter` + `ChatStatusFormatterTest` — queued / waiting for peer / retrying / failed; **ur** + **sd** strings; no delivered/read without signed confirmation |
| **5** | Battery / lifecycle | **PARTIAL** | `MeshForegroundServiceGuardTest` (no manifest `WAKE_LOCK`), `RetryPolicy` bounded backoff test; **measured battery: NOT EXECUTED** |
| **6** | Optional features | **DEFERRED** | — |
| **7** | Staging guard (exact project ref) | **PASS** (JVM) | `SupabaseEnvironmentGuardTest` — release fail-closed without prod ref; debug never prod; lookalike hosts; `stagingUrlAllowed` shim removed (`bb5c72e`) |

## Evidence matrix (HEAD `1550380`)

| Category | GitHub Actions run | SHA | Jobs / duration | Result |
|----------|-------------------|-----|-----------------|--------|
| **Android CI** (postgres + build + unit tests) | [**43618455459**](https://github.com/qadeer-cyber/TharMesh/actions/runs/43618455459) | `1550380` | `postgres` **success** (~1m44s); `build` **success** (~2m22s, `assembleDebug` + `testDebugUnitTest`); `signed-qa` **skipped** | **PASS** |
| **Emulator Cat 6** (prepare + Gate A + Gate B ×3) | [**43618455567**](https://github.com/qadeer-cyber/TharMesh/actions/runs/43618455567) | `1550380` | `prepare-apks` **success** (~3m1s); `gate-a-legacy-upgrade` **success** (~2m20s); `gate-b-persistence` **1/2/3** **success** (~6m11s–6m41s each) | **PASS** (Gate A **PASS**, Gate B **3/3 PASS**) |
| JVM unit (local sanity) | `./gradlew :tharmesh.app:testDebugUnitTest` | `1550380` (agent) | — | **PASS** (same suite as CI `build` job) |

### CI artifacts (HEAD `1550380`)

| Artifact name | Artifact ID | Run |
|---------------|-------------|-----|
| `tharmesh-app-debug` | **14122819820** | Android **43618455459** |
| `unit-test-reports` | **14123560970** | Android **43618455459** |
| `cat6-apk-artifacts` | **14123171849** | Emulator **43618455567** |
| `gate-a-legacy-upgrade` | **14123795469** | Emulator **43618455567** (`gate_a_verdict=PASS`) |
| `gate-b-pass-1` | **14123617413** | Emulator **43618455567** (`RESULT=PASS`) |
| `gate-b-pass-2` | **14123912984** | Emulator **43618455567** (`RESULT=PASS`) |
| `gate-b-pass-3` | **14123998455** | Emulator **43618455567** (`RESULT=PASS`) |

### CI-built head debug APK (`1550380`)

| Check | Value |
|-------|--------|
| Source | Artifact `tharmesh-app-debug` from run **43618455459** |
| File SHA-256 | `e4642a017fd553029135574f57fd6468317525119af87af842218232f3623618` |
| Signing cert SHA-256 | `ac1a511884df9ec2cd8b0b8d696c3192b671cddc6c93f8e83ae9efd08221c2ec` |

### Emulator prep — v6 historical APK (9fedae9)

| Check | Value |
|-------|--------|
| Pinned v6 file SHA-256 | `e2f7145b975ff099566f3a8a2d02cbd236ba75ae67af7a29f4ffd8d43f900c89` |
| Cert SHA-256 | `ac1a511884df9ec2cd8b0b8d696c3192b671cddc6c93f8e83ae9efd08221c2ec` |
| CI note | Runs `bb5c72e`–`8e84847` **FAIL**d prepare-apks when head `assembleDebug` ran before isolated historical build; fixed in `1550380` (historical first + isolated `GRADLE_USER_HOME`). |

### NOT EXECUTED / NO (unchanged)

| Item | Status |
|------|--------|
| Physical 2-phone / 3-phone mesh | **NOT EXECUTED** |
| Hosted staging OTP / Edge enrollment | **NOT EXECUTED** / **NO** owner credentials |
| Signed-QA workflow job | **NOT EXECUTED** (skipped on CI branch pushes) |
| API 33+ emulator | **NOT EXECUTED** |
| Measured battery under load | **NOT EXECUTED** |
| `workflow_dispatch` on Actions | **403** for automation tokens — push-trigger used |

## GO / NO-GO

| Gate | Verdict |
|------|---------|
| **0.7 Engineering (emulator Cat 6)** | **GO** (baseline `9f7f50f`) |
| **0.8 Engineering (JVM + fresh Cat 6 on `1550380`)** | **NO-GO for field release** — physical + hosted staging + signed-QA + battery still open |
| **0.8 Physical / hosted** | **NO-GO** |
| **Declarable 0.8 complete** | **NO** — per owner directive **t148u** |

## Commits on this remediation branch (selected)

| Topic | Commit(s) |
|-------|-----------|
| Phase 3 negatives | `46c11d9` |
| Phase 4 UX states | `a7e1f79` |
| Phase 5 partial | `a993cb0` |
| CI branch triggers | `54c6615` |
| Staging guard v2 | `bb5c72e` |
| Emulator historical build isolation | `8e84847`, `1550380` |