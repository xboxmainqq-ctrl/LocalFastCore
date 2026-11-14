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
- Bedrock 1.26.60 / protocol 2225 payload codecs for RequestNetworkSettingsPacket, NetworkSettingsPacket, and LoginPacket, with bounded binary primitives and unit tests.
- Bounded uncompressed Bedrock packet-batch framing with packet length prefixes, packet ID/sub-client headers, and unit tests.
- Bedrock batch compression envelope for pre-negotiation batches, raw DEFLATE, and uncompressed frames, with decompression limits; Snappy remains explicitly unsupported.
- Frame-level Bedrock initial handshake processor joins batch framing, compression, RequestNetworkSettings, NetworkSettings response creation, and compressed Login decoding. The host still owns actual transport delivery and real identity/account authentication.
- NetherNet message framing helper: bounded reliable-message fragmentation with countdown headers, strict unfragmented-message validation for unreliable delivery, and tests that feed encoded fragments through the reassembler.
- Existing NetherNet fragment assembler enforces per-message byte, fragment-count, and assembly-time limits.
- Protocol-independent world foundation: immutable block/chunk coordinates, configurable vertical world bounds, immutable namespaced block states, sparse in-memory chunks, and immutable generated-chunk snapshots.
- Compact chunk storage: sparse 16x16x16 sections with local block-state palettes; common sections use four bits per block index, widening to one byte and then two bytes only when needed.
- Chunk reads/writes use packed local coordinates and avoid allocating a coordinate record for each block access.
- Palette-width regression tests cover nibble-to-byte and byte-to-short transitions, all local section coordinates, and clearing blocks back to air.
- A deterministic flat-terrain chunk generator with validated world bounds, negative chunk-coordinate support, and a bounded generation depth.
- Bounded asynchronous chunk generation with fixed worker count, a capped work queue, in-flight request deduplication, cancellation, and a bounded completion handoff for the game thread.
- Atomic insertion of generated chunks into the in-memory world; duplicate loads do not overwrite live chunk data.
- Generation workers produce immutable results only; world mutation is left to the game thread when it drains completed results.
- `WorldChunkPipeline` connects generation to `TickScheduler`, commits completed chunks on game ticks, and limits chunk commits per tick.
- Thread-safe `WorldRegistry` for multiple worlds, with duplicate-ID rejection, normalized lookup/removal, stable registration order, and immutable snapshots.
- Immutable chunk and block snapshots exposed by the world contract.
- `FileWorldStorage` saves a world's loaded chunks to a versioned `.nxw` file, verifies a CRC32C checksum on load, rejects unsafe world IDs and symbolic-link targets, and replaces files atomically when supported.
- `WorldStorageModule` loads configured worlds during runtime startup and saves them during shutdown. It reuses worlds already in the registry, rolls back worlds it registered if startup fails, and keeps failed shutdown saves retryable.
- Persistence tests cover save/load, replacement, missing worlds, unsafe IDs, checksum corruption, symbolic-link rejection, lifecycle integration, and reload into a fresh registry.

## Performance direction

The world data model is being designed to avoid per-block object and hash-map overhead. Chunk sections are sparse, and each active section uses a compact palette. This is a server-side memory and CPU optimization foundation; it does not by itself set the Android client's FPS. Later client-facing work will also minimize redundant chunk updates, batch network writes, and send only data needed by the client's view distance.

## World storage format

The current `.nxw` format is an internal NEXORA development format. It stores world height, chunk coordinates, non-air block identifiers, block properties, and a checksum. Saves are written to a temporary file and then moved into place to reduce the risk of partial files.

**This is not the Minecraft Bedrock LevelDB world format.** It is a first persistence layer for the independent runtime; Bedrock-compatible world import/export will require a separately researched storage adapter.

## Important limitation

This is a runtime foundation, not yet a playable Minecraft Bedrock server. It currently contains selected Bedrock 1.26.60 payload codecs, packet-batch framing and compression, a frame-level initial handshake processor, and transport-independent NetherNet framing helpers. It still does not implement the WebRTC peer connection, HTTP signaling, server identity assertions, Microsoft/Xbox authentication, Bedrock LevelDB storage, entities, inventory, or gameplay. Basic flat chunk generation, bounded background generation, and in-memory chunk insertion exist, but chunk streaming to clients and a production terrain generator are not implemented. The game loop currently drives the scheduler; full world simulation and network event processing still need to be integrated before NEXORA can accept real Bedrock clients.

## Build

From the repository root:

    mvn --batch-mode --no-transfer-progress clean verify

No Nukkit libraries are required to build or run this module.
