# NEXORA unified server and Android client

Status: architecture decision and staged implementation plan. This document is not a claim that an Android client or playable server has already been implemented.

## Product decision

NEXORA is developed as one coordinated product with two independently buildable applications:

- **NEXORA Server** — standalone Java server runtime.
- **NEXORA Client** — an original Android application with its own rendering, input, UI, and network layers.

The client is the primary first-party test client for the server. Server and client changes must be reviewed together whenever they affect protocol messages, authentication, world state, chunk data, movement, or gameplay.

## Platform decision

NEXORA is a Minecraft-style multiplayer platform: a custom server core, a first-party Android client, and creator tools for worlds, gameplay content and interfaces. RPG is the first flagship mode; minigames, survival and other modes can share the same world and session foundation.

The server's compatibility boundary must be explicit. A Bedrock protocol adapter is the path for supported ordinary Minecraft Bedrock clients; a versioned NEXORA extension protocol may carry custom-client features such as richer UI metadata, creator tools and platform-specific capabilities. Do not describe the existing private NEXORA protocol as Bedrock-compatible, and do not claim vanilla-client support until login, encryption, movement, chunk streaming and gameplay have passed real-client tests. Advanced custom HUD and Visual Studio features are first-party-client features, not something an unmodified Minecraft client can render.

The first-party client and server are developed together. Their shared contracts cover identity, world state, chunks, character state, inventory, abilities, combat and replication. The server remains authoritative for every persistent or competitive gameplay result.

## Repository layout direction

Keep the existing standalone Java server runtime isolated from legacy Nukkit/LocalFastCore code. Add the Android client as a separate Gradle application module/repository component rather than introducing Android dependencies into the server runtime.

Proposed structure:

- `server/` — standalone server application and runtime.
- `client-android/` — Android app, UI, touch input, rendering and client session.
- `protocol/` — version-pinned protocol models, codecs and shared test vectors where code can genuinely be shared.
- `docs/` — protocol audits, architecture decisions, build and test instructions.

This is a target layout, not a statement that all modules already exist.

## Client architecture

Keep these client subsystems separate:

- Android application lifecycle, permissions, settings and navigation.
- Server discovery and connection management.
- NetherNet/WebRTC transport and identity/authentication.
- NEXORA packet framing, authentication and session state; optional Bedrock packet handling is separate.
- World/chunk decoding and client-side world representation.
- Renderer and resource pipeline.
- Touch controls, camera, interaction and UI overlays.
- Diagnostics: connection state, protocol version, packet counters, frame time and errors.

Rendering should be independent from networking. Network callbacks must not mutate render/game state directly; decoded events should be handed to a controlled client update loop.

## Server architecture

Keep the existing server layering:

- Runtime lifecycle and tick loop.
- Transport-neutral connection/session contracts.
- NetherNet transport adapter.
- Versioned NEXORA protocol/session layer (primary); optional Bedrock adapter remains isolated.
- Authentication and player session state.
- World, chunks, entities and gameplay.
- Persistence and server administration.

The server must remain independent of Android APIs and client rendering libraries.

## Private NEXORA protocol v1 (first handshake)

The first-party Android client now starts its application session with a private NEXORA v1 handshake after both NetherNet data channels open. It does not send Bedrock RequestNetworkSettings as the first application message.

The binary envelope uses the `NXRA` magic, a major/minor version, and a message type. ClientHello carries a bounded client build identifier. ServerHello returns a server UUID and a bounded maximum application-message size. The server then sends WorldInfo with the world identifier, vertical bounds, spawn coordinates, tick rate, and currently loaded chunk count. Deterministic rejection messages report unsupported protocol versions or malformed ClientHello payloads. Control messages are limited to 1 KiB and use reliable ordered delivery.

Server-side pieces are available as `NexoraPrivateProtocolCodec`, `NexoraWorldInfo`, `NexoraPrivateProtocolHandler`, and `NetherNetPrivateProtocolModule`. Android has a matching codec and displays the received world metadata. The handler accepts live world metadata and chunk-loading callbacks. After WorldInfo, the Android client requests a bounded 3×3 area around spawn. The server generates missing chunks in that neighborhood and returns sparse, bounded block-volume snapshots; the Android client builds a perspective voxel mesh with OpenGL ES 2.0, culls faces hidden by neighboring blocks, and supports drag-to-orbit plus pinch-to-zoom. The current protocol limits each connection to nine requests and rejects coordinates outside the spawn neighborhood. This is an interactive 3D viewer, not yet playable first-person gameplay. The `NexoraMain` launcher now has an explicit `--enable-development-network` option that registers the NetherNet signaling and private-protocol module after world, chunk-generation, and tick-loop modules. The default remains offline. This development option uses `NetherNetDevelopmentOfferValidator`, which deliberately accepts unauthenticated offers; it must only be used on a trusted private network and must not be exposed to the public internet. Its P-384 server identity key is temporary and regenerated on each process start, so clients may need to confirm the new fingerprint after a restart. Player authentication, dynamic view-distance streaming, interactive 3D rendering, movement, and gameplay are not implemented by this exchange. WebRTC currently relies on reachable UDP host candidates and has no STUN/TURN relay. This private protocol does not establish Bedrock interoperability.

