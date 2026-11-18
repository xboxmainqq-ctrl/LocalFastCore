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
