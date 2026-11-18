# NEXORA Development Plan

> Master plan for the NEXORA core architecture. This file is the source of truth for the current sequence.

## Core principle
NEXORA is not another Minecraft core with renamed optimizations. The goal is a new execution architecture: adaptive workload control, explicit task dependencies, measured execution cost, and composable gameplay systems.
Nukkit is only a compatibility/reference point where needed. Its implementation is not copied into NEXORA.

## Completed

### 1. Measurement foundation
- Bounded HotPathProfiler.
- Average, p50, p95 and max over a bounded recent window.
- Initial mob targeting and AI decision instrumentation.

### 2. Adaptive Runtime foundation
- AdaptiveRuntime with PLAYER, WORLD, ENTITY, AI, MAGIC and BACKGROUND lanes.
- Protected execution budget and emergency reserve.
- Critical admission and non-critical deferral.
- Runtime pressure tracking and dynamic estimate adjustment.
- Connected to WorldTickPipeline.

### 3. Work Graph
- Dependency-aware WorkGraph.
- PENDING, READY, RUNNING, COMPLETED, DEFERRED and FAILED states.
- Cycle detection.
- Runtime-aware admission.
- Estimated and observed execution cost.

### 4. Task Contracts
- TaskContract for lane, criticality, deferrability, ordering sensitivity and estimated cost.

### 5. Gameplay composition
- GameplayGraph for composing RPG, Magic, Combat and Effects.
- Example: Class check -> Spell cast -> Damage -> Effect.

### 6. Gameplay Events
- GameplayEventBus with typed events and deterministic registration order.
- Class changes, spell casts, elemental reactions, damage, status effects and quest progress.
- Expensive consequences are intended to enter WorkGraph and AdaptiveRuntime.

## Current architecture
Gameplay systems -> Gameplay Events / Gameplay Graph -> Work Graph -> Task Contracts -> Adaptive Runtime -> measured execution -> profiler/history -> improved future estimates.

## Large-server architecture track
- Cluster-first design: scale by adding server processes rather than forcing one process to own all players.
- One authoritative server owns a player at a time.
- Cross-server movement uses explicit handoff state, never shared mutable world objects.
- Interest management scopes particles, visual effects and observer-specific work to relevant players.
- Cluster routing and capacity decisions are based on measured player/world/gameplay/network pressure.
- `ServerNode` and `GatewayRouter` provide profile-aware, health-aware, load-aware destination selection.
- `PlayerHandoff` defines an immutable cross-server transfer envelope; source freeze, destination claim, commit/rollback and transport are still required.
- `PlayerTransferCoordinator` and `FilePlayerTransferLedger` provide local durable CAS records, expiring claims and fencing tokens; `DurableHandoffCoordinator` bridges the handoff envelope to this ledger. Replication and live ownership transfer are not implemented.
- `ClusterTransport` defines the transport SPI and `AuthenticatedClusterMessageCodec` authenticates bounded node messages with HMAC-SHA-256. Replay protection, key rotation, peer authorization and a concrete network adapter remain next.
- Fixed online capacity claims are prohibited until benchmark evidence exists.

## Next development sequence

### 7. Contract-aware Work Graph
Connect every Work Graph node to its full TaskContract instead of duplicating only lane/criticality/estimate fields.

### 8. Unified execution observation — DONE
`TaskExecution` measures actual execution, feeds observations into WorkGraph and HotPathProfiler, and records failures.

### 9. Gameplay event-to-work bridge — DONE
`GameplayWorkBridge` converts events into explicit Work Graph items without coupling gameplay modules to the scheduler.

### 10. Safe execution ownership — FOUNDATION DONE
`StateOwnership` and scoped `OwnershipGuard` establish explicit PLAYER/WORLD/ENTITY/GAMEPLAY/PERSISTENCE ownership. `WorldState` and `EntityState` now require explicit ownership for mutation. Parallel world mutation remains disabled until lifecycle and contention tests are complete.

### 11. Adaptive multi-lane budgeting — INTEGRATED
`AdaptiveBudget` now feeds live `AdaptiveRuntime` admission decisions. `RuntimeSlice` provides a bounded admission boundary for each execution slice; background work is reduced first under pressure.

### 12. RPG foundation
Build generic APIs for classes, stats, abilities, resources and progression on top of Gameplay Graph.

