# TharMesh 0.8 — Engineering report

**Branch:** `cursor/tharmesh-0.8-field-mesh-5aa9`  
**Base commit (0.7 gates):** `9f7f50f570a4d765f28debd183817b23bbb7ad27`  
**Agent:** Composer 2.5 (`originalModelName: composer-2.5`)  
**Report date:** 2026-11-14  

## Phase 0 — Baseline / 0.7 closure

**Satisfied** by independent supervisor verification:

- Run [**43547434502**](https://github.com/qadeer-cyber/TharMesh/actions/runs/43547434502)
- Gate A + Gate B passes **1–3** `RESULT=PASS` on one SHA
- APK file `3feee5d4…`, cert `ac1a5118…`

See `docs/phase-13-final-report-0.7.md`.

## Phase 1 — Offline transport reliability

| Item | Status | Evidence type |
|------|--------|----------------|
| Nearby transport off main thread | **Implemented** | `NearbyConnectionsTransport` callback thread |
| Send correlation / fail-closed | **Implemented** | `MeshEngineSendCorrelationTest` (JVM) |
| Bounded adb/evidence stages (CI harness) | **Implemented** | `emulator-0.7-evidence.sh` 180s stages |
| Physical BLE/Wi‑Fi Nearby | **NOT EXECUTED** | No Play Services device farm in cloud |

**Gap (documented):** No automated instrumented test of Play Services Nearby APIs.

## Phase 2 — Multi-hop mesh

| Item | Status | Evidence type |
|------|--------|----------------|
| Three-hop relay | **EXECUTED** | `MeshEngineRelayThreeHopTest` (JVM **simulation**) |
| Fault injection (drop, duplicate wire, delayed topology) | **EXECUTED** | `MeshEngineFaultInjectionTest` + `FaultInjectionTransport` (**simulation**) |
| Store-and-forward / TTL / pacing | **EXECUTED** | Existing `MeshEngineRetryTtlAndPacingTest`, `MeshStoreAndForwardCat2Test` |

**Not physical evidence:** All multi-hop rows are **JVM simulated mesh**, not three phones.

## Phase 3 — Crypto / identity negatives

| Item | Status | Evidence type |
|------|--------|----------------|
| Sealed payload / no plaintext relay | **EXECUTED** | `MeshEngineSecurityTest` |
| Signature / replay / TOFU | **EXECUTED** | `MeshEngineSecurityTest`, `MeshEngineSignatureTest` |
| JVM crypto / account tests | **EXECUTED** | Existing 0.7 suite |
| New staging guard | **EXECUTED** | `SupabaseEnvironmentGuardTest` |

**Security regression:** No checks weakened for 0.8 harness work.

## Phase 4 — Diagnostics UX

| Item | Status |
|------|--------|
| Live counters / export JSON | **Existing** (`DiagnosticsCollector`) |
| User-facing status strings (Stage 8.x) | **Existing** (`ChatStatusFormatterTest`) |
| Emulator ur/sd layout | **EXECUTED** on Cat 6 (API 30) |

## Phase 5 — Battery / lifecycle

| Item | Status |
|------|--------|
| Retry curves / pacing / churn | **EXECUTED** (JVM) |
| Process death / Room persistence | **EXECUTED** (JVM + emulator Gate B) |
| Measured battery on device | **NOT EXECUTED** |

## Phase 6 — Optional features

**Deferred** — Phases 1–3 green only in **simulated** scope; no bandwidth for attachments / group broadcast / SOS changes without physical validation.

## Phase 7 — Staging config

| Item | Status |
|------|--------|
| `THARMESH_SUPABASE_BACKEND_ENV=staging` + URL marker guard | **Implemented** (`SupabaseEnvironmentGuard`, `BuildConfig`) |
| Service role in APK | **Absent** (unchanged) |
| `identity_admission.enabled = false` | **Unchanged** (owner policy) |
| Hosted OTP / Edge on TharMesh-Staging | **BLOCKED / NOT EXECUTED** (no owner secrets in agent env) |

Configure owner builds with:

```properties
THARMESH_SUPABASE_BACKEND_ENV=staging
THARMESH_STAGING_URL_MARKER=tharmesh-staging
SUPABASE_URL=https://<your-staging-ref>.supabase.co
SUPABASE_ANON_KEY=<publishable-key>
```

## Evidence matrix

| Category | Run / artifact | SHA | Result |
|----------|----------------|-----|--------|
| Cat 6 Gate A+B | [43547434502](https://github.com/qadeer-cyber/TharMesh/actions/runs/43547434502) | `9f7f50f` | **PASS** |
| JVM unit (0.8 branch) | local `testDebugUnitTest` | (this branch HEAD) | See CI after push |
| Two-peer simulation | JVM Loopback / Bridged | — | **EXECUTED** |
| Three-peer simulation | JVM Bridged / FaultInjection | — | **EXECUTED** |
| Physical 2-phone | — | — | **NOT EXECUTED** (`docs/field-uat-0.8.md`) |
| Physical 3-phone relay | — | — | **NOT EXECUTED** |
| Hosted staging | — | — | **NOT EXECUTED** |
| Signed QA | [43489694681](https://github.com/qadeer-cyber/TharMesh/actions/runs/43489694681) | — | **WAITING** environment approval |
| API 33+ emulator | — | — | **NOT EXECUTED** |

**APK (0.7 gate run):** file `3feee5d48b895e9e2afa5c34617b8bdef82712cb7e7d07cb7916dcf16d9394bc` · cert `ac1a511884df9ec2cd8b0b8d696c3192b671cddc6c93f8e83ae9efd08221c2ec`.

## Security regression assessment

| Area | Assessment |
|------|----------------|
| E2E seal / relay ciphertext | **No regression**; tests retained |
| Cloud fail-closed | **Strengthened** staging URL guard |
| Evidence harness | **No assertion weakening** on Cat 6 |
| Logs / secrets | **No new debug dumps** of message bodies |

## GO / NO-GO matrix

| Gate | Verdict |
|------|---------|
| **0.7 Engineering Ready (emulator)** | **GO** (`9f7f50f`) |
| **0.8 Engineering Ready (field mesh code)** | **GO** for **simulated** transport/crypto acceptance on this branch; **NO-GO** for physical radios |
| **Hosted Staging Validated** | **NO-GO** |
| **Physical Device Validated** | **NO-GO** |
| **Release Candidate** | **NO-GO** |
| **Production** | **NO-GO** |

## Changelog (0.8 branch, technical)

- Shared `BridgedTransport` / `FaultInjectionTransport` test harness; `MeshEngineFaultInjectionTest`.
- Staging Supabase URL guard (`SupabaseEnvironmentGuard`, BuildConfig env/marker).
- 0.7 evidence doc closure; field UAT protocol; this report.