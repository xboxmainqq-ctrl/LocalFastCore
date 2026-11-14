# NEXORA unified server and Android client

Status: architecture decision and staged implementation plan. This document is not a claim that an Android client or playable server has already been implemented.

## Product decision

NEXORA is developed as one coordinated product with two independently buildable applications:

- **NEXORA Server** — standalone Java server runtime.
- **NEXORA Client** — an original Android application with its own rendering, input, UI, and network layers.

The client is the primary first-party test client for the server. Server and client changes must be reviewed together whenever they affect protocol messages, authentication, world state, chunk data, movement, or gameplay.

## Compatibility target

The initial compatibility target remains Minecraft Bedrock 1.26.60, protocol 2225, with NetherNet as the required transport direction.

There are two distinct compatibility goals; they must not be confused:

1. **NEXORA client ↔ NEXORA server:** both applications can be developed and tested together. A private development protocol is possible for early local tests, but it must be explicitly versioned and must not be advertised as Bedrock-compatible.
2. **Unmodified Bedrock client ↔ NEXORA server:** requires correct Bedrock protocol 2225, NetherNet signaling and identity, authentication, encryption, packet sequencing, and real-client integration tests.
3. **NEXORA client ↔ other Bedrock servers:** requires the NEXORA client to implement the relevant Bedrock client protocol and authentication/transport behavior. This is a separate compatibility milestone, not an automatic result of building a custom client.

The preferred long-term design is a shared, versioned Bedrock-compatible protocol implementation where practical, while keeping client and server responsibilities separate. Do not create an undocumented private wire format and later call it Bedrock protocol.

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
- Bedrock packet framing, compression, encryption and session state.
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
- Bedrock 1.26.60 protocol/session layer.
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
