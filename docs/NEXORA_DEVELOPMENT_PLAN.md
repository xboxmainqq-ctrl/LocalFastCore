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

## Next development sequence

### 7. Contract-aware Work Graph
Connect every Work Graph node to its full TaskContract instead of duplicating only lane/criticality/estimate fields.

### 8. Unified execution observation — DONE
`TaskExecution` measures actual execution, feeds observations into WorkGraph and HotPathProfiler, and records failures.

### 9. Gameplay event-to-work bridge — DONE
`GameplayWorkBridge` converts events into explicit Work Graph items without coupling gameplay modules to the scheduler.

### 10. Safe execution ownership — IN PROGRESS
`StateOwnership` establishes explicit PLAYER/WORLD/ENTITY/GAMEPLAY/PERSISTENCE ownership. Parallel world mutation remains disabled until stronger lifecycle and contention tests exist.

### 11. Adaptive multi-lane budgeting
React to player density, world activity, entity density, AI pressure and gameplay workload while preserving critical work.

### 12. RPG foundation
Build generic APIs for classes, stats, abilities, resources and progression on top of Gameplay Graph.

### 13. Magic / Combat integration
Build spells, elements, damage, effects, resistances and reactions as composable gameplay modules.

### 14. Plugin/module API
Expose stable NEXORA APIs for gameplay actions, events, contracts and graph tasks.

### 15. World/chunk architecture
Continue the separate NEXORA world/chunk/entity architecture and connect it to Adaptive Runtime after ownership rules are explicit.

### 16. Persistence and lifecycle
Design save/load, shutdown, recovery and background persistence as explicit runtime workloads.

### 17. Benchmark laboratory
Create repeatable workloads and compare NEXORA against reference server implementations using identical scenarios. No superiority claims before measurements.

### 18. Hardening
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