### 13. Magic / Combat integration
Build spells, elements, damage, effects, resistances and reactions as composable gameplay modules.

### 14. Plugin/module API
Expose stable NEXORA APIs for gameplay actions, events, contracts and graph tasks.

### 15. World/chunk architecture — IN PROGRESS
`WorldState`, `ChunkState`, `EntityState`, `EntityRegistry` and `SpatialEntityIndex` establish explicit world/entity boundaries and chunk-local entity lookup. `ChunkWorkScheduler` routes chunk load/unload through WorkGraph. Full chunk storage and persistence remain next.

### 16. Persistence and lifecycle — STARTED
`PlayerLifecycle` now defines CONNECTING → ACTIVE → DISCONNECTING → DISCONNECTED independently from network transport. Save/load and recovery remain next.

### 17. Player persistence and transfer recovery — FOUNDATION STARTED
- Separate serialized player snapshots from live runtime/player objects.
- Enforce monotonic revisions, idempotent repeat saves and conflict detection.
- Key snapshots by the Bedrock XUID string (not a lossy numeric conversion); the file format is versioned and retains a reader for the earlier numeric-ID format.
- Keep the storage contract backend-neutral. `InMemoryPlayerStateStore` is test-only; `FilePlayerStateStore` now provides a local durable adapter with bounded files, checksum validation, per-player locks and atomic replacement where supported.
- The canonical snapshot service is wired into Bedrock disconnect/aggregate saves as a shadow checkpoint; legacy files remain authoritative when any component exists, with canonical fallback only when all legacy components are absent.
- A CAS transfer ledger now has a durable local-file adapter, versioned records, leases and monotonically increasing fencing tokens. It is not yet replicated or wired into live handoff.
- Next: add process-restart/login-fallback integration tests, then integrate ledger operations into source freeze/destination claim/commit and add authenticated inter-node transport. Exactly-once delivery remains unclaimed.

### 18. Benchmark laboratory
Create repeatable workloads and compare NEXORA against reference server implementations using identical scenarios. No superiority claims before measurements.

### 19. Hardening
Stress tests, failure tests, cycle tests, starvation tests, load spikes, long-running stability and compatibility testing.

## Rules to prevent architectural drift
- Do not copy Nukkit implementation into NEXORA.
- Do not add a feature merely because another core has it.
- Prefer new architecture over renamed existing mechanisms.
- Gameplay modules must not become tightly coupled to the runtime.
- Runtime must not own gameplay state.
- Never introduce parallel world mutation before ownership is proven safe.
- Measure performance before claiming an optimization is better.
- Update this document after every major architectural milestone.

## Player-state persistence integration (current stage)

- PlayerStateCheckpointService is wired into the live BedrockSessionManager by NexoraServer.
- Graceful player disconnect and the manager aggregate save path first persist the existing profile, inventory/crafting UI, and equipment files, then write one canonical player-state checkpoint.
- An unchanged aggregate is idempotent: it returns ALREADY_CURRENT and does not advance the durable revision.
- The canonical file store is local to one server process and is not a distributed database.
- Existing legacy files remain the login authority. Canonical recovery must not overwrite partially present legacy state until migration can prove completeness and freshness.
- Next: add conservative recovery for players with no legacy component files, test login/disconnect/restart, then introduce an explicit migration marker and only later switch canonical snapshots to primary authority.


### 20. Live player-transfer lifecycle — FOUNDATION ADDED
- `PlayerTransferLifecycle` now sequences source freeze + canonical checkpoint, durable PREPARED publication, destination lease/fence claim, destination restore/activation, and durable COMMITTED transition.
- Failed activation deactivates the destination before attempting ABORTED; source gameplay resumes only after the durable ledger confirms ABORTED.
- An uncertain commit never resumes the source. The destination is asked to deactivate only if it still owns the same fencing token; recovery must reconcile the durable ledger.
- Participant hooks are explicit interfaces. The Bedrock session manager is not yet wired to these hooks, and the current file ledger is still single-host only. This is a lifecycle protocol foundation, not crash-safe cross-host consensus or exactly-once delivery.
- Next: wire session freeze/restore hooks behind a feature gate, then add multi-process fault-injection tests and a replicated ledger/transport before enabling cross-host handoff.


