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

## Authenticated node messaging

`ClusterTransport` defines the node transport SPI. `AuthenticatedClusterMessageCodec` provides bounded HMAC-SHA-256 message envelopes with recipient and clock-skew validation. This is a protocol foundation only: replay deduplication, key rotation, peer authorization, a concrete network adapter, and replicated ledger semantics remain required before production use.

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
3. Gateway protocol abstraction — FOUNDATION (`ServerNode`, `GatewayRouter`, `ClusterTransport` SPI and authenticated envelope codec; concrete transport adapter remains next).
4. Cross-server handoff state — PARTIAL FOUNDATION (`PlayerHandoff` envelope, in-process `HandoffCoordinator`, and a CAS-based `PlayerTransferCoordinator` with lease expiry and fencing tokens, a `DurableHandoffCoordinator` facade, and `FilePlayerTransferLedger` durable on one host only. The new ledger is not yet wired into live handoff, replicated, or backed by an authenticated inter-node transport).
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
NEXORA has an immutable `PlayerStateBundle`, a bounded explicit binary `PlayerStateBundleCodec`, and a `PlayerStateCheckpointService` that advances revisions from the durable latest snapshot and restores the aggregate. The bundle covers profile/location/vitals/experience, player inventory and selected hotbar slot, crafting UI slots, armor, offhand, cursor, and magical equipment. It contains detached immutable snapshots rather than live session objects. The codec and checkpoint coordinator have round-trip/durable-store tests. `NexoraServer` now wires the checkpoint service into `BedrockSessionManager`; after the legacy profile, inventory, and equipment stores are saved, the manager writes a canonical shadow checkpoint. Legacy stores remain the login authority whenever any legacy component is present. A canonical snapshot is used as a recovery fallback only when profile, inventory, and equipment legacy components are all absent; it never overwrites partially present legacy state. This is a conservative recovery bridge, not completed migration or distributed replication.


### Live transfer lifecycle protocol — partial foundation

The `PlayerTransferLifecycle` coordinator sequences the participant boundary:

1. Freeze the source and checkpoint the canonical player aggregate.
2. Publish a durable PREPARED record containing the checkpoint revision.
3. Claim the transfer on the destination with a lease and monotonically increasing fencing token.
4. Restore and activate the destination while enforcing that fence on ownership-sensitive writes.
5. Commit the ledger only after destination activation succeeds.

On failed activation, the destination is deactivated before the ledger is aborted. The source resumes only after ABORTED is durably confirmed. If commit is uncertain, the source remains frozen; the destination is asked to deactivate only while it still owns the same fence. This intentionally favors temporary unavailability over two authoritative owners.

The coordinator currently uses participant interfaces and is not yet wired into the live Bedrock session lifecycle. The local file ledger is not a shared multi-host consensus store. Cross-host handoff must remain disabled until session hooks, replicated ledger semantics, authenticated transport, and process-level fault-injection tests are complete.


### Concrete node transport — foundation added

`TcpClusterTransport` now provides a bounded TCP adapter for the cluster transport SPI: configured node addresses, explicit peer allowlists, bounded send/receive worker queues, frame-size limits, socket timeouts, HMAC envelope validation, recipient checks, and an application ACK after receiver acceptance. `ClusterReplayGuard` rejects duplicate message IDs within a bounded in-memory retention window and fails closed when its capacity is exhausted.

This is not a production cluster deployment by itself. Replay state is process-local and is lost on restart; messages use one TCP connection per send; HMAC does not encrypt traffic. Use private network isolation or TLS for confidentiality, a durable replay ledger, key rotation, and a replicated transfer ledger before enabling cross-host player handoff. The adapter is intentionally not wired into server startup yet.


### Replay persistence and key rotation — foundation added

The transport now accepts a pluggable `ClusterReplayFilter`. `FileClusterReplayFilter` stores message-ID expiry markers with checksums and an OS file lock, so duplicate filtering survives process restarts on one host. It rejects corrupt markers and fails closed at capacity. `RotatingClusterMessageCodec` signs outbound messages with an active key and can accept a bounded set of previous keys during a rotation grace period.

The file replay filter is still single-host; it is not a replicated global deduplication service. Operators must coordinate key-ring rollout across nodes and retire old keys only after the grace window. Production multi-host use still requires shared durable replay/idempotency and replicated transfer ledger semantics.


### Pairwise peer authentication — foundation added

