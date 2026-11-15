# TharMesh 0.9 — Engineering report

**Branch:** `cursor/tharmesh-0.9-engineering-5aa9`  
**Base (stacked):** `cursor/tharmesh-0.8-field-mesh-5aa9` @ `4ec45fe`  
**Draft PR:** [#57](https://github.com/qadeer-cyber/TharMesh/pull/57) (stacked on 0.8, not `main`)  
**Engineering HEAD:** `7b354acac0a78575bc35e9a8c12c957dbbdbb16d`  
**Report date:** 2026-11-15  

> **0.9 is in progress — not release-ready.** Physical UAT, hosted staging OTP/Edge, signed-QA upgrade, and measured battery remain **NOT EXECUTED** unless linked GitHub evidence exists below.

## Phase labels (owner directive t150u)

| Phase | Label | Evidence |
|-------|-------|----------|
| **1** Transport reliability | **PASS** (JVM **simulation**) | Exponential reconnect backoff (`NearbyMeshConnectPolicy`), PayloadSent on transfer **SUCCESS** (`NearbyOutboundSendTracker`), tests `NearbyMeshConnectPolicyBackoffTest`, `NearbyOutboundSendTrackerTest`, existing correlation tests |
| **2** Multi-hop / relay | **PASS** (JVM **simulation**) | Existing relay suite + `MeshEnginePhase3RelayDuplicationTest` (injection counter asserted) |
| **3** E2EE security | **PASS** (JVM **simulation**) | `MeshEngineSecurityTest`, `MeshEnginePhase3NegativeTest`, relay duplication test |
| **4** Android 13/14/15 | **PASS** (emulator evidence) / **PASS** (JVM) | JVM `NearbyPermissionsSdkMatrixTest`; PR run [**43731539094**](https://github.com/qadeer-cyber/TharMesh/actions/runs/43731539094) **PASS** API **33/34/35** |
| **5** Messaging UX | **PASS** (JVM) | `MessageStatus.RELAYING`, `ChatStatusFormatter` Connecting/Relaying + ur/sd strings |
| **6** Battery / background | **PARTIAL** | `MeshForegroundServiceGuardTest`, `scripts/battery-measurement-60m.sh`; **measured battery: NOT EXECUTED** |
| **7** Hosted staging | **BLOCKED** | No owner staging creds in CI — see checklist below |
| **8** Signed QA + upgrade | **NOT EXECUTED** | `signed-qa` job not authorized on this branch |
| **9** CI | **PASS** (cloud) | PR [**#57**](https://github.com/qadeer-cyber/TharMesh/pull/57) — **15/15** checks green @ `7b354ac` (Android CI + Cat6 Gate A + Gate B 3/3 + API 33–35) |

## CI evidence matrix

| Workflow | Run URL | Head SHA | Result |
|----------|---------|----------|--------|
| Android CI | [**43731539092**](https://github.com/qadeer-cyber/TharMesh/actions/runs/43731539092) | `7b354ac` | **PASS** — postgres + build + unit tests |
| Emulator Cat 6 (API 30) | [**43731539047**](https://github.com/qadeer-cyber/TharMesh/actions/runs/43731539047) | `7b354ac` | **PASS** — Gate A + Gate B **3/3** |
| Android API 33–35 matrix | [**43731539094**](https://github.com/qadeer-cyber/TharMesh/actions/runs/43731539094) | `7b354ac` | **PASS** — api-emulator-evidence **33/34/35** |

### CI-built head APK (run `43731539092`)

| Field | Value |
|-------|--------|
| Artifact name | `tharmesh-app-debug` |
| Artifact ID | **14169190809** |
| File SHA-256 | `e94f92fd1b6c7c52909adecddd56cdc6bdcdbc4185a62b5372bdb7cb786b8fe6` |
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