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


### 2026-11-17 — chunk pipeline reliability review (clean-room Nukkit reference)
- Reviewed the existing NEXORA chunk generation, game-thread commit pipeline and player ticket manager before changing behavior.
- Studied public PowerNukkitX/Nukkit documentation and release notes as an architectural reference only: chunk storage exposes separate load/save/unload operations; chunk APIs distinguish generation/population/finish/initialization states; recent PowerNukkitX fixes specifically address retrying failed chunk loads, deduplicating player chunk requests and reducing per-tick streaming allocations. References: https://powernukkitx.github.io/PowerNukkitX/org/powernukkitx/level/format/IChunk.html, https://powernukkitx.github.io/PowerNukkitX/org/powernukkitx/level/format/LevelProvider.html, https://powernukkitx.org/changelog.
- No Nukkit/PowerNukkit source code, classes, assets or implementation was copied or added as a dependency. The observations are used only to shape NEXORA's own lifecycle and reliability requirements.
- Fixed a NEXORA-specific waiter leak: if the chunk failure-reporting callback itself throws while a generation result is drained, an async chunk request now still completes with `false` instead of remaining unresolved forever.
- Added a regression test for that failure path in `WorldChunkPipelineTest`.
- Verification: code and test are committed; CI has not yet been confirmed. The previously observed runner-allocation failure remains an infrastructure blocker, so no passing-test claim is made.
- Commit references: `6a1bd82c7d3cb943262a646b08490b01a37be506` (fix), `e6a9f31430cca5ee01736b0eef0b42deaba262d4` (regression test).


### 2026-11-17 — fatal chunk-generator failure path
- Continued the chunk pipeline audit without waiting for another prompt.
- Found a second unresolved-request path: `AsyncChunkGenerationService` previously removed an in-flight reservation and rethrew a fatal `Error` without publishing a completion. Any `requestAsync` waiter for that chunk could then remain incomplete indefinitely.
- Changed the service to publish a bounded failure result (retaining the original fatal error as the cause) before rethrowing it. The game-thread pipeline can now report the failure and complete the waiter with `false`.
- Added a regression test proving that fatal generator errors are surfaced and the asynchronous waiter resolves.
- Verification: changes committed; no passing CI result is claimed until a runner executes the workflow.
- Commit references: `d24710b1159bb676398b0f8bde46c27f794bb4b1` (fix), `904b40286f209ee9aa6d7b4a881f82407846dc76` (test).

## Next tasks — in order

1. **Verify current CI** for the active branch and fix any compilation/test failures before adding more code.
2. **World persistence hardening:** initial review and regression coverage are in place (atomic replacement, CRC32C, version/signature/size/count validation, corruption/truncation/trailing-byte tests). Before changing the file format, add a bounded-write safeguard and a documented recovery/backup policy.
3. **World lifecycle and multi-world configuration:** existing tests cover startup rollback, reuse of pre-registered worlds, restart persistence and retry after failed shutdown save; extend only where a concrete gap is found.
4. **Chunk lifecycle and pipeline correctness:** define NEXORA-owned states (generated, persisted, loaded, initialized/ready) and explicit transitions; then test cancellation, retry after generation/load failure, back-pressure, bounded memory/queues, duplicate requests and clean shutdown under load. Use Nukkit/PowerNukkitX only as a public behavior reference; no source/API copying.
5. **Entity and player simulation:** authoritative movement/collision boundaries, deterministic tick behavior and persistence.
6. **Dedicated server packaging:** verify clean install, documented config, startup, save/restart and shutdown using CI artifacts.

## Definition of core-ready

Do not start magic/RPG implementation until the dedicated core can pass automated tests for clean installation, world creation, chunk generation/loading, block/world persistence across restart, entity/player simulation, bounded networking and clean shutdown. Record actual CI evidence and any remaining limitations here.

## Session hand-off template

- Completed: added `.nxw` future-version, truncation and trailing-byte rejection tests; reviewed world lifecycle rollback/retry coverage; reviewed chunk pipeline against public Nukkit/PowerNukkitX architecture references; fixed async waiter completion when the failure reporter throws and when the generator throws a fatal `Error`, with regression tests for both.
- Tests/CI: tests are committed but not confirmed passing; workflow runs fail before runner allocation and logs/annotations are inaccessible through the current GitHub connection.
- Not completed / blockers: CI infrastructure/dispatch failure of unknown cause; no evidence yet of a code compilation failure.
- Next exact task: restore a working CI runner/verification path; continue the chunk lifecycle audit with worker interruption, cancellation races, retry semantics and shutdown behavior; then define NEXORA's explicit chunk lifecycle state machine before expanding streaming behavior.
- Commit SHA:
