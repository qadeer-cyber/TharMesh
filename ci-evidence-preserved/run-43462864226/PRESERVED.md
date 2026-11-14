# Run [43462864226](https://github.com/qadeer-cyber/TharMesh/actions/runs/43462864226) — cancelled request (bot **403**)

| Field | Value |
|-------|--------|
| Commit | `de0c852` |
| Mode | Monolithic 3-pass (superseded by split jobs) |
| Cancel | Agent token could not cancel — **owner should cancel** if still `in_progress` |
| Hang | Pass 2 at **emulator reboot durability** (unbounded wait; fixed in `de0c852`+ via `REBOOT_BOOT_MAX_SEC` / strict boot) |

## Historical pass 1 (does not count for final acceptance)

Prior monolithic run [43460956240](https://github.com/qadeer-cyber/TharMesh/actions/runs/43460956240) at commit **`50bcd5b`** recorded **`run1=PASS`** / `RESULT=PASS` before pass-2 `emu kill` regression.

That pass predates the current assertion set on commit **`de0c852`** (3-pass no emu-kill, strict timeouts, split jobs). **Not final acceptance.**

Artifact id from 43460956240: **14056793543** (`emulator-evidence-cat6`, SHA-256 bundle logged in agent notes).

Gate A transcript (6→7) from same run: `old_room_user_version=6`, `new_room_user_version=7`, `RESULT=PASS debug-keystore legacy schema upgrade`.
