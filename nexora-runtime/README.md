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
- Protocol-independent world foundation: immutable block/chunk coordinates, configurable vertical world bounds, immutable namespaced block states, and a sparse concurrent in-memory world.
- Thread-safe `WorldRegistry` for multiple worlds, with duplicate-ID rejection, normalized lookup/removal, stable registration order, and immutable snapshots.
- Immutable chunk and block snapshots exposed by the world contract.
- `FileWorldStorage` saves a world's loaded chunks to a versioned `.nxw` file, verifies a CRC32C checksum on load, rejects unsafe world IDs and symbolic-link targets, and replaces files atomically when supported.
- Persistence tests cover save/load, replacement, missing worlds, unsafe IDs, checksum corruption, and symbolic-link rejection.

## World storage format

The current `.nxw` format is an internal NEXORA development format. It stores world height, chunk coordinates, non-air block identifiers, block properties, and a checksum. Saves are written to a temporary file and then moved into place to reduce the risk of partial files.

**This is not the Minecraft Bedrock LevelDB world format.** It is a first persistence layer for the independent runtime; Bedrock-compatible world import/export will require a separately researched storage adapter.

## Important limitation

This is a runtime foundation, not yet a playable Minecraft Bedrock server. It does not currently implement RakNet/Bedrock networking, Microsoft/Xbox authentication, packet codecs, Bedrock LevelDB storage, chunk generation, entities, inventory, or gameplay. The game loop currently drives the scheduler; world simulation and network event processing still need to be implemented and integrated before NEXORA can accept real Bedrock clients.

## Build

From the repository root:

    mvn -f nexora-runtime/pom.xml --batch-mode --no-transfer-progress clean verify

No Nukkit libraries are required to build or run this module.
