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
- Keep the storage contract backend-neutral; the current in-memory adapter is explicitly non-durable.
- Next: connect snapshots to the live player serialization boundary, add a durable transfer ledger, source freeze/destination claim protocol, and crash/restart recovery tests.

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