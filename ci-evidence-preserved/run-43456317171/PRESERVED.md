# Preserved evidence — GitHub Actions run [43456317171](https://github.com/qadeer-cyber/TharMesh/actions/runs/43456317171)

Run failed the persistence gate (`pre-kill: messages=3 contacts=4 verified=4` — legacy v6 seed peers still present). **Gate A (Room v6→v7 in-place upgrade)** evidence is copied from the last passing upgrade transcript in CI run [43452041489](https://github.com/qadeer-cyber/TharMesh/actions/runs/43452041489).

| Item | Value |
|------|--------|
| Failed run URL | https://github.com/qadeer-cyber/TharMesh/actions/runs/43456317171 |
| Cancel | Bot token **403** — owner may cancel manually if still active |
| Gate A transcript SHA-256 | `04e924b9e18fab48a8d77700b09c3299854baf9cd861b517ee878ef046fc7a2f` |
| `old_room_user_version` | **6** |
| `new_room_user_version` | **7** |
| `migration_path` | Room_6_to_7 (automatic on open) |
| Gate A script result | `RESULT=PASS debug-keystore legacy schema upgrade` |
| v6 APK (rebuilt 9fedae9) file SHA-256 | `e2f7145b975ff099566f3a8a2d02cbd236ba75ae67af7a29f4ffd8d43f900c89` |
| Debug cert SHA-256 | `ac1a511884df9ec2cd8b0b8d696c3192b671cddc6c93f8e83ae9efd08221c2ec` |

Snapshots (before/after TSVs) for Gate A: `../gate-a-from-prior-runs/debug-keystore-upgrade/db-snapshot-{before,after}/`.

Cause of persistence FAIL on 43456317171: `run-cat6-emulator-evidence.sh` ran persistence suite on DB still containing legacy v6 `peer-a` / `peer-b` seed rows.
