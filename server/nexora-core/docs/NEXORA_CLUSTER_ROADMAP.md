# NEXORA cluster architecture and rollout plan

## Goal

NEXORA starts as one complete Bedrock game server. The codebase may prepare for a network of independent NEXORA server processes, but the first server must remain fully usable without a cluster, proxy, shared database, or cluster credentials.

The intended future network can contain separate game servers such as:

- `lobby-01` — entry point and server selection;
- `magic-01` — the first main magical survival/adventure server;
- `survival-01` — a separate survival mode;
- `minigames-01` — isolated minigame instances.

These are separate server processes, not worlds inside one process. Multiple worlds and dimensions inside one NEXORA process do not require cluster mode.

## Current implementation status

The repository already contains an experimental foundation:

- authenticated, allowlisted TCP node messaging;
- pairwise HMAC-SHA-256 message authentication and replay filtering;
- bounded send/receive worker pools and message queues;
- durable player-transfer records with compare-and-set state transitions;
- transfer fencing tokens and destination leases;
- shared JDBC player checkpoints and online-player leases;
- source freeze, destination activation, durable commit/abort, and recovery paths;
- Bedrock redirect routing after a confirmed transfer commit.

The implementation is intentionally not described as production-ready. End-to-end validation is still required for real Bedrock client reconnects, multi-process crash/restart races, network partitions, database failover, and operational key rotation. The current peer configuration is static; it is not an automatic service-discovery system. The TCP HMAC layer authenticates messages but does not encrypt the stream.

## Operating modes

### Mode A — standalone (the default for the first server)

Leave all cluster and shared-transfer JDBC environment variables unset.

Expected behavior:

- the server starts without opening a cluster listener;
- local file-backed transfer bookkeeping remains local to the host;
- no peer keys, peer addresses, cluster port, proxy, or external database are required;
- normal Bedrock gameplay does not depend on cluster availability.

This is the required mode while the first NEXORA server is being developed and validated.

### Mode B — controlled multi-node test

Only enable this mode in a private test environment after every node has:

1. a unique `NEXORA_NODE_ID`;
2. the same strongly consistent JDBC transfer ledger;
3. the same shared canonical player-checkpoint store;
4. a unique online-player lease namespace backed by that shared database;
5. an explicit peer allowlist and pairwise keys;
6. reachable Bedrock routes for target nodes;
7. synchronized clocks and monitored database connectivity.

Do not use asynchronous database replicas for ownership, lease, checkpoint, or transfer-decision reads.

The cluster listener must stay on a private network or an authenticated encrypted tunnel. HMAC authenticates and integrity-protects messages; it does not provide confidentiality.

### Mode C — production server network (future)

This mode is not yet approved. It requires the controlled test gates below plus production-grade operational procedures, alerting, backup/restore drills, key rotation, schema migration, database failover testing, and a tested client routing strategy.

## Future logical topology

A later network may look like this:

```text
Bedrock clients
      |
Public Bedrock entry / routing layer
      |
  +---+--------------------+
  |                        |
Lobby node             Game nodes
                         |
             +-----------+-----------+
             |                       |
         Magic node              Survival node

Shared services:
- strongly consistent player/transfer database
- canonical player checkpoint storage
- optional shared account/economy services (designed separately)
```

The routing layer and shared services are future components. The current implementation supports explicit peer routes and a transfer redirect flow; it should not be mistaken for a complete proxy, matchmaking service, global account system, or automatic node scheduler.

## Ownership and transfer invariants

These rules must remain true as the cluster evolves:

1. A player XUID has at most one valid online ownership lease.
2. The source freezes the player before publishing a transfer.
3. The destination may activate only a durable checkpoint associated with the exact prepared transfer.
4. A destination claim carries a monotonically increasing fencing token; stale owners cannot commit or mutate the newer transfer.
5. The source redirects only after a durable `COMMITTED` decision.
6. The source resumes only after a durable abort is confirmed.
7. An unknown outcome fails closed. Do not create two active player sessions to improve availability.
8. Player inventory, equipment, position, and other canonical state must be checkpointed consistently before ownership changes.
9. Cluster transport messages carry transfer identifiers and bounded metadata, not an untrusted authoritative player-state snapshot.
10. A node that loses its online lease or cannot establish authoritative ownership must stop the affected session safely.

