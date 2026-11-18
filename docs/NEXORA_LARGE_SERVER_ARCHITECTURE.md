# NEXORA Large Server Architecture

## Goal

NEXORA is designed to scale by adding server processes instead of requiring one process to simulate every player in a large network.

A single server process owns a bounded gameplay/world workload. A cluster provides the large-online experience.

## Cluster model

Gateway
  -> Server A
  -> Server B
  -> Server C
  -> ...

A player has one authoritative gameplay server at a time. Cross-server movement is an explicit handoff, not shared mutable world state.

## Per-server protection

Each server uses:
- AdaptiveRuntime
- WorkGraph
- TaskContract
- adaptive lane budgets
- state ownership
- chunk lifecycle
- spatial entity indexing
- player lifecycle

The goal is graceful degradation under load: preserve authoritative player/gameplay work first and reduce/defer work that is explicitly safe to defer.

## Interest management

Visual and simulation work must be scoped to relevant observers.

A spell can produce:
1. authoritative gameplay result;
2. nearby status/effect state;
3. visual representation.

The authoritative result is not allowed to depend on particle delivery.

Particles, cosmetic effects and other observer-specific work should be generated only for players whose view/interest area requires them.

## Cross-server rules

Do not share mutable world objects directly between server processes.

Cross-server communication must use explicit messages/events:
- player transfer;
- party/group state;
- global events;
- marketplace/economy events;
- chat;
- persistence updates.

Ownership remains unambiguous.

## Scaling strategy

Scale based on measured:
- player count;
- per-player CPU cost;
- entity count;
- AI cost;
- active chunks;
- gameplay event rate;
- network output;
- persistence pressure.

Do not promise a fixed player count before benchmark evidence exists.

## Planned milestones

1. Interest/observer model.
2. Server-local player ownership.
3. Gateway protocol abstraction — FOUNDATION (`ServerNode`, `GatewayRouter`; transport adapter remains next).
4. Cross-server handoff state — PARTIAL FOUNDATION (`PlayerHandoff` immutable envelope and `HandoffCoordinator` PREPARED/CLAIMED/COMMITTED/ABORTED state machine; durable replicated ledger and transport authentication remain required).
5. Global event bus boundary.
6. Cluster health/load metrics.
7. Load-aware player routing — FOUNDATION (`GatewayRouter` filters by profile, health and `ServerLoadPolicy`).
8. Multi-process integration tests.
9. Large-online benchmark suite.
10. Failure/reconnect/handoff testing.