`PeerClusterMessageCodec` binds each authenticated source node to its own configured pairwise codec/key and signs outbound messages with the key for the destination peer. This prevents a node holding only its own pairwise key from simply claiming another configured source ID. It composes with `RotatingClusterMessageCodec` for staged per-peer key rotation. The TCP adapter accepts the codec abstraction, so this can be used without changing the transport.

Pairwise keys still need secure provisioning and rotation outside the repository; this code does not provide a secrets manager, TLS, certificate identity, or cluster-wide consensus. Avoid one shared cluster-wide HMAC secret for production peer identity.


### Shared SQL transfer ledger adapter — foundation added

`JdbcPlayerTransferLedger` now implements the existing compare-and-set ledger contract over an injected JDBC `DataSource`. It stores transfer identity, player checkpoint revision, state, lease, fencing token and record version in a shared SQL table. Each transition is a single conditional UPDATE (`WHERE transfer_id = ? AND record_version = ?`), so concurrent nodes sharing the same transactional database cannot both advance one version. Schema initialization is explicit and can be run by deployment tooling.

This adapter is not automatically configured or enabled by server startup. A production deployment must provide a correctly configured shared transactional database and JDBC driver, run schema migration, protect credentials, and test database failover/isolation semantics. It does not itself replicate the database, distribute secrets, or make the end-to-end player handoff exactly-once. Live Bedrock participant integration and multi-process crash recovery remain outstanding.


### Live Bedrock source freeze — integrated, destination still pending

The source freeze also closes any open container window and returns transient crafting-grid items before checkpointing. On an abort, inventory/equipment reconciliation is sent best-effort before the source is re-advertised; frozen disconnect/shutdown paths deliberately do not write over the transfer checkpoint.

`BedrockSessionManager` now exposes a guarded source-transfer boundary. It resolves exactly one active session by authenticated XUID, takes the session combat lock, marks the session transfer-frozen, writes the legacy stores and canonical aggregate checkpoint, and returns the durable checkpoint revision. It then removes the player-list entry and remote actor from initialized peers and clears the source session's authenticated/initialized gameplay flags. Inbound frames are ignored while frozen; periodic saves and disconnect/shutdown cleanup do not overwrite or mutate the frozen snapshot.

`BedrockPlayerTransferSource` adapts this boundary to `PlayerTransferLifecycle.Source`, requiring the configured node ID to match the handoff source and resolving the authenticated XUID from caller-owned transfer context. The source is resumed only through the explicit abort callback, which re-advertises the player after restoring its active flags. If the network session disappears while frozen, abort does not silently create a replacement session; recovery must reconnect/re-authenticate the player.

This is source-side integration only. A destination participant is not yet implemented: it must load the exact checkpoint revision, rebuild all Bedrock runtime/session state, validate fencing before activation and ownership-sensitive writes, and handle network/session handoff. Therefore this work does not enable cross-server transfers and does not claim the end-to-end lifecycle is complete.


### Exact-revision destination restore and runtime fencing — foundation added

`PlayerStateCheckpointService.loadAtRevision` now loads only the exact durable checkpoint revision recorded by a transfer claim, rejecting missing/newer/older revisions and validating schema, checksum and XUID. `CheckpointedPlayerTransferDestination` verifies the target node, source/target identity, active claim, lease and fencing token before invoking a Bedrock-specific activator. It passes the immutable restored aggregate and a `PlayerTransferFence` to that activator. The fence re-reads the durable ledger and remains valid for the same owner after COMMITTED; it becomes invalid on expiry, abort, or a higher-token reclaim. Rollback deactivation is permitted only while the same claim is still active, and never tears down a committed or newer owner.

The activator is a strict integration boundary, not a completed Bedrock destination implementation. It must check the fence before every authoritative mutation and stop gameplay when the fence fails. The actual Bedrock network session migration/reconnect and session-state reconstruction still need implementation and fault-injection tests. These adapters do not enable cross-server transfer by themselves.


### JDBC ledger integration tests — added

The JDBC transfer ledger now has H2-backed integration coverage for durable state visibility through a fresh DataSource, duplicate transfer rejection, stale-version CAS rejection, immutable player/source/target/checkpoint identity, and two concurrent node claimants racing for one PREPARED transfer. These tests exercise real JDBC transactions and SQL constraints, but H2 is a test database; production deployments still need integration tests against their selected shared SQL engine and its failover/isolation configuration.