Any future global economy, friends, parties, permissions, or statistics service must define its own consistency and ownership rules. Do not silently treat local per-server state as globally shared.

## Rollout stages

### Stage 0 — first standalone server

- Continue core gameplay and Bedrock compatibility work.
- Keep cluster configuration absent.
- Verify startup, gameplay, persistence, backups, and recovery on one host.
- Preserve optional cluster initialization: standalone startup must not create a dependency on cluster infrastructure.

### Stage 1 — cluster contract and test harness

- Document message types, ownership rules, timeout behavior, and compatibility/versioning.
- Add repeatable multi-process integration tests using two local server processes.
- Exercise disconnects at every transfer boundary: before prepare, after prepare, after claim, after activation, before commit, and after commit.
- Verify duplicate delivery, replay, stale fencing tokens, and clock-skew rejection.
- Test shared-database unavailability and recovery without admitting duplicate ownership.

### Stage 2 — two-node private test

- Run two independent NEXORA processes with unique node IDs.
- Use a shared primary database and shared checkpoint storage.
- Configure only explicit peer addresses and pairwise secrets.
- Test real Bedrock client transfer and reconnect in both directions.
- Kill either process during transfer and verify deterministic recovery.
- Test packet loss, latency, peer restart, database restart, and network partition.

### Stage 3 — lobby and additional game nodes

Only after Stage 2 passes:

- add a dedicated lobby node;
- add a controlled server-selection/routing component;
- add target capacity and maintenance/draining signals;
- add operator-visible node health and transfer metrics;
- define player return behavior when a destination is unavailable.

### Stage 4 — production readiness

Require all of the following before public enablement:

- repeatable real-client transfer tests;
- crash/restart and partition test evidence;
- tested database backup, restore, and failover;
- TLS or a managed encrypted private tunnel;
- key rotation and secret storage procedures;
- schema migration and rollback procedures;
- alerting for leases, transfer recovery, replay rejection, queue saturation, and node health;
- documented operator procedures for stuck/frozen transfers;
- load tests at expected peak player counts.

## Configuration boundary

The current configuration is intentionally opt-in:

- `NEXORA_NODE_ID` identifies a process when shared transfer ownership is configured.
- `NEXORA_TRANSFER_JDBC_*` configures shared transfer and online-lease persistence.
- `NEXORA_PLAYER_STATE_JDBC_*` configures canonical shared player checkpoints.
- `NEXORA_CLUSTER_BIND`, `NEXORA_CLUSTER_PEERS`, and `NEXORA_CLUSTER_PEER_KEYS` enable authenticated peer transport.
- `NEXORA_CLUSTER_BEDROCK_ROUTES` maps peer IDs to the Bedrock addresses clients use after transfer.

Do not set only part of the cluster configuration. Startup validation is expected to reject incomplete or inconsistent configuration rather than silently entering a partially connected mode.

## Non-goals for the first release

The first public NEXORA server does not need:

- a proxy or global matchmaking;
- automatic service discovery;
- cross-server chat;
- global economy or global permissions;
- automatic player balancing;
- cross-node world simulation;
- distributed ticking or shared live entity simulation.

These can be designed when actual additional game servers are ready. The initial priority remains a stable, performant standalone server.

## Current validation additions

The test suite now includes a real two-process TCP transport integration test: two independent JVMs bind separate loopback listeners, authenticate with a configured peer key, send a bounded cluster message, and verify receiver delivery plus sender acknowledgement. This complements the existing independent-JVM JDBC online-lease race/restart tests; it does not yet simulate full Bedrock client handoff between two running game servers.

The in-memory `ServerNodeRegistry` now exposes a stable ID-sorted health snapshot that includes stale and unhealthy nodes for operator monitoring. Delayed/out-of-order heartbeats cannot overwrite a newer load/health report. Routing queries continue to return only healthy, non-stale nodes.
