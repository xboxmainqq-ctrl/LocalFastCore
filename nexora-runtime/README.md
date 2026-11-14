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
- Unit tests cover lifecycle ordering, rollback, duplicate/late registration, idempotent stop, shutdown retry, server-settings validation, and tick-scheduler behavior.

## Important limitation

This is a runtime foundation, not yet a playable Minecraft Bedrock server. It does not currently implement RakNet/Bedrock networking, Microsoft/Xbox authentication, packet codecs, worlds, chunks, entities, inventory, or gameplay. The tick scheduler is a deterministic component; a production game loop still needs to drive it using a monotonic clock and a bounded catch-up policy. Those systems must be implemented and integration-tested before NEXORA can accept real Bedrock clients.

## Build

From the repository root:

    mvn -f nexora-runtime/pom.xml --batch-mode --no-transfer-progress clean verify

No Nukkit libraries are required to build or run this module.
