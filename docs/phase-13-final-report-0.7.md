# TharMesh Update 0.7 — Phase 13 final report (evidence)

## A — Run identity

| Field | Value |
|-------|--------|
| Cloud run | [bc-e43a1c33-85a3-5815-b17d-b4cf9a5c5aa9](https://cursor.com/agents/bc-e43a1c33-85a3-5815-b17d-b4cf9a5c5aa9) |
| `originalModelName` | **composer-2.5** |
| Branch (0.7 integration) | `cursor/universal-account-0939` |
| **Closed-gate commit** | **`9f7f50f570a4d765f28debd183817b23bbb7ad27`** |
| **Authoritative Cat 6 run** | [**43547434502**](https://github.com/qadeer-cyber/TharMesh/actions/runs/43547434502) — **all 5 jobs success** |
| PR #52 | **Draft** (0.7 track; not merged) |

## B — Cat 6 emulator — Gate closure (supervisor verified)

Workflow: `.github/workflows/emulator-evidence.yml` on commit **`9f7f50f`**.

| Job | Conclusion | Duration (approx.) |
|-----|------------|--------------------|
| prepare-apks | **success** | ~3m 31s |
| gate-a-legacy-upgrade | **success** | ~2m 44s |
| gate-b-persistence (1) | **success** | ~6m 2s |
| gate-b-persistence (2) | **success** | ~6m 15s |
| gate-b-persistence (3) | **success** | ~6m 18s |

### Gate A

| Check | Evidence |
|-------|----------|
| Room **v6 → v7** in-place upgrade | `RESULT=PASS debug-keystore legacy schema upgrade` |
| **LEGACY_V6_SEED** messages (3) | Preserved in before/after snapshots |
| Contacts **peer-a / peer-b** | Preserved |
| Verified **peer_identity** (2) | Preserved |
| Snapshot diffs | **messages.tsv, contacts.tsv, peer_identity.tsv, mesh_fingerprint.txt** unchanged |

Artifact: **gate-a-legacy-upgrade** id **14095360513** (run 43547434502).

### Gate B (3 independent passes)

Each pass: `RESULT=PASS`, `STAGE_END recovery_stage_death ok`, per-pass **uninstall+install** clean state, head APK SHA vs prepare-apks.

| Pass | Artifact ID | `RESULT` |
|------|-------------|----------|
| 1 | **14095301700** | `RESULT=PASS` |
| 2 | **14094732956** | `RESULT=PASS` |
| 3 | **14095248876** | `RESULT=PASS` |

**Head debug APK (run 43547434502)**

| | SHA-256 |
|--|---------|
| File | `3feee5d48b895e9e2afa5c34617b8bdef82712cb7e7d07cb7916dcf16d9394bc` |
| Signing cert | `ac1a511884df9ec2cd8b0b8d696c3192b671cddc6c93f8e83ae9efd08221c2ec` |

**Pinned v6 (prepare-apks):** file `e2f7145b975ff099566f3a8a2d02cbd236ba75ae67af7a29f4ffd8d43f900c89` · cert `ac1a511884df9ec2cd8b0b8d696c3192b671cddc6c93f8e83ae9efd08221c2ec` · source `9fedae9`.

### Harness notes (accepted)

- **API 33+** image checks: **NOT EXECUTED** (API 30 AVD only; transcript logs `API 33+ image: NOT EXECUTED`).
- **RecoveryRotationStage.save**: **`apply()` → `commit()`** for emulator stage-death durability (accepted).
- Recovery stage hang fix: no `am start -W` on instant-finish smoke; **180s** per-stage deadlines with diagnostics (commit `9f7f50f`).

## C — Category totals (0.7 scope)

| Cat | Status |
|-----|--------|
| 1 JVM | **EXECUTED** (regression on 0.8 branch continues) |
| 2 Enrollment Cat 2 | **EXECUTED** |
| 3 PG 001–014 | **EXECUTED** |
| 4 Edge crypto | **EXECUTED** |
| 5 Hosted staging | **NOT EXECUTED** (owner credentials / approval) |
| **6 Emulator Cat 6** | **EXECUTED** — Gates A+B **PASS** on **`9f7f50f`** |
| 7 Two-phone | **NOT EXECUTED** |
| Signed QA | **NOT EXECUTED** (e.g. [43489694681](https://github.com/qadeer-cyber/TharMesh/actions/runs/43489694681) awaiting environment approval) |

## D — 0.7 engineering verdict (closed)

| Question | Verdict |
|----------|---------|
| **Engineering Ready (0.7 emulator gates)** | **YES** on **`9f7f50f`** / run **43547434502** |
| Staging validated | **NO** |
| Device validated | **NO** |
| Safe to merge PR #52 | **NO** (owner policy; draft) |
| Safe to enable production admission | **NO** |
| Production | **NO** |

**0.8 work** branches from **`9f7f50f`** on `cursor/tharmesh-0.8-field-mesh-5aa9` (separate draft PR). PR #55 unchanged.