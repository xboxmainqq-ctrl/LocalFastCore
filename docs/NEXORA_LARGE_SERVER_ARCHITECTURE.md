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


## Player checkpoint and recovery boundary

Player state is persisted as an immutable, schema-versioned snapshot rather than
a live player/runtime object. Each snapshot carries a player ID, monotonically
increasing revision, capture timestamp, payload and SHA-256 checksum.

The storage contract requires atomic per-player saves:
- a newer revision may replace an older revision;
- an older revision is rejected as stale;
- repeating the same revision and payload is idempotent;
- a different payload for an already-used revision is a conflict.

The `InMemoryPlayerStateStore` is only a concurrency-safe reference adapter
for tests and loses data on process exit. `FilePlayerStateStore` now provides a
local durable adapter using bounded versioned files, SHA-256 verification,
per-player locking, forced temporary-file writes and atomic replacement where
the filesystem supports it. It is still a single-host store, not a replicated
cluster database.

The snapshot store is not yet connected to the live Bedrock session serialization
path, and a persistent transfer ledger is still required before player handoff
can recover safely across process crashes. The source must not
release authoritative ownership until the destination has durably claimed and
validated the transfer. Exactly-once behavior must not be claimed without the
transactional/replicated guarantees that make it possible.


### Player snapshot identity compatibility
The snapshot contract uses the authenticated Bedrock XUID as an opaque, validated string. The local file format writes that identity directly and reads the earlier numeric-ID format for compatibility. This removes the need to coerce XUIDs into signed 64-bit integers. Snapshot payload composition and integration with the live profile/inventory/equipment save path remain pending.


### Canonical player-state bundle
NEXORA has an immutable `PlayerStateBundle`, a bounded explicit binary `PlayerStateBundleCodec`, and a `PlayerStateCheckpointService` that advances revisions from the durable latest snapshot and restores the aggregate. The bundle covers profile/location/vitals/experience, player inventory and selected hotbar slot, crafting UI slots, armor, offhand, cursor, and magical equipment. It contains detached immutable snapshots rather than live session objects. The codec and checkpoint coordinator have round-trip/durable-store tests. `NexoraServer` now wires the checkpoint service into `BedrockSessionManager`; after the legacy profile, inventory, and equipment stores are saved, the manager writes a canonical shadow checkpoint. Legacy stores deliberately remain the login authority: canonical snapshots are not yet used to overwrite partially present legacy state. This is a safe dual-write stage, not completed migration or distributed replication.
