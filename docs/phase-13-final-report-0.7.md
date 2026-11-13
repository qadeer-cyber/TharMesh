# TharMesh Update 0.7 — Phase 13 final report (evidence)

## A — Run identity

| Field | Value |
|-------|--------|
| Cloud run | [bc-e43a1c33-85a3-5815-b17d-b4cf9a5c5aa9](https://cursor.com/agents/bc-e43a1c33-85a3-5815-b17d-b4cf9a5c5aa9) |
| `originalModelName` | **composer-2.5** |
| Branch | `cursor/universal-account-0939` |
| Report commit | `1643d72` (supervisor gate follow-up after accepted `db91149`) |
| Supervisor accepted | **`db91149`** partial — CI [43426038834](https://github.com/qadeer-cyber/TharMesh/actions/runs/43426038834) (`fa3f8f1`); enrollment Cat 2 + staging doc; debug **cert** match; gates **1–2** not accepted |

## B — PR / CI / artifacts

| Item | Status |
|------|--------|
| PR #52 | **Draft** (not merged) |
| PR #55 | **Unmerged** (Track A l10n) |
| `main` HEAD (base) | `31a9dbbaef764827ec1470ce5ed6b70ba21eed61` |
| Accepted CI | [43426038834](https://github.com/qadeer-cyber/TharMesh/actions/runs/43426038834) — postgres **success**, build **success**, signed-qa **skipped** (`fa3f8f1`) |
| Head CI | [43426580278](https://github.com/qadeer-cyber/TharMesh/actions/runs/43426580278) — `db91149` (after report metadata commits) |

### APK file SHA-256 identity (debug `tharmesh.app-debug.apk` bytes)

| SHA-256 (file) | Source |
|----------------|--------|
| `f27685be…` *(supervisor attachment)* | **Supervisor-attached APK** — does **not** match any CI artifact APK file SHA-256 re-fetched from Actions on 2026-11-13 (see rows below). Treat as an **unmapped** binary until tied to a run id. |
| `ac7c111711b36c59bd91a8820cecd3e61b13a4a88cba05671481e46553078704` | **`db91149`** — run [43426580278](https://github.com/qadeer-cyber/TharMesh/actions/runs/43426580278), artifact `14038511130` |
| `42f70734dfb5eb784eef2705fad2836f3c587dbb60f4cee2c95a4fbca91b6cfd` | **`fa3f8f1`** — run [43426038834](https://github.com/qadeer-cyber/TharMesh/actions/runs/43426038834), artifact `14037207023` |
| `8c53d4c07e83c64059e1e2c1d68f745ec2108537bbb0f6eb5681c9be4c7ebdad` | **`ed415e6`** — run [43425502383](https://github.com/qadeer-cyber/TharMesh/actions/runs/43425502383), artifact `14037715463` |
| `a6c963432eac8d65e24ea4406aef2ed48e652e10226627911ce1b0f9edb061b2` | **`b3d2dea` / `1070ff9`** — run [43408800759](https://github.com/qadeer-cyber/TharMesh/actions/runs/43408800759) (same file hash as `/1070ff9` branch artifact download) |
| `e9d579d9e74190d84fbeba226e52071c95a800d69dfd1a193861529efeb19cc4` | **`main` `31a9dbb`** — run [43196099406](https://github.com/qadeer-cyber/TharMesh/actions/runs/43196099406), artifact `13932374356` |
| `323acded41159f755ec9f58004bbe194f971cfce2d7c7462a51a42b1b695ba0a` | **`main` `a2e14177`** — run [42981974505](https://github.com/qadeer-cyber/TharMesh/actions/runs/42981974505), artifact `13837853468` |
| `eeda2973e713863011d1014a2790dede816d6cf1de2e5cda43a32af318de24c4` | **Local** `./gradlew assembleDebug` on branch head (not a CI artifact) |

**Signing cert (all debug CI APKs above, supervisor-verified):** SHA-1 `55a7a32d…`, SHA-256 `ac1a511884df9ec2cd8b0b8d696c3192b671cddc6c93f8e83ae9efd08221c2ec` (`apksigner verify --print-certs`).

> **Correction:** Prior report cited `42f70734` and `8ac1cc8f…` — `42f70734` is correct for **`fa3f8f1`**; `8ac1cc8f…` was **incorrect** for the 1070ff9/b3d2deac line (actual file hash `a6c96343…`).

## C — Category totals (executed vs NOT EXECUTED)

| Cat | Name | Count / scope | Status |
|-----|------|----------------|--------|
| **1** | Pure JVM unit | **472** tests | **EXECUTED** |
| **2** | Mock-backed integration | **+6** `EnrollmentFlowDecisionsTest` (supervisor **accepted**) | **EXECUTED** |
| **3** | Real Postgres | **190** assertions, migrations **001–014** | **EXECUTED** |
| **4** | Local Edge / Node crypto | CI Deno/tsx | **EXECUTED** |
| **5** | Hosted staging | Owner Supabase | **NOT EXECUTED** (admission **OFF**) |
| **6** | Emulator full script | `emulator-0.7-evidence.sh` + **3 consecutive `RESULT=PASS`** | **NOT EXECUTED** on this follow-up (post-reboot UI hardening landed; **3-pass gate not completed** — emulator `adb` stuck **offline** after multi-instance restarts) |
| **6b** | Debug-keystore **legacy schema** upgrade | Room **v6 → v7** (or lower → current) with TSV/fp diff | **NOT EXECUTED** — no downloadable CI debug APK with Room `user_version` **< 7** (see D) |
| **6b'** | Debug-keystore reinstall (same Room v7) | 1070ff9 → head | **Invalid gate** (reinstall durability only; supervisor **rejected** as legacy migration) |
| **7** | Physical two-phone UAT | | **NOT EXECUTED** |
| — | API 33/34 emulator | | **NOT EXECUTED** |
| — | Signed QA / release upgrade | | **NOT EXECUTED** |

## D — Evidence highlights (supervisor follow-up)

### Gate 1 — Legacy Room schema migration (`debug-keystore-upgrade-evidence.sh`)

- **Inventory:** `scripts/legacy-ci-apk-audit.sh` → `/opt/cursor/artifacts/ui/0.7-evidence/debug-keystore-upgrade/legacy-ci-apk-audit.txt`
- **Finding:** Every **non-expired** `tharmesh-app-debug` Actions artifact (paginated API, 2026-11-13) maps to a commit whose `AppDatabase` **`version = 7`** — including **`main` `31a9dbb`**, **`main` `a2e14177`**, and earliest **PR #52** artifacts (`e2a60b40…`).
- **Room v6 commits** (e.g. `9fedae9` on `main`) have **no** retained CI debug APK (April 2026 workflow runs return **0** artifacts — expired).
- **Attempt with oldest CI APK (`main` `31a9dbb`, file SHA `e9d579d9…`):** old and new **`PRAGMA user_version` = 7** → `migration_path=NONE`; script exits **`RESULT=NOT_EXECUTED legacy_schema_migration`** (no substitute local APK per supervisor).
- **Prior 1070ff9 run:** same Room v7 → **reinstall only**, not legacy migration.

### Gate 2 — Emulator three full passes

- **Hardening:** `wait_for_boot_settled` (bootanim stopped), ANR dismiss via uiautomator **Wait** bounds + `CLOSE_SYSTEM_DIALOGS`, post-reboot **6×** UI relaunch attempts, mandatory ur/sd uiautomator (2/2).
- **Runner:** `scripts/emulator-0.7-evidence-3pass.sh` → separate `adb-transcript-run{1,2,3}.txt`.
- **Status:** **NOT EXECUTED** — could not obtain **3 consecutive `RESULT=PASS`** in this session (emulator never returned to `adb devices` **device** after recovery attempts). Prior partial runs (data-only TSV) are **not** a gate pass.

### Accepted (unchanged)

- **Phase 7** `docs/staging-package-0.7.md` — migrations **001–014**, platform-defaults, auth RPC surface, Edge list, admission **OFF**.
- **Enrollment Cat 2** — `EnrollmentFlowDecisions` + tests; **Linked** gated.

## E — Owner-only checklist

(unchanged — staging, admission OFF, PR #52 draft review, signed-qa blocked)

## F — YES/NO verdicts

| Question | Verdict |
|----------|---------|
| Engineering ready | **NO** — conditional on supervisor gates **1** (legacy CI Room migration) and **2** (3× emulator full `RESULT=PASS`); CI **43426038834** green is necessary but **not sufficient** |
| Staging validated on hosted Supabase | **NO** |
| Physical device validated | **NO** |
| Safe to merge PR #52 (draft) | **NO** |
| Safe to enable production admission | **NO** |

**Gate labels:** Engineering complete **NO** (pending 6b legacy + 6 triple-pass) · Staging validated **NO** · Device validated **NO** · RC accepted **NO**