### 21. Concrete cluster transport — FOUNDATION ADDED
- `TcpClusterTransport` implements bounded TCP message exchange with configured peer addresses and allowlists, frame limits, bounded queues, socket timeouts and receiver ACKs.
- HMAC envelope verification and recipient validation are enforced before delivery.
- `ClusterReplayGuard` rejects duplicate IDs during a bounded in-memory retention window and fails closed at capacity. It does not survive process restart.
- Loopback transport tests cover message delivery, ACK and duplicate rejection.
- Next: durable replay/idempotency, key rotation, TLS or private-network confidentiality, process restart/failure injection, and a shared replicated transfer ledger. The transport remains opt-in and is not wired into server startup.


### 22. Cluster replay persistence and key rotation — FOUNDATION ADDED
- `ClusterReplayFilter` decouples transport replay checks from storage.
- `FileClusterReplayFilter` persists bounded replay markers with checksum validation and cross-process file locking; corruption and capacity exhaustion fail closed.
- `RotatingClusterMessageCodec` signs with the active HMAC key and accepts up to four configured active/previous keys for staged rotation.
- Tests cover restart-style replay rejection, expiry, capacity, corruption, symlink rejection, and key rotation.
- Limitation: replay persistence is local to one host; no shared cluster-wide deduplication or automated secret distribution/rotation exists.


### 23. Pairwise peer authentication — FOUNDATION ADDED
- `PeerClusterMessageCodec` binds source identity to a configured pairwise key and selects the outbound key by target node.
- Pairwise codecs can wrap the rotating key-ring codec for controlled per-peer key rotation.
- Tests verify source identity binding and reject source spoofing / unknown targets.
- Key provisioning remains external; no secrets manager or TLS certificate lifecycle is included.


### 24. Shared SQL transfer ledger — FOUNDATION ADDED
- Added `JdbcPlayerTransferLedger`, a JDBC/DataSource adapter for the existing versioned CAS ledger contract.
- Transfer state, checkpoint revision, lease expiry, fencing token and record version are persisted in a shared SQL table.
- State transitions use one conditional UPDATE on transfer ID + expected record version, allowing competing nodes to race safely against the same transactional database.
- Schema initialization is explicit; deployment tooling must run it and provide the JDBC driver, shared database, credentials and tested isolation/failover settings.
- The adapter is not wired into startup and does not create replication or end-to-end exactly-once semantics.
- Next: validate against supported real database engines in integration CI, then add a deployment-owned DataSource configuration. Live Bedrock source/destination participant hooks and process-level crash recovery remain gated.


### 25. Live Bedrock source participant — INTEGRATED
- Added canonical checkpoint revision lookup to `PlayerStateCheckpointService`.
- `BedrockSessionManager.freezePlayerForTransfer(XUID)` serializes against inbound combat/gameplay with the session lock, verifies one authenticated source session, checkpoints the complete canonical player bundle, and returns its durable revision.
- Frozen sessions reject inbound frames, leave authenticated/initialized gameplay counts and visibility, and are not modified by periodic save, disconnect cleanup, or shutdown crafting cleanup.
- Remote player-list/actor visibility is removed while the source is frozen.
- `unfreezePlayerAfterTransferAbort(XUID)` restores the existing source session and re-advertises it only when called after a confirmed durable ABORTED transition.
- Added `BedrockPlayerTransferSource` adapter with explicit source-node validation and caller-supplied authenticated XUID resolution.
- Added a session-level freeze/checkpoint/abort-resume regression test.
- Still gated: destination session reconstruction, exact-revision validation, fencing enforcement at every ownership mutation, authenticated network handoff, and crash-recovery reconciliation. No cross-host transfer is enabled.


### 26. Exact-revision destination restore and runtime fencing — FOUNDATION ADDED
- Added `loadAtRevision(XUID, revision)` to canonical checkpoint storage. It rejects revision mismatches and verifies schema, checksum and XUID before decoding.
- Added `PlayerTransferFence`, which re-reads the durable ledger and rejects expired, aborted, or superseded claims. The same token remains valid after COMMITTED.
- Added `CheckpointedPlayerTransferDestination`: validates target/source identity, active lease and fence, restores only the exact revision, and delegates session-specific activation with the runtime fence.
- Rollback deactivation is limited to the same active claim; committed destinations and newer fencing tokens are never torn down by stale rollback.
- Added tests for exact-revision restore, commit behavior, revision mismatch and stale-token reclaim.
- Remaining: authenticated client redirect/reconnect and inter-node RPC; add process-level crash/restart tests and audit every authoritative mutation path. The concrete pending-login activator is now tracked in section 32. No cross-host transfer is enabled.


