# NEXORA Core — development log and next steps

This is the hand-off log for NEXORA Core and its server. The latest user direction (2026-11-17) excludes an Android client and a separate RPG server. Keep general-purpose server/core work in scope, including native Mob AI; plan the Core AI Assistant and Moderation AI Assistant as later optional modules. See docs/NEXORA_AI_SYSTEMS_ROADMAP.md.

## Current scope

Work only on engine/server foundations:
- server lifecycle, configuration, diagnostics and safe shutdown;
- worlds, dimensions, chunks, block states and world persistence;
- deterministic generation and bounded asynchronous chunk loading;
- entities, collision, movement and simulation;
- player sessions, inventories and authoritative interactions;
- transport-neutral protocol, authentication and safe networking;
- versioned extension API, data-driven content and distribution.

**Out of scope:** Android client, separate RPG server/product, RPG classes/progression, magic, spells, abilities, quests, bosses, RPG economy, AI mods and RPG-specific plugins. Existing historical code may remain untouched. Mob AI and the two operator/moderation assistant foundations are now in the repository; they are incomplete and must remain optional.

## Work log

### 2026-11-17 — initial AI subsystem foundations
- Added `MobAiController` and wired it into the authoritative `EntityTickEngine`. Configured mobs can use PASSIVE, NEUTRAL or HOSTILE profiles; hostile perception checks nearby players and line of sight, neutral mobs can be provoked, pursuit checks loaded chunks and safe footing, and attack-range decisions emit an intent without applying damage.
- Added regression tests for hostile pursuit, neutral provocation, and blocked line of sight. Pathfinding, combat/damage, profile persistence and load-budget enforcement are still incomplete.
- Added `AiToolRegistry` and `CoreAiAssistant`: registered tools require explicit permissions, mutating tools require explicit approval, and audit events omit arguments. Added optional `AiModelProvider` SPI with bounded prompts/context and rejection of sensitive context keys; Core works without a provider.
- Added `ModerationSignalAnalyzer` and `ModerationAiAssistant`: bounded per-player history, repeated-message and message-rate signals, SHA-256 fingerprints instead of stored raw chat, explicit player-data deletion, and mandatory human review. Optional model summaries receive only signal metadata, not raw chat or player identifiers. No automatic punishments or model-based classifier.
- Added lifecycle-managed optional `CoreAiAssistantModule` and `ModerationAiModule`; both start and stop independently and can be disabled without affecting the Core.
- Added bounded tool argument validation (maximum 32 keys, 64-character keys and 2,048-character values) and bounded moderation history (maximum per-player events plus configurable LRU cap on tracked players).
- Separated AI tool action failures from audit-sink failures so a completed state change is not incorrectly reported as an unexecuted/failed action.
- Added tests for permission denial, approval gates, audit records, tool argument limits, provider availability/context safety, module lifecycle, moderation thresholds, expiry, privacy and memory caps.
- Code commits: `0b01bda109031d871fd8cdc465776b6fbf014842`, `a444373b6fd23fc4ab2196696aa6fe5bbeaab2b9`, `b80c9c4337a4ae6b43bb2d9125db2b9790e272f4`, `9a76cce0d677cd4a03f863f93942bea86c59d9d4`, `08dbaf9eb445458606fff5fcf8b6b757ec80c5f8`, `611f840b51782289e379d2e890e26a7390d3defa`, `bf710a2a92a380c2561b2e643857aa8a88656f8f`, `bf2ed01581eb50882aad41e56110014ab97abf57`, `88aed5ccfdcd0e2a6ecbc193659b3670da40b7f9`, `7f1f5c65bf5b7f94883d4e32985dfe8bffc192d4`, `417b8031411302d6f9f8c15e0ee94750ff0c3954`, `3a7ce7d241369997cf0f9f77c8644b601669c5e9`, `5d4a9a98fb8fb5517f6cd75c79d8e27e3afa1f83`, `2ff6aa6a0dd55f916ccc520d4d5a2990a9b5fa13`, `c51f45f5da04b34d8004c0dc4e05ff4ede51759d`.
- Verification: tests are committed; no successful CI run has been observed for these commits. The available job summaries show completed failures with empty step lists and no downloadable logs. Do not claim tests pass.


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


### 2026-11-17 — cancellation after generation
- Added a deterministic regression test for cancelling a chunk after its worker has finished but before the game tick commits the result.
- The test verifies that cancellation resolves the async waiter with `false`, removes the queued completion without mutating the world, and allows a fresh request for the same chunk to generate and commit successfully.
- Test commit: `78eeb3257f26378abf30746310d922c2225ab0e7`.
- Verification: committed test only; CI has not yet provided a successful run, so execution remains unconfirmed.

