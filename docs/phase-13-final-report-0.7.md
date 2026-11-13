# TharMesh Update 0.7 — Phase 13 final report (evidence)

## A — Run identity

| Field | Value |
|-------|--------|
| Cloud run | [bc-e43a1c33-85a3-5815-b17d-b4cf9a5c5aa9](https://cursor.com/agents/bc-e43a1c33-85a3-5815-b17d-b4cf9a5c5aa9) |
| `originalModelName` | **composer-2.5** |
| Branch | `cursor/universal-account-0939` |
| Report commit | `c288b0b` (historical Room v6 rebuild + legacy upgrade script; device gates still open) |
| Supervisor | Accepted honest **NOT EXECUTED** on `c7af23e`; **Engineering ready = NO** until device gates close |

## B — PR / CI / artifacts

| Item | Status |
|------|--------|
| PR #52 | **Draft** |
| PR #55 | **Unmerged** |
| CI on `c7af23e` | [43434968872](https://github.com/qadeer-cyber/TharMesh/actions/runs/43434968872) — postgres **success**, build **success**, signed-qa **skipped** |
| Prior accepted CI | [43426038834](https://github.com/qadeer-cyber/TharMesh/actions/runs/43426038834) (`fa3f8f1`) |

### APK identity (file SHA-256)

| SHA-256 | Source |
|---------|--------|
| `e2f7145b975ff099566f3a8a2d02cbd236ba75ae67af7a29f4ffd8d43f900c89` | **Rebuilt Room v6** debug APK from historical **`9fedae9`** + pinned `app/debug.keystore` (`scripts/build-historical-debug-apk.sh`; artifact copy `tharmesh-app-debug-9fedae9-room6.apk`) |
| `f27685be…` | Supervisor attachment — **not** matched to any CI re-download in inventory |
| `ac7c1117…` | `db91149` CI APK |
| `42f70734…` | `fa3f8f1` CI APK |
| `a6c96343…` | `b3d2dea` / `1070ff9` |

**Debug signing cert (all above debug APKs that use pinned keystore):** SHA-1 `55a7a32dca87f5393e1bf83b0b6f3ba5db235add`, SHA-256 `ac1a511884df9ec2cd8b0b8d696c3192b671cddc6c93f8e83ae9efd08221c2ec`.

## C — Category totals

| Cat | Status |
|-----|--------|
| 1 JVM (472) | **EXECUTED** |
| 2 Enrollment Cat 2 | **EXECUTED** (accepted) |
| 3 PG 001–014 / 190 assertions | **EXECUTED** |
| 4 Edge crypto | **EXECUTED** |
| 5 Hosted staging | **NOT EXECUTED** |
| **6 Emulator 3× full PASS** | **NOT EXECUTED** — `adb` never reached `device` after cold-boot (`connect 127.0.0.1:5555` **offline**; qemu running; see `legacy-build-transcript.txt`) |
| **6b Legacy Room v6→v7 on device** | **PARTIAL** — historical APK **built & signed** (`9fedae9`); on-device seed/upgrade **NOT EXECUTED** (same `adb` failure). Script: `LEGACY_SOURCE_COMMIT=9fedae9 … debug-keystore-upgrade-evidence.sh` |
| 7 Two-phone | **NOT EXECUTED** |
| Signed QA upgrade | **NOT EXECUTED** |

## D — Gate evidence (supervisor paths)

### Path 1 — Legacy schema (`9fedae9`, Room 6)

| Step | Result |
|------|--------|
| Worktree at last Room **v6** commit `9fedae9` | **DONE** |
| Copy pinned `app/debug.keystore` + debug `signingConfig` (build.gradle only) | **DONE** |
| `./gradlew assembleDebug` | **BUILD SUCCESS** |
| `apksigner` SHA-1 / SHA-256 | **Matches** pinned identity |
| Install → seed → `install -r` head → TSV/fp / `PRAGMA user_version` 6→7 | **NOT EXECUTED** — emulator/adb unavailable in this run |

Transcript: `/opt/cursor/artifacts/ui/0.7-evidence/debug-keystore-upgrade/legacy-build-transcript.txt`

**Label when device run succeeds:** `Cat 6 debug-keystore legacy upgrade, old APK rebuilt from historical source commit 9fedae917868340e9b2b93f46b847a6119c576bd`

### Path 2 — Emulator 3-pass

| Step | Result |
|------|--------|
| `adb kill-server` / kill qemu / cold-boot flags (`-no-snapshot-load -wipe-data`, `-gpu swiftshader_indirect`) | **ATTEMPTED** |
| `sys.boot_completed` + bootanim stopped + `emulator-0.7-evidence-3pass.sh` | **NOT EXECUTED** — no `adb` device; transcripts not produced |

## E — Owner checklist

(unchanged — staging OFF, PR #52 draft, signed-qa blocked)

## F — Verdicts

| Question | Verdict |
|----------|---------|
| Engineering ready | **NO** — conditional on **6b on-device** legacy migration + **6** triple `RESULT=PASS` |
| Staging validated | **NO** |
| Device validated | **NO** |
| Safe to merge PR #52 | **NO** |
| Safe to enable production admission | **NO** |

**Gate labels:** Engineering complete **NO** · Staging validated **NO** · Device validated **NO** · RC accepted **NO**
