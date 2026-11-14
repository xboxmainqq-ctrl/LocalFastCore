# NEXORA Standalone Runtime

This module is the beginning of the independent NEXORA server process. It has **no Nukkit, Nukkit-MOT, Bukkit, or other game-server API dependency**.

## Private NEXORA client protocol

The first-party Android client and server now have a versioned private NEXORA v1 handshake over the existing NetherNet reliable channel. The first exchange negotiates protocol version, returns a server identifier and message-size limit, and rejects unsupported versions. The server then sends `WorldInfo` (world id, vertical bounds, spawn point, tick rate, and loaded chunk count), which the Android client can decode and display. This is a NEXORA-only protocol, not Bedrock packet compatibility.

The server-side protocol module is now registered by `NexoraMain` only when `--enable-development-network` is explicitly supplied. The default launch remains offline. This flag enables unauthenticated development admission and is only for a trusted private network; do not expose it to the public internet. The temporary P-384 server identity key is regenerated at each restart. WebRTC currently requires reachable UDP host candidates (no STUN/TURN relay). Player authentication, chunk streaming, rendering, and gameplay remain incomplete. Protocol tests have been added but have not been confirmed running in CI.


### Start the server for a private Android-client test

The safe default is still offline. To enable the current unauthenticated development handshake on a trusted LAN:

```bash
java -jar target/nexora-runtime-0.1.0-SNAPSHOT.jar \
  --data-dir ./nexora-data \
  --enable-development-network \
  --bind-address 0.0.0.0 \
  --signaling-port 8080
```

In the NEXORA Android client, enter `http://<server-LAN-IP>:8080`. Confirm the server identity fingerprint only after checking it with the server owner. Do not port-forward this development endpoint or use it on an untrusted network. The WebRTC peer connection also needs a reachable UDP host candidate; a successful HTTP signaling response alone does not guarantee that the data channels can connect.

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
- Frame-level Bedrock initial handshake processor joins batch framing, compression, RequestNetworkSettings, NetworkSettings response creation, and compressed Login decoding.
- `BedrockInitialTransportRouter` connects accepted NetherNet peers to per-connection handshake processors, sends NetworkSettings over the reliable ordered channel, and hands decoded Login requests to an asynchronous authentication callback, then forwards subsequent messages to the play handler only after authentication succeeds.
- `NetherNetBedrockModule` composes the WebRTC host, HTTP signaling server, and Bedrock transport router into one lifecycle-managed module for `NexoraServer`.
- NetherNet message framing helper: bounded reliable-message fragmentation with countdown headers, strict unfragmented-message validation for unreliable delivery, and tests that feed encoded fragments through the reassembler.
- Existing NetherNet fragment assembler enforces per-message byte, fragment-count, and assembly-time limits.
- Bounded NetherNet SDP parsing preserves the original offer for identity verification and removes the non-standard `a=identity` line only from the SDP passed to WebRTC.
- Client GameServerToken and detached fingerprint signatures are verified before admission; operator identity assertions are signed into every server answer and checked by the Android client.
- Bounded HTTP/HTTPS signaling implements `GET /v1/join` and `POST /v1/join/{networkId}`, with request limits, worker/queue bounds, timeouts, and complete SDP answers.
- Desktop Java WebRTC peer host configures full ICE, MAX_BUNDLE, UDP-only candidates, and both NetherNet data channels with bounded queues and fragment reassembly.
- Android client creates both data channels, gathers full ICE, performs the signaling exchange, verifies the operator identity, applies first-use key confirmation for plaintext HTTP, and waits for both channels to open.
- Protocol-independent world foundation: immutable block/chunk coordinates, configurable vertical world bounds, immutable namespaced block states, sparse in-memory chunks, immutable generated-chunk snapshots, and block-resolution Bedrock biome storage (4096 biome cells per 16³ section), plus separate sparse fluid sections for water/lava block states.
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
- `FileWorldStorage` saves loaded chunks, non-default biome sections and separate fluid cells to version-3 `.nxw` files; it reads legacy v1 (blocks) and v2 (blocks + biomes), verifies CRC32C, rejects unsafe world IDs and symbolic-link targets, and replaces files atomically when supported.
- `WorldStorageModule` loads configured worlds during runtime startup and saves them during shutdown. It reuses worlds already in the registry, rolls back worlds it registered if startup fails, and keeps failed shutdown saves retryable.
- Persistence tests cover save/load, replacement, missing worlds, unsafe IDs, checksum corruption, symbolic-link rejection, lifecycle integration, and reload into a fresh registry.