### 2026-11-17 — serialize chunk completion and new requests
- Continued the audit of chunk request/delivery races.
- Found a narrow race on failed generation: a new network-side request could arrive after the old async waiter had been removed but before the generation service released its in-flight reservation. That request could attach to an already-finishing generation and never receive a completion.
- Serialized the game-tick drain operation on the pipeline monitor, matching the existing synchronization of request, cancel and stop operations. A new request can no longer interleave with result delivery and waiter resolution.
- Commit: `49b64cbebbea657c2a96abf5723ddc91e5f09e19`.
- Verification: source change committed; full tests still await a functioning GitHub Actions runner.

### 2026-11-17 — provider SPI, privacy boundary and obsolete client workflow removal
- Added `AiModelProvider` as an optional SPI with bounded prompt/output sizes and rejection of sensitive context keys. Wired it into the Core assistant and moderation summary facade; both assistants remain usable without a provider.
- Tightened context-key filtering to avoid rejecting unrelated names that merely contain the letters "ip".
- Preserved the existing passive mob wander goal when an explicit PASSIVE profile is assigned.
- Removed `.github/workflows/nexora-android.yml` from active CI. The historical `client-android/` source tree and RPG source are retained, not actively built or extended.
- Updated README, AGENTS.md, project context and AI roadmap to reflect the current standalone-server scope.
- Relevant commits: `7f1f5c65bf5b7f94883d4e32985dfe8bffc192d4`, `417b8031411302d6f9f8c15e0ee94750ff0c3954`, `2ff6aa6a0dd55f916ccc520d4d5a2990a9b5fa13`, `3a7ce7d241369997cf0f9f77c8644b601669c5e9`, `5d4a9a98fb8fb5517f6cd75c79d8e27e3afa1f83`, `c51f45f5da04b34d8004c0dc4e05ff4ede51759d`, `95b839026e9166679f7ae40b1409b27360bcb3fb`, `319cc25f52a6a5642621f83a045fc9c520afd6c9`, `1b13c9ad285a13b4c200d2a625c51f63c7465a64`, `e67a2ba2be77229d4c084aeb5b89e7ebb3a3fa05`, `60ec713118c620cd00ac6673e27778b7ff439acc`, `78b0695c6aabcec893fdccf2b8d1565b1585179c`, `9b871315e35d4d8bc00d3a8a5dfee07f6e210c68`, `8133bc38600be8789c1475521e388e5d42f2805d`, `1113de6318b77ad87bb984dfdb3f12765c5d573b`, `e012dfba6689f476af8c8a22693857617be6316b`, `f62bc586ba790245f0b9edacd7efe4b77bcd5171`, `1285de0eb31032ec98f290a0c097212cf8aa250d`, `e3e16a628c0400ee9f3cc813facf2de039cb3cf6`.
- Verification: the server workflow continues to fail within roughly one second; the latest observed job exposes no steps and no logs. A rerun was requested, but no successful build is confirmed.

## Next tasks — in order

1. **Restore a usable CI verification path** and fix any compile/test failures once the runner actually starts.
2. **World persistence hardening:** initial review and regression coverage are in place (atomic replacement, CRC32C, version/signature/size/count validation, corruption/truncation/trailing-byte tests). Before changing the file format, add a bounded-write safeguard and a documented recovery/backup policy.
3. **World lifecycle and multi-world configuration:** existing tests cover startup rollback, reuse of pre-registered worlds, restart persistence and retry after failed shutdown save; extend only where a concrete gap is found.
4. **Chunk lifecycle and pipeline correctness:** define NEXORA-owned states (generated, persisted, loaded, initialized/ready) and explicit transitions; then test cancellation, retry after generation/load failure, back-pressure, bounded memory/queues, duplicate requests and clean shutdown under load. Use Nukkit/PowerNukkitX only as a public behavior reference; no source/API copying.
5. **Entity and player simulation:** authoritative movement/collision boundaries, deterministic tick behavior and persistence.
6. **Dedicated server packaging:** verify clean install, documented config, startup, save/restart and shutdown using CI artifacts.
7. **Mob AI completion:** bounded pathfinding, target memory, combat/damage integration, profile persistence and per-tick work budgets.
8. **Core AI completion:** production provider adapter, diagnostics catalogue, conversation workflow and durable audit storage.
9. **Moderation AI completion:** report ingestion, case workflow, evidence retention policy, appeals and durable moderator audit.

## Definition of core-ready

The Core is ready only after automated tests cover clean installation, world creation, chunk generation/loading, block/world persistence across restart, entity/player simulation, bounded networking, AI isolation and clean shutdown. Record actual CI evidence and any remaining limitations here.

## Session hand-off template

