# NEXORA Standalone Runtime

This module is the beginning of the independent NEXORA server process. It has **no Nukkit, Nukkit-MOT, Bukkit, or other game-server API dependency**.

## Current implementation

- Standalone Java 21 Maven module.
- Explicit server lifecycle states.
- Runtime module registration with unique identifiers.
- Deterministic startup order and reverse shutdown order.
- Startup rollback: modules already started are stopped if a later module fails.
- Unit tests for lifecycle ordering, rollback, and registration rules.

## Important limitation

This is a runtime foundation, not yet a playable Minecraft Bedrock server. It does not currently implement RakNet/Bedrock networking, Microsoft/Xbox authentication, packet codecs, worlds, chunks, entities, inventory, or gameplay. Those systems must be implemented and integration-tested before NEXORA can accept real Bedrock clients.

## Build

From the repository root:

    mvn -f nexora-runtime/pom.xml --batch-mode --no-transfer-progress clean verify

No Nukkit libraries are required to build or run this module.
