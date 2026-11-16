# NEXORA — Minecraft-style game platform

## Product direction

**NEXORA is an Android-first block-world game platform with two experiences: classic offline survival single-player and customizable online RPG servers.** Both use the same original NEXORA Core with different rulesets. The platform has dedicated servers, a separate Android client, network/lobby services and creator tools. Its network structure is inspired by large Minecraft server networks, but NEXORA is an independent product with original code, identity and content.

The NEXORA Dedicated Server runs headlessly and owns authoritative worlds, chunks, blocks, entities, players, inventories, permissions, commands, events and gameplay. The separate NEXORA Android Client provides rendering, touch controls, UI/HUD, resource packs, server connection and creator tools. Single-player starts an embedded instance of the same NEXORA Core and works offline.

The target is Bedrock-style mobile gameplay; Java Edition is out of scope. A native Bedrock 1.26.60 / protocol 2225 ingress is now being integrated through NetherNet. It remains experimental until unmodified retail clients pass end-to-end login and gameplay tests. The NEXORA Client remains a separate first-party application.

Do not use Luanti or copy proprietary Minecraft source code/assets. Use public protocol documentation, clean-room implementations and original or properly licensed content.

Product briefs: [Survival single-player vs RPG servers](docs/SINGLEPLAYER_AND_SERVER_MODES.md) · [NEXORA RPG network vision](docs/PRODUCT_VISION_RPG_NETWORK.md) · Architecture decisions: [NEXORA platform direction](docs/architecture/nexora-platform-direction.md) · [Minecraft compatibility and client audit](docs/architecture/minecraft-compatibility-audit.md).

### Android-first game modes

- Launch target: Android phones/tablets with touch-first controls and installable APK builds.
- Single Player is a familiar survival sandbox: gather resources, mine/place blocks, craft, build, explore, survive, fight basic mobs and save/reload local worlds.
- Online dedicated servers provide the customizable RPG experience: characters, classes, stats, magic, abilities, equipment, mobs, bosses, quests, dungeons, economy and social systems.
- Both modes use the exact same NEXORA Core, but load different explicit rulesets: Survival locally, configurable RPG on servers.
- Network model: central lobby/player profiles, then multiple RPG worlds or instances on authoritative NEXORA dedicated servers as the platform grows.
- Unrelated standalone minigame modes such as BedWars and SkyWars are out of scope unless the product direction is explicitly changed.
- Design the client for easy, polished and efficient customization through data-driven content, resource packs, configurable HUD/UI, reusable components and creator tools rather than hardcoding each server's content into the app.
- Delivery order: playable offline Survival → one online RPG server tested with two Android clients → RPG systems and creator/customization tools → lobby and multi-world network services.

## Bedrock block compatibility

The world model keeps stable block names and now has an explicit compatibility registry for translating them to Bedrock block names. The initial registry covers the blocks emitted by the current overworld generator and allows custom NEXORA blocks to map to vanilla Bedrock blocks. This is only the name-translation foundation: per-session Bedrock runtime palette resolution and actual chunk-packet integration are still required. See [Bedrock block compatibility](docs/architecture/bedrock-block-compatibility.md).

## Private NEXORA client protocol

The first-party Android client and server now have a versioned private NEXORA v1 handshake over the existing NetherNet reliable channel. The first exchange negotiates protocol version, returns a server identifier and message-size limit, and rejects unsupported versions. The server then sends `WorldInfo` (world id, vertical bounds, spawn point, tick rate, and loaded chunk count). The Android client requests a 3×3 area around spawn. The server generates missing chunks in that bounded neighborhood and returns sparse block-volume snapshots (up to 32,768 non-air blocks per chunk). The Android client builds a perspective voxel mesh with OpenGL ES 2.0, omits faces hidden by neighboring blocks, and supports orbit-camera drag plus pinch zoom. It is an interactive 3D world viewer, not yet a playable first-person client. Chunk requests are restricted to the 3×3 spawn neighborhood and nine requests per connection. This is a NEXORA-only protocol, not Bedrock packet compatibility.

The server-side protocol module is now registered by `NexoraMain` only when `--enable-development-network` is explicitly supplied. The default launch remains offline. This flag enables unauthenticated development admission and is only for a trusted private network; do not expose it to the public internet. The temporary P-384 server identity key is regenerated at each restart. WebRTC currently requires reachable UDP host candidates (no STUN/TURN relay). Player authentication, dynamic view-distance streaming, a full interactive 3D renderer, collision, and gameplay remain incomplete. Handshake, chunk-volume codec, spawn-neighborhood generation, and request-boundary tests have been added but have not been confirmed running in CI.


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

## Bedrock 1.26.60 NetherNet ingress (experimental)

The server now has an opt-in Cloudburst NetherNet listener wired to the protocol-2225 initial login and world-startup pipeline. Signaling validates the Minecraft-issued NetherNet identity assertion, Bedrock Login independently validates the multiplayer token, and the login public key must match the key authenticated by signaling. The listener advertises the NEXORA server and prepares a 3×3 spawn area before accepting players.

Enable it with:

```bash
java -jar target/nexora-runtime-0.1.0-SNAPSHOT.jar \
  --data-dir ./nexora-data \
  --enable-bedrock-network \
  --bedrock-bind-address 0.0.0.0 \
  --bedrock-port 19132 \
  --bedrock-ice-port 0
```

Port 0 for the ICE option means WebRTC media shares the Bedrock port. The host must expose **both TCP and UDP** on that port; opening TCP signaling alone is not enough. A separate ICE UDP port can be selected with `--bedrock-ice-port` if the host allocates one. STUN discovery is enabled for public-address candidates.