- Completed: core persistence/config reliability tests; async chunk waiter and cancellation fixes; initial profile-based Mob AI; permissioned/audited AI tool boundary; privacy-conscious moderation signal foundation.
- Tests/CI: regression tests have been added, but no successful CI run is confirmed for the latest AI commits. The most recent earlier server workflow had a failed job with no steps/logs exposed; inspect current run details before diagnosing the failure.
- Not completed / blockers: CI verification; chunk lifecycle state machine; vertical mob navigation, combat integration and aggregate pathfinding budgets; model/provider adapters and complete assistant workflows; moderation case management.
- Next exact task: obtain a real server CI run, fix compile/test failures, then continue bounded chunk lifecycle and mob navigation work.
- Commit SHA:

### 2026-11-17 — extend the existing Mob AI with bounded navigation
- Re-read the active branch's entity simulation, MobAiController, EntityTickEngine, registry and regression tests before changing the mob architecture.
- Confirmed that MobAiController already runs from the authoritative EntityTickEngine tick. It does not own a separate clock, thread, entity registry or mob population. Kept that integration and did not add a second AI subsystem.
- Replaced straight-line-only pursuit with deterministic A* pathfinding on the loaded ground plane. Paths avoid solid/unsafe cells, are bounded to the configured detection radius, and expand no more than 512 nodes per search.
- Cached per-mob waypoints and rebuild routes when the target moves to another block or the current route becomes unusable. Waypoint advancement waits until the mob is near the center of the cell to reduce corner cutting.
- Added regression tests for routing around a one-block obstacle and reporting STUCK when all four adjacent ground cells are blocked. Existing passive wandering, neutral provocation, hostile perception and line-of-sight behavior remain in place.
- Commits: 13b3c74918b8f9f9bcec6f34f8dc24ef90ade544 (bounded pathfinding), 544bf3aec507a98f9de5b93dc43d558e4d2c02d4 (waypoint precision), 87645c8ad11db2a85703357d5bfeb488f6ded046 (route regression assertion).
- Verification: source and tests are committed, but Maven execution is not yet confirmed. Previous Actions jobs failed before reporting steps; do not claim the tests pass until a runner executes them.
- Next Mob AI tasks after the follow-up target-memory work: navigation across height changes, safe handling of liquids/hazards, combat integration, persistent profiles and aggregate per-tick pathfinding budgets.
### 2026-11-17 — hostile target memory and safe search behavior
- Extended the existing MobAiController with a bounded 100-server-tick memory of the last visible player position. If line of sight is broken, the mob can continue navigating toward the last known position rather than immediately forgetting the target.
- A remembered position is a search destination only: it never produces an attack intent. If the player despawns or the memory window expires, the mob clears its target/navigation state and returns to IDLE.
- Added regression coverage for continuing around a newly placed two-block wall without attacking an unseen target, and for forgetting the target after the memory window.
- Commit: e761ad85fb43dfa380ec4ccb3ea9ef1b3eceae9e.
- Verification remains blocked by GitHub Actions runner allocation: recent jobs finish with runner_id=0 and no steps. The new tests are committed but not yet executed.
### 2026-11-17 — aggregate Mob AI pathfinding budget
- Added a shared per-server-tick pathfinding budget to the existing MobAiController. Each new route search reserves a maximum of 512 node expansions, and the controller permits at most 4,096 reserved expansions (eight new searches) per server tick.
- Mobs whose route search cannot start within the current tick budget receive WAITING and retry on a later server tick; cached routes do not consume new-search budget. This bounds bursts of simultaneous path requests without adding a new thread or tick loop.
- Added a regression test with nine hostile mobs: eight can begin new routes in the first tick, the ninth is deferred, and the deferred mob gets a route on the next tick after cached paths stop consuming search budget.
- Commit: 4fd102078a5b4f0e8c60049f2693eeafc7d81ec6.
- Verification: committed but not executed because GitHub Actions still fails before allocating a runner (runner_id=0, empty step list). Load testing of the budget remains outstanding.
### 2026-11-17 — failed-route backoff and budget regression coverage
- Added a ten-tick retry backoff for unreachable paths so mobs trapped by permanent obstacles do not consume a new 512-node search reservation every tick. If a cached waypoint becomes blocked, the controller retries on the next server tick; a changed target block bypasses the old target's backoff.
- Added an explicit assertion that a remembered but unseen target never produces an attack intent.
- Commits: ae26b762b3f8204bbfe1092346949bab43551945 (route backoff), 4715d507445bd4f53d2e38813ec5cfd43d1c222b (memory safety assertion).
- The shared 4,096-reserved-node per-tick budget and nine-mob deferral test are in commit 4fd102078a5b4f0e8c60049f2693eeafc7d81ec6. Aggregate load tests remain outstanding.