### 27. JDBC ledger concurrency and restart tests — ADDED
- Added H2 test-scope dependency and integration tests for durable state visibility across independent DataSources, duplicate IDs, versioned CAS, and commit visibility.
- Two concurrent transfer coordinators race for one PREPARED record; the test asserts only one fencing token wins.
- JDBC CAS now keeps player XUID, source node, target node and checkpoint revision immutable across state transitions.
- H2 verifies JDBC behavior only; production database-specific integration/failover/isolation testing remains required.


### 28. Monotonic transfer state enforcement across ledger adapters — COMPLETE
- Centralized legal transfer successor rules in `PlayerTransferRecord`.
- In-memory, local-file and JDBC ledgers now all reject state regression, premature reclaim, same/lower fencing tokens, terminal-state reopening, and mutation of XUID/node/checkpoint identity.
- H2 integration tests cover JDBC persistence, concurrent claims, stale CAS, immutable identity and no PREPARED regression.
- CI validation: 750 tests passed, zero failures on commit `73d7212`.


### 29. JDBC lease reclaim after reconnect — VERIFIED
- Added an H2-backed restart/reconnect scenario: a node's claim expires, a second DataSource reclaims it with a higher fencing token, the old token cannot commit, and the new token can.
- CI: 751 tests passed, zero failures on commit `e074e3f`.


### 30. Destination transfer recovery rehydration — FOUNDATION ADDED
- Added `PlayerTransferRecoveryService` to rebuild destination-side pending Bedrock login restores from the durable transfer ledger after process restart.
- Only current, unexpired CLAIMED leases and COMMITTED ownership records are eligible; expired claims are skipped and cannot restore stale player ownership.
- Recovery is bounded (1..10000 records), idempotent for an already-registered matching transfer/fence, and fails closed on conflicting pending ownership.
- Recovery queries now prioritize newest records first across in-memory, file and JDBC ledger adapters so historical transfers for one XUID do not mask the latest ownership record.
- Added tests for claimed/committed rehydration, expired-lease rejection and repeat recovery.
- Remaining: wire recovery service into configured server startup and a deployment-owned shared ledger; add process-restart Bedrock login tests and multi-process failure injection. This does not enable cross-host transfer by itself.

### 31. Startup recovery wiring — IMPLEMENTED, CI VERIFIED
- `NexoraServer` now opens the durable local transfer ledger under `data/cluster/transfers` and runs bounded destination recovery before starting the network runtime.
- `NEXORA_NODE_ID` selects the destination identity; the development fallback is `standalone`.
- `BedrockSessionManager` exposes a startup recovery entry point that rehydrates its private pending-login registry.
- Startup logs the scanned, registered, already-pending, expired and superseded record counts.
- Recovery errors fail startup closed rather than accepting logins with a partially reconstructed ownership registry.
- The configured file ledger is explicitly single-host/local-filesystem only. Do not use it for a multi-host cluster; shared JDBC storage and an operator-managed unique node ID are required for that deployment.
- Remaining: complete live transfer orchestration (claim/commit/abort across source and destination), validate recovery with process-kill integration tests, and prove shared-store semantics across separate hosts.

### 32. Bedrock destination login reservation — IMPLEMENTED, TRANSPORT STILL GATED
- Added `BedrockPendingTransferActivator`, the concrete bridge from the exact-revision destination adapter into the live Bedrock login pipeline.
- The adapter verifies transfer ID, target node and checkpoint XUID against the active fencing token, then registers the exact XUID/revision for the next authenticated login.
- The destination lifecycle can now durably commit ownership after this reservation is installed; the login path revalidates the ledger fence and loads only the claimed checkpoint revision before authenticating the player.
- Rollback removes only the matching transfer/fencing-token reservation and rejects a matching session if login already consumed the reservation.
- Added a regression test for reservation and matching-token cancellation.
- Important boundary: this is a destination-side login reservation, not client redirection or an authenticated source-to-destination RPC. The current server startup still uses the single-host file ledger, so cross-host gameplay transfer remains disabled.
- Next: wire an authenticated cluster RPC for prepare/accept/abort, use the shared JDBC ledger on every node, add source/destination process-kill tests, and validate real Bedrock reconnect behavior before enabling the feature.

