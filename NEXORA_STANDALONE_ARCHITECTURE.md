# NEXORA Standalone Architecture Roadmap

## Goal

Make NEXORA an independently runnable Minecraft Bedrock server runtime. Nukkit-MOT is a temporary compatibility host, not a permanent runtime dependency.

A standalone server is a substantial engineering project: removing the Maven dependency or renaming the plugin does not implement a server. NEXORA must eventually own the network protocol, login and authentication flow, world/chunk storage, block and entity simulation, inventory and gameplay rules, scheduling, permissions, and extension lifecycle.

## Architecture rules from this point forward

1. **Platform-neutral API first.** Public NEXORA APIs use Java types and immutable NEXORA data models only. They must not expose cn.nukkit classes, mutable Nukkit objects, or Nukkit event types.
2. **Adapters at the edge.** Nukkit-MOT integration belongs in a dedicated adapter package/module. A future standalone runtime will provide a different implementation of the same NEXORA interfaces.
3. **Compatibility is isolated.** Existing LocalFastCore and Nukkit plugin compatibility stays in the compatibility host. It must not dictate the design of new NEXORA APIs.
4. **Stable player identity.** Cross-system player references use UUIDs and immutable snapshots, not live platform player objects.
5. **No premature protocol claims.** A standalone Bedrock server is not complete until real clients can connect, authenticate, enter a world, play, disconnect, and reconnect against automated integration tests.

## Milestones

### A — Platform boundary (current)
- Define platform-neutral runtime contracts.
- Keep lifecycle events and player snapshots independent of Nukkit types.
- Add tests for public contracts and enforce package boundaries.
- Document which classes are compatibility adapters.

### B — Compatibility adapter
- Move Nukkit-specific listeners and runtime access behind an adapter.
- Inject the adapter into the public NEXORA API through a stable interface.
- Verify the existing Nukkit-MOT host and legacy plugin behavior on a test server.

### C — Server services
- Introduce NEXORA-owned player profiles, permissions, commands, scheduler, and persistence APIs.
- Use a database abstraction with a durable backend for player/world data.
- Keep storage migrations and backup/restore behavior tested.

### D — Bedrock protocol and world runtime
- Implement and test protocol negotiation, login, encryption/authentication integration, packet handling, and session lifecycle.
- Build world/chunk, block, entity, inventory, and gameplay services behind NEXORA interfaces.
- Add deterministic integration tests and a repeatable test-client workflow.

### E — Standalone runtime
- Add a server launcher and configuration independent of Nukkit-MOT.
- Run the same NEXORA modules against the standalone runtime.
- Verify ordinary Bedrock client compatibility and NEXORA-client capabilities separately.
- Remove Nukkit-MOT from the standalone distribution only after all required services have independent implementations.

## Exit criteria for removing Nukkit-MOT

Nukkit-MOT can be removed from the standalone runtime only when:
- the standalone launcher starts without Nukkit classes on its runtime classpath;
- a supported Bedrock client can connect and complete login;
- players can enter, modify, save, and reload a world;
- core gameplay services and plugin/module lifecycle work;
- automated integration tests cover connect, play, save, restart, and reconnect;
- compatibility hosting remains available separately if legacy Nukkit plugins are still required.

## Current implementation note

The ru.nexora.core.platform package currently contains the first platform-neutral contract (NexoraPlatform) and immutable PlayerSnapshot. The Nukkit adapter is not yet wired to this contract. This is an architectural starting point, not a standalone server implementation.
