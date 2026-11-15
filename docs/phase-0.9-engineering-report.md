# TharMesh 0.9 — Engineering report

**Branch:** `cursor/tharmesh-0.9-engineering-5aa9`  
**Base (stacked):** `cursor/tharmesh-0.8-field-mesh-5aa9` @ `4ec45fe`  
**Draft PR:** [#57](https://github.com/qadeer-cyber/TharMesh/pull/57) (stacked on 0.8, not `main`)  
**Engineering HEAD:** `b3d1b37e701f3c3bb267bae6f798ad099e2f6c0f`  
**Report date:** 2026-11-15  

> **0.9 is in progress — not release-ready.** Physical UAT, hosted staging OTP/Edge, signed-QA upgrade, and measured battery remain **NOT EXECUTED** unless linked GitHub evidence exists below.

## Phase labels (owner directive t150u)

| Phase | Label | Evidence |
|-------|-------|----------|
| **1** Transport reliability | **PASS** (JVM **simulation**) | Exponential reconnect backoff (`NearbyMeshConnectPolicy`), PayloadSent on transfer **SUCCESS** (`NearbyOutboundSendTracker`), tests `NearbyMeshConnectPolicyBackoffTest`, `NearbyOutboundSendTrackerTest`, existing correlation tests |
| **2** Multi-hop / relay | **PASS** (JVM **simulation**) | Existing relay suite + `MeshEnginePhase3RelayDuplicationTest` (injection counter asserted) |
| **3** E2EE security | **PASS** (JVM **simulation**) | `MeshEngineSecurityTest`, `MeshEnginePhase3NegativeTest`, relay duplication test |
| **4** Android 13/14/15 | **PASS** (emulator evidence) / **PASS** (JVM) | JVM `NearbyPermissionsSdkMatrixTest`; run [**43739151778**](https://github.com/qadeer-cyber/TharMesh/actions/runs/43739151778) **PASS** API **33/34/35** |
| **5** Messaging UX | **PASS** (JVM) | `MessageStatus.RELAYING`, `ChatStatusFormatter` Connecting/Relaying + ur/sd strings |
| **6** Battery / background | **PARTIAL** | `MeshForegroundServiceGuardTest`, `scripts/battery-measurement-60m.sh`; **measured battery: NOT EXECUTED** |
| **7** Hosted staging | **BLOCKED** | No owner staging creds in CI — see checklist below |
| **8** Signed QA + upgrade | **NOT EXECUTED** | `signed-qa` job not authorized on this branch |
| **9** CI | **PASS** (cloud) | Branch @ `b3d1b37` — **15/15** checks green (v6 **cert-only** verify; Cat6 **push-only** dedupe) |

## CI evidence matrix

| Workflow | Run URL | Head SHA | Result |
|----------|---------|----------|--------|
| Android CI | [**43739151726**](https://github.com/qadeer-cyber/TharMesh/actions/runs/43739151726) | `b3d1b37` | **PASS** — postgres + build + unit tests |
| Emulator Cat 6 (API 30) | [**43739145104**](https://github.com/qadeer-cyber/TharMesh/actions/runs/43739145104) | `b3d1b37` | **PASS** — Gate A + Gate B **3/3** |
| Android API 33–35 matrix | [**43739151778**](https://github.com/qadeer-cyber/TharMesh/actions/runs/43739151778) | `b3d1b37` | **PASS** — api-emulator-evidence **33/34/35** |

### CI-built head APK (run `43739151726`)

| Field | Value |
|-------|--------|
| Artifact name | `tharmesh-app-debug` |
| Artifact ID | **14171547259** |
| File SHA-256 | `7fe3ea69d69ea01e4b6c5822da1a7af01010a0991959ce7600e6a83a72a73c4d` |
| Cert SHA-256 | `ac1a511884df9ec2cd8b0b8d696c3192b671cddc6c93f8e83ae9efd08221c2ec` |

## Phase 7 — Owner staging checklist (BLOCKED)

Configure in GitHub **repository secrets/variables** (names only — do not commit values):

| Name | Purpose |
|------|---------|
| `SUPABASE_URL` | Staging `https://<ref>.supabase.co` only |
| `SUPABASE_ANON_KEY` | Publishable anon key only |
| `GOOGLE_WEB_CLIENT_ID` | OAuth web client for staging |
| `THERMESH_STAGING_PROJECT_REF` | Exact project ref (fail-closed guard) |
| `THERMESH_PRODUCTION_PROJECT_REF` | Exact prod ref (release builds) |

Supabase dashboard: enable Email OTP + SMTP; Google OAuth redirect URLs for staging app id `tharmesh.app`; Edge Functions `device-enrollment`, `sensitive-auth` deployed to **staging** only.

## Known blockers

1. **Physical 2-phone / 3-phone UAT** — NOT EXECUTED (`docs/field-uat-0.9.md`).
2. **Hosted staging OTP / Edge** — BLOCKED (no creds).
3. **Signed-QA upgrade evidence** — NOT EXECUTED (`THARMESH_QA_SIGNING_ENABLED` not set).
4. **API 35 emulator** — may FAIL on runner if system image missing (honest FAIL, not fabricated PASS).
5. **Stacked merge** — blocked on PR #52 / #56 owner order.

## Gates (explicit)

| Gate | Verdict |
|------|---------|
| JVM engineering | **PARTIAL** (local green; CI URLs pending) |
| Emulator API 30 Gate A + B 3/3 | **pending CI** |
| Android 13+ API matrix | **pending CI** |
| Physical mesh | **NOT EXECUTED** |
| Hosted staging | **BLOCKED** |
| Signed QA upgrade | **NOT EXECUTED** |
| Measured battery | **NOT EXECUTED** |
| Production admission | **NO** (owner explicit approval required) |