### 33. Shared JDBC transfer-ledger startup configuration — IMPLEMENTED, CI VERIFIED
- Added a standard-JDBC `DriverManagerDataSource` and `PlayerTransferLedgerFactory`.
- Startup now selects the local checksummed file ledger when `NEXORA_TRANSFER_JDBC_URL` is absent, or the shared SQL ledger when it is configured.
- Optional environment variables: `NEXORA_TRANSFER_JDBC_USER`, `NEXORA_TRANSFER_JDBC_PASSWORD`, and `NEXORA_TRANSFER_JDBC_INITIALIZE_SCHEMA=true|false`. Credentials must be supplied as a pair; schema creation remains opt-in so production migrations can be deployment-managed.
- Shared JDBC mode requires an explicit `NEXORA_NODE_ID`; the unsafe shared fallback identity `standalone` is not accepted.
- Startup output deliberately hides JDBC connection details and credentials.
- The database's vendor JDBC driver must be supplied on the runtime classpath. H2 remains test-only; no production database vendor is selected or bundled.
- Added tests for shared JDBC initialization/round-trip, local fallback, invalid credentials and schema flags.
- CI: 770 tests passed, zero failures on commit `2b0b604`.
- Boundary: configuring a shared ledger makes the ownership metadata shared, but does not itself enable cross-host gameplay transfer. Authenticated RPC, client redirect/reconnect and process-failure validation remain mandatory gates.

### 34. Shared JDBC canonical player checkpoints — IMPLEMENTED, CI VERIFIED
- Added `JdbcPlayerStateStore` with per-XUID revision compare-and-set, bounded Base64 payload storage, checksum verification, idempotent same-revision writes, stale-write rejection and bounded contention retries.
- Added two-independent-DataSource and concurrent-writer tests to verify a newer snapshot cannot be overwritten by an older node.
- Added `PlayerStateStoreFactory` and startup configuration via `NEXORA_PLAYER_STATE_JDBC_URL`, optional `NEXORA_PLAYER_STATE_JDBC_USER`, `NEXORA_PLAYER_STATE_JDBC_PASSWORD`, and opt-in `NEXORA_PLAYER_STATE_JDBC_INITIALIZE_SCHEMA=true|false`.
- Startup now refuses shared-ledger mode unless canonical player checkpoints are also configured as shared. This prevents a destination node from claiming a transfer while its required inventory/equipment/position checkpoint exists only on the source host's local disk.
- The player-state store and transfer ledger can use the same SQL database but occupy separate tables. The checkpoint schema initializer currently supports H2, PostgreSQL and MySQL/MariaDB payload column types; other vendors fail closed and need an explicit dialect implementation. The JDBC driver must be supplied on the runtime classpath; schema creation is opt-in.
- Remaining: validate schema behavior against the production database vendor, add multi-process kill/restart tests, and finish authenticated cluster RPC plus Bedrock client redirect/reconnect. Shared metadata and checkpoints are necessary but not sufficient to enable live cross-host handoff.

### 35. Bedrock source redirect gate — IMPLEMENTED, CI VERIFIED
- Added protocol-2225 TransferPacket (0x55) encoding for destination host, little-endian uint16 port, reload-world flag and absent optional gatherings metadata.
- Added a source-session redirect method that only accepts a matching durable COMMITTED record, transfer ID, fencing token, XUID and local source-node identity.
- Redirect is allowed only for the already-frozen source session, is sent over the reliable channel, and is one-shot. A failed/uncommitted ownership check sends no packet.
- Added packet encoding/validation tests and a session test proving the redirect cannot be sent before COMMITTED.
- CI: 773 tests passed, zero failures on commit `7683fe5`.
- Protocol reference: Mojang Bedrock protocol docs, TransferPacket: https://mojang.github.io/bedrock-protocol-docs/1.26.50/packets/transfer-packet/
- Remaining: wire this gate into authenticated inter-node prepare/accept/abort RPC and node routing; test a real Bedrock client reconnect on the destination. The packet encoder and guarded session method alone do not enable live cross-host migration.

