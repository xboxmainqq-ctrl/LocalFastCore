# NEXORA Standalone Runtime

This module is the beginning of the independent NEXORA server process. It has **no Nukkit, Nukkit-MOT, Bukkit, or other game-server API dependency**.

## Current implementation

- Standalone Java 21 Maven module.
- Explicit server lifecycle states.
- Runtime module registration with unique identifiers.
- Immutable, validated standalone server settings (Bedrock UDP default port, player cap, tick rate, view distance, online-mode flag, and normalized data directory).
- Deterministic startup order and reverse shutdown order.
- Startup rollback: modules already started are stopped if a later module fails.
- Shutdown failure reporting preserves the original cause and suppressed failures.
- Failed shutdown modules remain available for a later retry; modules that stopped successfully are not stopped twice.
- Runtime module contract documents cleanup requirements for partial startup and retryable shutdown.
- Deterministic tick scheduler with delayed and repeating tasks, cancellation, stable execution order, and task-failure isolation.
- Dedicated game loop driven by `System.nanoTime()`, with configurable tick rate, bounded catch-up, backlog dropping, clean thread shutdown, and failure reporting.
- Protocol-independent world foundation: immutable block/chunk coordinates, configurable vertical world bounds, immutable namespaced block states, a minimal `World` contract, and a sparse concurrent in-memory world implementation.
- Thread-safe `WorldRegistry` for multiple worlds, with duplicate-ID rejection, normalized lookup/removal, stable registration order, and immutable snapshots.
- In-memory world chunks are created on demand and removed when their last non-air block is cleared; negative coordinates use correct floor-based chunk mapping.
- Unit tests cover lifecycle ordering, rollback, duplicate/late registration, idempotent stop, shutdown retry, settings validation, scheduler behavior, game-loop lifecycle/error handling, and world-registry behavior.

## Important limitation

This is a runtime foundation, not yet a playable Minecraft Bedrock server. It does not currently implement RakNet/Bedrock networking, Microsoft/Xbox authentication, packet codecs, persistent world storage, chunk generation, entities, inventory, or gameplay. The current world implementation is sparse and in-memory only; data is not saved across restarts. The game loop currently drives the scheduler; world simulation and network event processing still need to be implemented and integrated before NEXORA can accept real Bedrock clients.

## Build

From the repository root:

    mvn -f nexora-runtime/pom.xml --batch-mode --no-transfer-progress clean verify

No Nukkit libraries are required to build or run this module.