## Shared development and test strategy

1. Add protocol golden test vectors: known input bytes and expected decoded values, plus known model values and expected encoded bytes.
2. Test codecs on both client and server against the same vectors.
3. Add a loopback/in-process test transport for fast deterministic protocol and gameplay tests. It is a test tool only, not a replacement for NetherNet in production.
4. Add a paired integration test that starts a NEXORA server and connects the NEXORA Android client to it.
5. Add a real-device test matrix for Android versions, screen sizes, GPU families, reconnects, network loss and background/foreground transitions.
6. Keep separate acceptance gates for internal NEXORA-client compatibility and unmodified Bedrock-client compatibility.

## Milestones

### A. Server protocol foundation
- Complete bounded packet framing and compression/encryption negotiation audit.
- Implement protocol 2225 login/session state only after verifying the complete packet sequence.
- Implement authentication and transport identity validation; never trust a successful data-channel open as player authentication.
- Add packet golden vectors and negative/fuzz-style bounds tests.

### B. Android client foundation
- Create the Android application shell and navigation.
- Add server address entry and connection diagnostics.
- Implement the transport/session state machine.
- Build a minimal renderer and resource loading path with original or properly licensed assets.
- Render a test scene before adding full world streaming.

### C. First playable NEXORA test
- Connect the Android client to the NEXORA server.
- Enter a generated world and receive chunk data.
- Move and rotate the camera.
- Break and place blocks with server-authoritative updates.
- Disconnect and reconnect without corrupting world state.

### D. Bedrock interoperability
- Test against unmodified Minecraft Bedrock 1.26.60 clients and servers where applicable.
- Verify protocol 2225, NetherNet identity, authentication, encryption, packet behavior and chunk/movement flows.
- Do not claim interoperability until real-client tests pass.

## Licensing and platform constraints

The client must not bundle proprietary Minecraft code, textures, sounds, fonts, or other assets without appropriate rights. Use original assets or assets with licenses compatible with distribution. Compatibility engineering does not imply permission to redistribute Minecraft content or impersonate official services.

The Android client is a substantial separate engineering effort. Performance must be measured on actual devices; high FPS cannot be guaranteed from server-side optimization alone.

### Player inventory and item actions (private NEXORA v1.2)

Protocol v1.2 uses message type 11 for a server-authoritative inventory snapshot. The packet identifies the owning player UUID and carries up to 36 occupied stacks. Each stack includes its exact zero-based slot index, a bounded UTF-8 namespaced item identifier, and a count from 1 to 64. Empty slots remain empty on the Android 6×6 inventory grid rather than shifting later items into earlier cells.

Client message type 12 requests one of four operations: move/merge/swap a stack between slots, drop one item, drop an entire stack, or use an item. The server resolves the player session from the connection, validates the action and slot bounds, mutates only that player's inventory, and returns type 13 with the action result followed by a fresh type 11 snapshot. Dropped items become server-owned world item entities and can be collected again. The Android UI supports tap-to-select and tap-to-move, plus buttons to drop one item, drop a stack, or request item use.

Item use is deliberately reported as NOT_USABLE for now: the runtime does not yet define item behavior, player health, or consumable effects, so the client cannot pretend an item was used.

Player inventories are persisted under `<data-directory>/players/<player-uuid>.nxi`. The binary file stores all 36 exact slots, bounded namespaced item IDs, stack counts, a format version, and a CRC32C checksum. Writes use a temporary file, fsync, and atomic replacement; symbolic-link paths, invalid lengths, wrong owners, unsupported versions, malformed stacks, and checksum failures are rejected. The server loads an inventory when the player joins, saves it after pickups and inventory actions, saves it on disconnect, and performs a final save during shutdown.

Until account authentication is implemented, the persistent player UUID is deterministically derived from the normalized player name. This is only a development identity mechanism: an unauthenticated client can claim another player's name when that player is offline. Do not expose the current development network mode to untrusted networks or treat name-derived identity as account security. Actual item-use behavior, item metadata/icons, and authenticated player accounts remain future work.