### 36. Authenticated source/destination transfer orchestration — IMPLEMENTED, CI VERIFIED
- Added `PlayerTransferClusterEndpoint` on the destination: it accepts only authenticated cluster messages whose source/target match the durable PREPARED record, claims with a fencing token, reserves the exact shared checkpoint through the Bedrock login adapter, and commits ownership only after reservation succeeds.
- Added `PlayerTransferSourceCoordinator`: freezes/checkpoints the source, writes PREPARED, sends the target request through `ClusterTransport`, reconciles the durable outcome, and invokes the guarded Bedrock redirect only after COMMITTED is confirmed.
- Added a loopback TCP integration test using HMAC-authenticated `TcpClusterTransport` instances for source and destination. The test verifies checkpoint reservation, durable commit and TransferPacket emission.
- The source coordinator fails closed on uncertain outcomes: it never resumes a source session unless the shared ledger proves ABORTED.
- CI: 783 tests passed, zero failures on commit `62b6bfc`.
- Added strict `ClusterTransportFactory` configuration with per-peer HMAC keys, peer allowlists, replay persistence, bounded worker queues and Bedrock route mappings. The transport is now started/stopped by `NetworkRuntimeModule` before public listeners.
- `NexoraServer` now wires the destination endpoint and exposes operator-console `players` and `transfer <xuid> <target-node>` commands; transfer requests use the configured Bedrock route and source coordinator.
- Remaining: validate real Bedrock client reconnect, process-kill races and production database failover before enabling this outside a controlled test cluster.

### 37. Transfer cancellation and remote reservation cleanup — IMPLEMENTED, CI VERIFIED
- Added CAS-safe PREPARED cancellation. If a destination claim races cancellation, exactly one state transition wins; a stale source cannot abort the newer owner.
- A current claim may be aborted even after lease expiry, but a superseded fencing token still cannot abort or commit.
- Added authenticated `PLAYER_TRANSFER_ABORT` messages so source-side cancellation also clears the matching destination pending-login reservation. A committed transfer is never deactivated by a stale abort.
- Destination cleanup now removes a matching reservation even when its claim lease has expired or the ledger has already recorded ABORTED, while preserving newer tokens and committed ownership.
- Added tests for expired-claim cleanup, prepared cancellation, claim/abort races and remote reservation cleanup.
- CI: the same 783-test build passed with zero failures.

