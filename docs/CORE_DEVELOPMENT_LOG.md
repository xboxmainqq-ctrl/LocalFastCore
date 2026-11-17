# NEXORA Core — development log and next steps

This is the hand-off log for core work. Update it after every meaningful implementation change. The user explicitly set the order: build the general-purpose NEXORA Core first; add magic and RPG gameplay only after the core foundations are ready.

## Current scope

Work only on engine/server foundations:
- server lifecycle, configuration, diagnostics and safe shutdown;
- worlds, dimensions, chunks, block states and world persistence;
- deterministic generation and bounded asynchronous chunk loading;
- entities, collision, movement and simulation;
- player sessions, inventories and authoritative interactions;
- transport-neutral protocol, authentication and safe networking;
- versioned extension API, data-driven content and distribution.

**Out of scope until the core milestone is accepted:** RPG classes/progression, magic, spells, abilities, quests, bosses, RPG economy and RPG-specific plugins. Existing RPG code may remain in the repository, but do not expand it during this phase.

## Work log

### 2026-11-17 — server configuration reliability
- Implemented atomic replacement when saving `server.properties`: write to a temporary file, then replace the target; use a non-atomic replacement only when the filesystem does not support atomic moves.
- Ensure an unsuccessful replacement cleans up the temporary file.
- Added tests for saving/reloading updated settings, temporary-file cleanup, and malformed numeric settings.
- Also enabled the server workflow on the active development branch in commit `cbdb37ce100771c957a25d5af39fbb66d95a62c3`.
- Code commits: `594b650dde85c1b2d283f38e1bb4d3c2a8c42438` (atomic config save), `dbde27b8d145f690e47348f7a1f134489a58b5d0` (tests).
- Verification status: changes committed to GitHub; CI result has not yet been confirmed in this log. Do not describe the tests as passing until CI reports success.


### 2026-11-17 — world snapshot validation tests
- Confirmed the existing `.nxw` reader already validates the magic signature, supported format range, world ID, bounded file size/counts, duplicate records, checksum and trailing data; the writer already stages a temporary snapshot and atomically replaces the destination where supported.
- Added regression tests proving that future unsupported format versions, truncated snapshots and bytes appended after the checksum are rejected.
- Test commit: `940cde47e246dcddcf042deef9cf835c6937cf15`.

- Follow-up CI result for test commit `940cde47e246dcddcf042deef9cf835c6937cf15`: workflow run `44096227872` failed in about two seconds before a runner was allocated (`runner_id=0`, no job steps reported). The available GitHub connection could not retrieve annotations or logs, so this is an infrastructure/dispatch blocker of unknown cause, not evidence of a Java compilation or test failure. Do not mark tests as passed.

- CI: the previous workflow run for configuration work (run 44095600935) ended in failure. GitHub exposed no job steps/log payload for that run, so its cause is not yet established. The new test commit triggers another verification run; record its result after it completes.

## Next tasks — in order

1. **Verify current CI** for the active branch and fix any compilation/test failures before adding more code.
2. **World persistence hardening:** inspect `.nxw` storage for atomic writes, corruption detection, format/version validation and recovery behavior; add focused tests before changing the format.
3. **World lifecycle and multi-world configuration:** ensure create/load/unload/save flows are explicit, safe and independently testable.
4. **Chunk pipeline correctness:** cancellation, back-pressure, bounded memory/queues, duplicate requests and clean shutdown under load.
5. **Entity and player simulation:** authoritative movement/collision boundaries, deterministic tick behavior and persistence.
6. **Dedicated server packaging:** verify clean install, documented config, startup, save/restart and shutdown using CI artifacts.

## Definition of core-ready

Do not start magic/RPG implementation until the dedicated core can pass automated tests for clean installation, world creation, chunk generation/loading, block/world persistence across restart, entity/player simulation, bounded networking and clean shutdown. Record actual CI evidence and any remaining limitations here.

## Session hand-off template

- Completed:
- Tests/CI:
- Not completed / blockers:
- Next exact task:
- Commit SHA:
