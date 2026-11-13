# TharMesh Update 0.7 — Phase 13 final report (evidence)

## A — Run identity

| Field | Value |
|-------|--------|
| Cloud run | [bc-e43a1c33-85a3-5815-b17d-b4cf9a5c5aa9](https://cursor.com/agents/bc-e43a1c33-85a3-5815-b17d-b4cf9a5c5aa9) |
| `originalModelName` | **composer-2.5** |
| Branch | `cursor/universal-account-0939` |
| Report commit | `ffa7432` (consolidated 0.7 evidence + enrollment Cat 2) |
| Supervisor accepted | `b3d2dea` (CI [43408800759](https://github.com/qadeer-cyber/TharMesh/actions/runs/43408800759)) |

## B — PR / CI / artifacts

| Item | Status |
|------|--------|
| PR #52 | **Draft** (not merged) |
| PR #55 | **Unmerged** (Track A l10n) |
| `main` HEAD (base) | `31a9dbbaef764827ec1470ce5ed6b70ba21eed61` |
| Pre-push green CI | [43408800759](https://github.com/qadeer-cyber/TharMesh/actions/runs/43408800759) — postgres **success**, build **success**, signed-qa **skipped** |
| Debug APK (43408800759) | artifact `14028079166` — 6,751,582 bytes — SHA-256 `8ac1cc8fbf7e1bdc9a17fcab57ab51a0ee2ff393b2a41ef48e3ea052aa580a14` |
| Post-push CI | *(record run URL after consolidated push)* |

## C — Category totals (executed vs NOT EXECUTED)

| Cat | Name | Count / scope | Status |
|-----|------|----------------|--------|
| **1** | Pure JVM unit | **472** tests (`:tharmesh.app:testDebugUnitTest`) | **EXECUTED** |
| **2** | Mock-backed integration | **+6** `EnrollmentFlowDecisionsTest` (pending / approved / rejected / expired / backend error; **Linked** gated on server confirm); prior Cat-2 suites unchanged | **EXECUTED** |
| **3** | Real Postgres | `ci-postgres-tests.sh` — migrations **001–014**, race suite, auth RPC surface (tsx on Node 20) | **EXECUTED** (CI postgres; supervisor: **190** assertions, no migration edits on `b3d2dea`) |
| **4** | Local Edge / Node crypto | Deno edge crypto tests in CI | **EXECUTED** |
| **5** | Hosted staging | Owner Supabase | **NOT EXECUTED** (no credentials; admission **OFF**) |
| **6** | Emulator (AVD `tm_api30`, API 30, KVM `/dev/kvm`) | `scripts/emulator-0.7-evidence.sh` — offline TSV, `adb reboot` + `sys.boot_completed`, dark layout, ur-PK / sd-PK uiautomator | **EXECUTED** (data + screenshots; API 30 headless System UI ANR can flake full script `RESULT=PASS`) |
| **6b** | Debug-keystore upgrade | Old CI artifact `14025029716` (1070ff9) → `install -r` head debug; `apksigner` cert match | **EXECUTED** — `RESULT=PASS debug-keystore upgrade`; signed QA/release upgrade **NOT EXECUTED** |
| **7** | Physical two-phone UAT | `docs/two-phone-uat-0.7.md` | **NOT EXECUTED** |
| — | API 33/34 emulator matrix | `sdkmanager` images not installed (only `tm_api30`) | **NOT EXECUTED** |
| — | Signed QA / release APK upgrade | `signed-qa` workflow | **NOT EXECUTED** (env blocked) |

**PG:** Cross-account `revokeViaSvc` reject assertion preserved (`device-revoke-race.test.mjs`).

## D — Evidence highlights

- **Staging doc (Phase 7):** `docs/staging-package-0.7.md` — migrations **001–014**, platform-defaults SQL, authenticated RPC surface (`docs/authorization-surface-audit.md`), five Edge functions, **014** defense-in-depth, `identity_admission.enabled` **OFF**.
- **Enrollment Cat 2:** `EnrollmentFlowDecisions.kt` + tests — `mayShowLinked` requires `cloudEnrollmentConfirmed && cloudDevicesConfirmed`; approved-without-finish stays **Unconfirmed**, not **Linked**.
- **Room offline (emulator):** `messages.tsv` / `contacts.tsv` / `peer_identity.tsv` / `mesh_fingerprint.txt` under `/opt/cursor/artifacts/ui/0.7-evidence/db-snapshot-{pre_kill,post_relaunch,post_reboot}` — pre-kill vs post-relaunch vs post-reboot **match** on successful runs.
- **Reboot durability:** `adb reboot`, wait `sys.boot_completed`, same TSV diff — **EXECUTED** (`reboot durability TSV match: PASS` in transcript).
- **Dark / l10n:** `layout-account-light.png`, `layout-account-dark.png`, `layout-account-ur.png`, `layout-account-sd.png` (Urdu/Sindhi needles in uiautomator).
- **Debug-keystore upgrade:** Cert SHA-256 **`ac1a511884df9ec2cd8b0b8d696c3192b671cddc6c93f8e83ae9efd08221c2ec`** (old + new); transcript `/opt/cursor/artifacts/ui/0.7-evidence/debug-keystore-upgrade/upgrade-transcript.txt`.
- **Screenshots:** `01`–`06` and `layout-*` under `/opt/cursor/artifacts/ui/0.7-evidence/`.

## E — Owner-only checklist

- Enable hosted staging + OAuth + OTP on a **non-production** Supabase project.
- Keep `identity_admission.enabled = false` until explicit go-live (documented **OFF**).
- Review PR #52 (draft); keep PR #55 separate until Track A sign-off.
- Configure `signed-qa` for release/QA APK path (not exercised here).

## F — YES/NO verdicts

| Question | Verdict |
|----------|---------|
| Engineering ready (CI green, migrations 001–014, Cat 3 race + surface) | **YES** (re-verify post-push CI on consolidated commit) |
| Staging validated on hosted Supabase | **NO** — Cat 5 NOT EXECUTED |
| Physical device validated | **NO** — Cat 7 NOT EXECUTED |
| Safe to merge PR #52 (draft) to `main` | **NO** — remain draft until owner review + staging gate |
| Safe to enable production admission | **NO** |

**Gate labels:** Engineering complete **YES** · Staging validated **NO** · Device validated **NO** · RC accepted **NO**