### 38. Duplicate Bedrock XUID admission guard — IMPLEMENTED LOCALLY, CI VERIFIED
- Bedrock Login processing now serializes authentication by XUID within a server process and rejects a second active or transfer-frozen session for the same XUID.
- A stale duplicate can no longer create two independently active local sessions while one session is already transferring.
- Added a regression test for duplicate active XUID rejection.
- CI: 790 tests passed, zero failures on commit `b834401`.
- Remaining cluster-safety requirement: a durable shared player-owner lease/registry must gate admission across different server processes. The current transfer fencing protects a transfer's destination after claim/commit, but it is not yet a global online-session lease for arbitrary duplicate logins on different nodes. Until that is implemented and process-kill tested, keep cluster handoff in a controlled test environment.
### 39. Shared transfer-decision lookup and node-side ownership fencing — IMPLEMENTED, CI VERIFIED
- Added latest-per-XUID transfer lookup to in-memory, file and JDBC ledgers; recovery ordering now prefers the greatest canonical checkpoint revision before timestamps.
- Added a common ownership policy: PREPARED blocks admission, CLAIMED blocks gameplay until COMMITTED, COMMITTED permits only the destination, and ABORTED restores the source.
- Shared-ledger mode now configures this policy in BedrockSessionManager before the network starts. Login checks the latest durable transfer decision; active sessions periodically re-check it and are stopped when ownership moves to another node.
- After a committed/aborted transfer, reconnects restore the latest shared canonical checkpoint instead of potentially stale per-host legacy inventory/profile files. A claimed-but-uncommitted transfer cannot admit a player.
- Expired claims are durably aborted during destination startup recovery and on login reconciliation; stale PREPARED records on their source node are cancelled after a 30-second grace period.
- Transfer freeze now forces a fresh monotonic canonical checkpoint revision even when the serialized player state is unchanged, and the exact revision returned by that write is stored in PREPARED. This avoids a separate latest-revision read racing with another checkpoint.
- Added tests for ownership policy, latest per-XUID lookup, and expired-claim durable rollback.
- CI: 808 tests passed, zero failures on the latest validated shared-lease build `ab4fca7`.
- Remaining: this is transfer-history fencing, not a complete online-player lease registry. A duplicate XUID that logs into two different nodes before either node has a transfer record can still be admitted. Implement a shared lease/owner registry and validate it under multi-process login races before public cluster use.
### 40. Frozen source session reconciliation — IMPLEMENTED, CI VERIFIED
- Added a source-side reconciliation API that scans frozen Bedrock sessions and queries the latest durable per-XUID transfer decision.
- Added recovery for the narrow crash/failure window where the source froze and checkpointed but PREPARED was never published: the session resumes only if the shared ledger confirms no transfer record and the same node can still renew/acquire the exclusive online lease.
- Duplicate/failed transfer attempts now consult the durable per-XUID decision before any automatic source resume; an existing PREPARED, CLAIMED, or terminal decision keeps the source frozen for normal reconciliation.
- A frozen source session is resumed for a durable ABORTED record; it is redirected only for a matching durable COMMITTED record and configured Bedrock route. PREPARED/CLAIMED remain frozen. If no transfer record exists, the source may resume only after it proves it can still acquire/renew its exclusive online lease.
- Added the operator-console command `transfer recover` so an operator can reconcile source sessions after a peer or database restart without guessing whether ownership committed.
- CI: 798 tests passed, zero failures on commit `35e90f7`.
### 41. Shared online XUID lease registry — IMPLEMENTED, CI VERIFIED
- Added `PlayerOnlineLeaseRegistry` with in-memory and JDBC implementations. The JDBC table `nexora_player_online_leases` uses XUID as its primary key and atomic conditional UPDATE/INSERT acquisition so only one node can own an unexpired player lease.
- JDBC lease acquisition/renewal now uses the shared database `CURRENT_TIMESTAMP`, not the individual node clock, avoiding early lease theft caused by clock skew between nodes. Transfer-record timestamps still require synchronized node clocks.
- Shared-ledger bootstrap now initializes the online-lease table when the existing transfer JDBC schema-initialization flag is enabled, and configures the Bedrock session manager with the shared registry.
- Shared startup now validates transfer, online-lease and canonical checkpoint schemas before opening the Bedrock listener, including deployment-managed migrations with initialization disabled.
- Authenticated login acquires a random per-session lease before restoring player state. Active inbound sessions renew every third of `NEXORA_ONLINE_PLAYER_LEASE_MS` (default 15 seconds); failed renewal is fail-closed. Disconnect and shutdown release the exact node/session lease.
- In shared-ledger mode, the shared canonical checkpoint is now preferred for every login, even when no prior transfer record exists. Host-local legacy player/inventory files are only fallback migration sources when no canonical snapshot has yet been created.
- If a clustered player has no canonical snapshot yet, the first successful Bedrock initialization writes the complete restored aggregate to shared storage before gameplay admission. This makes legacy-file migration durable before the player can modify state.
- Source transfer freeze releases the source online lease; a confirmed abort reacquires it before gameplay resumes. PREPARED/CLAIMED/COMMITTED transfer history continues to fence logins during the handoff gap.
- A session that loses its durable transfer fence or global online lease immediately stops gameplay and releases its matching online lease; destination rollback also releases any lease held by a partially activated session.
- Added in-memory expiry/fencing tests, JDBC multi-node exclusivity tests, and a concurrent two-node first-login race test.
- CI: 808 tests passed, zero failures on commit `ab4fca7`; GitHub Actions build and GitLab publish both succeeded.
- Remaining validation: process-kill/partition tests with two actual server processes and a production database; the lease uses bounded expiry and is not a consensus protocol for arbitrary clock skew. Nodes must have synchronized clocks and must not continue gameplay when renewal fails.
### 42. Shared ownership restart integration coverage — IMPLEMENTED, CI VERIFIED
- Added combined JDBC restart tests using independent DataSource/ledger/lease/checkpoint-store instances to model source and destination processes reconnecting to the same durable database.
- Added source-coordinator tests proving a failed freeze resumes only when no durable per-XUID transfer decision exists; duplicate attempts cannot unfreeze an already-owned transfer.
- Verified a COMMITTED transfer continues to reject the former source after restart, the destination restores the exact canonical checkpoint revision, the destination lease remains exclusive, and an expired process lease cannot be resurrected by the old session token.
- Verified an ABORTED transfer restores source-node login authority and rejects the destination after restart.
- CI: 808 tests passed, zero failures on commit `ab4fca7`.
- Remaining: run the same scenarios with two actual server JVMs, a production SQL engine, network partitions, and abrupt process termination at each handoff boundary.