The server creates `nethernet-identity.pem` in the data directory on first launch. Keep this file between restarts and deployments: replacing it changes the server's cryptographic identity. Retail authentication is mandatory; there is no unauthenticated public Bedrock mode.

**Compatibility is not yet certified.** Current automated tests cover the transport-neutral Bedrock handshake and identity-binding rejection, but CI does not prove that a retail 1.26.60 client can complete WebRTC, login, resource-pack negotiation and gameplay on a real host. Movement, block interaction and complete gameplay packet handling are still in progress. Do not advertise the server as fully playable until those tests pass. See [Bedrock NetherNet runtime notes](docs/BEDROCK_NETHERNET_RUNTIME.md).

## Current implementation

- Standalone Java 25 Maven module with no Nukkit, Bukkit, or Minecraft server API dependency.
- Initial RPG domain foundation: four namespaced classes (Warrior, Mage, Ranger, Guardian), immutable base attributes, and deterministic class registration.
- Server-side XP progression up to level 100, with five attribute points per level and bounded validation; see [NEXORA RPG architecture](docs/architecture/nexora-rpg.md).
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
- `CloudburstNetherNetBedrockModule` binds the Cloudburst NetherNet HTTP/WebRTC transport to the Bedrock router, using retail Minecraft identity validation and a persistent operator key. The older custom WebRTC development module remains separate and is not the retail Bedrock transport.
- NetherNet message framing helper: bounded reliable-message fragmentation with countdown headers, strict unfragmented-message validation for unreliable delivery, and tests that feed encoded fragments through the reassembler.
- Existing NetherNet fragment assembler enforces per-message byte, fragment-count, and assembly-time limits.
- Bounded NetherNet SDP parsing preserves the original offer for identity verification and removes the non-standard `a=identity` line only from the SDP passed to WebRTC.
- Client GameServerToken and detached fingerprint signatures are verified before admission; operator identity assertions are signed into every server answer and checked by the Android client.
- Bounded HTTP/HTTPS signaling implements `GET /v1/join` and `POST /v1/join/{networkId}`, with request limits, worker/queue bounds, timeouts, and complete SDP answers.
- Desktop Java WebRTC peer host configures full ICE, MAX_BUNDLE, UDP-only candidates, and both NetherNet data channels with bounded queues and fragment reassembly.
- Android client creates both data channels, gathers full ICE, performs the signaling exchange, verifies the operator identity, applies first-use key confirmation for plaintext HTTP, and waits for both channels to open.
- Private NEXORA v1 transfers sparse block-volume snapshots for a bounded 3×3 spawn neighborhood over the reliable channel. Missing nearby chunks are generated on request; the Android client renders received blocks with an OpenGL ES 2.0 perspective camera, hidden-face culling, drag-to-orbit, and pinch-to-zoom. Player movement and dynamic view-distance streaming remain unimplemented.
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

## Launch the dedicated server

The Maven package phase creates an executable shaded JAR with the runtime dependencies:

    mvn --batch-mode --no-transfer-progress clean verify
    java -jar target/nexora-runtime-0.1.0-SNAPSHOT.jar --help
    java -jar target/nexora-runtime-0.1.0-SNAPSHOT.jar --data-dir ./nexora-data

On first launch, the server creates `./nexora-data/server.properties`. Edit this file before starting the server to choose the world ID, generator, seed, vertical bounds and tick rate:

    world.id=world
    world.generator=overworld
    world.seed=0
    world.min-y=-64
    world.max-y-exclusive=320
    server.tick-rate=20
    server.network.enabled=false

A complete example extension is included at [examples/generators/flat-generator](examples/generators/flat-generator/README.md); build it as a separate JAR and install it into the extensions folder. The current built-in generator ID is `overworld`. Additional trusted generator extensions can be installed as JAR files in `./nexora-data/extensions/generators/`. Each JAR must provide a Java service descriptor at `META-INF/services/ru.nexora.runtime.WorldGeneratorExtension` containing the fully qualified provider class name. The provider implements `WorldGeneratorExtension`, returns a stable ID from `id()`, and creates a `ChunkGenerator` from `createGenerator()`. Select it with `world.generator=<extension-id>` in `server.properties`. JARs are loaded in filename order; duplicate or invalid IDs and broken providers stop startup with a clear error. Extension class loaders are closed during server shutdown.

**Security:** generator extensions are trusted Java code, not sandboxed plugins. They execute inside the server process with the server's operating-system permissions. Install only JARs from sources you trust. The server loads existing `.nxw` world data on later launches and saves it during graceful shutdown (Ctrl+C). Configuration is stored separately from world data, under the selected data directory.

When the server workflow succeeds, download the `nexora-core-server` artifact from the [NEXORA Server Runtime workflow](https://github.com/aqaqpopa-del/LocalFastCore/actions/workflows/nexora-server.yml) to obtain the executable JAR.

**This remains a development build, not a playable multiplayer release.** The default launch is offline; the explicit `--enable-development-network` option starts the NetherNet signaling endpoint and accepts the private NEXORA handshake in unauthenticated development mode. The runtime starts world storage, bounded chunk-generation workers, and the configured game tick loop. Production player authentication, secure persistent server identity, chunk streaming, rendering, gameplay, and real Android-device connection tests still need to be completed.

## Build

From the repository root:

    mvn --batch-mode --no-transfer-progress clean verify

No Nukkit libraries are required to build or run this module.