## Performance direction

The world data model is being designed to avoid per-block object and hash-map overhead. Chunk sections are sparse, and each active section uses a compact palette. This is a server-side memory and CPU optimization foundation; it does not by itself set the Android client's FPS. Later client-facing work will also minimize redundant chunk updates, batch network writes, and send only data needed by the client's view distance.

## World storage format

The current `.nxw` format is an internal NEXORA development format. Version 3 stores world height, chunk coordinates, non-air block identifiers and properties, sparse 16³ biome sections (Bedrock biome IDs in XZY order), separate fluid cells and a checksum. Version 1 (blocks only) and version 2 (blocks + biomes) files remain readable. Saves are written to a temporary file and then moved into place to reduce the risk of partial files.

**This is not the Minecraft Bedrock LevelDB world format.** It is a first persistence layer for the independent runtime; Bedrock-compatible world import/export will require a separately researched storage adapter.

## Important limitation

This is still not a playable or Bedrock-compatible server release. The repository now contains HTTP/HTTPS NetherNet signaling, a desktop Java WebRTC peer-host adapter, Android-side offerer/signaling/data-channel code, and operator identity verification with TOFU for plaintext HTTP. These components have authored tests, but a successful CI/build and real Android-to-server session have not yet been confirmed. The Android client does not yet acquire official Microsoft/Xbox credentials or produce a live authenticated GameServerToken, so real-account interoperability is not established. The Bedrock layer currently handles selected 1.26.60 packets, post-login resource-pack negotiation, StartGame, experimental full-column LevelChunk serialization, and a SubChunkRequest response path with block/liquid palettes and primary heightmap data. These pieces are not yet a production-complete or live-client-verified chunk pipeline: biome delivery through the active SubChunk path, render-heightmap computation, real block entities, exact client acceptance, production network composition, Bedrock LevelDB storage, entities, inventory, and gameplay remain incomplete. The server WebRTC profile currently expects a directly reachable UDP host candidate and does not configure STUN/TURN relay traversal.

## Launch the development runtime

The Maven package phase creates an executable shaded JAR with the runtime dependencies:

    mvn --batch-mode --no-transfer-progress clean verify
    java -jar target/nexora-runtime-0.1.0-SNAPSHOT.jar --help
    java -jar target/nexora-runtime-0.1.0-SNAPSHOT.jar --data-dir ./nexora-data --seed 0

The first launch creates a flat test world and its spawn chunk. Existing `.nxw` world data is loaded on later launches and saved again during graceful shutdown (Ctrl+C). The default data directory is `./nexora-data`.

When the server workflow succeeds, download the `nexora-core-server` artifact from the [NEXORA Server Runtime workflow](https://github.com/aqaqpopa-del/LocalFastCore/actions/workflows/nexora-server.yml) to obtain the executable JAR.

**This is an offline development bootstrap, not a playable multiplayer release.** It starts world storage, chunk-generation workers, and the 20 TPS game loop, but deliberately does not bind a NetherNet signaling endpoint or accept Android clients yet. The production login identity, exact Bedrock block palette, and live client session still need to be completed and tested.

## Build

From the repository root:

    mvn --batch-mode --no-transfer-progress clean verify

No Nukkit libraries are required to build or run this module.
