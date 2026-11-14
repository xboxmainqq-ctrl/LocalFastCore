# NEXORA Bedrock 1.26.60 network target

Status: protocol target decision and research baseline. This document does not claim that a working Bedrock transport or playable server exists.

## Target

- Minecraft Bedrock Edition: **1.26.60**.
- Bedrock protocol version: **2225**.
- Primary dedicated-server transport: **NetherNet**.
- RakNet: deprecated for this target; do not implement it as the primary transport or allow it to leak into protocol/gameplay layers.

The official stable protocol changelog records 1.26.60 as protocol 2225, released 2026-10-27, with 64 packet changes compared with the previous stable release. Stable 1.26.61 and 1.26.62 retain protocol 2225 and have no packet changes. NEXORA's declared target remains 1.26.60; later same-protocol clients still require compatibility testing.

The official 1.26.60 release notes state that the dedicated-server warning about NetherNet was changed to an error when NetherNet is not used, because RakNet is deprecated.

## Source of truth

1. Mojang official Bedrock 1.26.60 changelog:
   https://www.minecraft.net/en-us/article/minecraft--bedrock-edition-26-60-changelog
   - Dedicated-server transport deprecation notice and gameplay/client changes.
2. Mojang official protocol reference, 1.26.60:
   https://mojang.github.io/bedrock-protocol-docs/1.26.60/
   - Packet schemas, supporting types, primitives and protocol 2225.
3. Mojang official protocol changelog:
   https://mojang.github.io/bedrock-protocol-docs/changelog/
   - Stable release protocol numbers and packet schema changes.
4. Mojang NetherNet partner onboarding guide:
   https://github.com/Mojang/bedrock-protocol-docs/blob/main/additional_docs/NetherNetOnboardingGuide.md
   - Signaling, identity assertions, peer connection and data-channel requirements.

## Architecture rules

- Keep transport, Bedrock session/protocol, gameplay, world, and persistence as separate layers.
- The game runtime must depend on a transport-neutral connection contract, not on NetherNet implementation classes.
- The transport adapter owns connection establishment, reliability/ordering semantics exposed by its API, close/error propagation, and transport diagnostics. It must use bounded inbound reassembly and outbound queues; queue saturation is surfaced as backpressure rather than unbounded memory growth.
- The Bedrock protocol layer owns packet framing/serialization, compression/encryption negotiation, login/session state, and packet-version selection.
- Do not assume NetherNet is interchangeable with generic WebRTC. Its exact signaling, channel setup, framing, and platform requirements must follow the Mojang onboarding guide and be verified against real clients.
- Do not copy packet IDs or schemas from a different protocol version. Pin schema inputs to protocol 2225 and add version-specific tests.
- Keep deprecated RakNet-specific code out of shared gameplay and packet model classes.

## Verified facts vs open questions

### Verified

- Stable Bedrock 1.26.60 was released 2026-10-27 and uses protocol 2225.
- The stable protocol changelog records 64 packet changes for 1.26.60 relative to the previous stable release.
- Stable 1.26.61 and 1.26.62 also use protocol 2225 and list no packet changes.
- Mojang's 1.26.60 release notes say the dedicated-server warning about trying NetherNet becomes an error when NetherNet is not used, as RakNet is deprecated.
- The official 1.26.60 protocol reference enumerates packet schemas, including Login, NetworkSettings, StartGame, LevelChunk, PlayerAuthInput and related packet types.

### Open questions — must be answered before claiming transport support

- Exact server-side implementation details and operational requirements for NetherNet.
- Signaling/discovery deployment, TLS, and network reachability requirements for a standalone third-party server.
- Real-client confirmation that the verified NetherNet offer identity is consistently associated with the following Bedrock Login and that the target client accepts the selected NetherNet login sequence.
- Android client compatibility and any platform-specific constraints.
- Which packet schema changes among the 64 changes affect the minimum playable login/world/chunk/movement path; these must be audited individually against the 1.26.60 reference before implementing those packet serializers.

No packet bytes, handshake messages, encryption behavior, or transport guarantees are to be invented to fill these gaps.

## Current implementation checkpoint — Login envelope

The standalone runtime currently has bounded Login packet decoding and a structural parser for the Connection Request JSON envelope:

- Parses both the legacy `chain`/`clientData` object and the newer `AuthenticationType`/`Token`/`Certificate` envelope.
- Bounds the JSON input, JWT string lengths, chain count, parser nesting depth (64), and rejects duplicate JSON keys and trailing JSON content.
- Checks only the compact JWT envelope shape and bounded fields; it does not authenticate those tokens.
- Exposes the parsed envelope through `BedrockLoginPacket.parseConnectionRequest()`.

**Security boundary:** the Login JSON parser is structural only. In the production NetherNet path, player authentication is performed earlier from Mojang's SDP `a=identity` assertion: the GameServerToken signature and claims are verified, then the detached fingerprint JWS proves possession of the key bound to the DTLS certificate. The verified identity is now carried from signaling into the WebRTC transport connection and then into the Bedrock router. The Login JSON itself is never treated as the source of trusted player identity.

### Server encryption handshake primitive

A first cryptographic building block now exists in `BedrockServerEncryptionHandshake`:

- Requires an EC server key pair and an authenticated P-384 client identity public key.
- Creates an ES384 compact JWT with the server public key in `x5u` and a random salt claim.
- Derives 256-bit key material as SHA-256(salt || ECDH-secret) using P-384 ECDH.
- Includes unit tests for JWT signature, client/server key agreement, and key size.
- This is an isolated legacy Bedrock key-exchange primitive; it is not wired into the NetherNet data-channel path and does not select an application cipher.

The protocol-2225 `PlayStatus` values and 4-byte big-endian packet codec are implemented. The router sends `PlayStatus(LoginSuccess)` after the signaling-authenticated player identity has been attached to the transport.

**Transport boundary:** Mojang's NetherNet guide defines the game transport as WebRTC data channels protected by DTLS. NEXORA binds the validated SDP identity to the created peer session; it does not infer player identity merely from an open data channel. The isolated legacy Bedrock ECDH primitive is not injected into this transport path. The exact packet 3/4 behavior and whether any additional game-layer encryption applies on NetherNet still require an unmodified 1.26.60 client test.

**Current router checkpoint:** the signaling validator now returns its verified player identity. The signaling server passes it to the peer host, the WebRTC session retains it, and the resulting transport connection exposes it as an optional authenticated identity. In the production authenticated path, the Bedrock router converts that trusted XUID, display name and P-384 key into `BedrockAuthenticatedPlayer`; it does not ask a second authenticator to trust the unverified Login JSON. Explicit development mode can still create identity-free sessions, but that mode is unauthenticated and must not be used publicly. The router sends compressed `PlayStatus(LoginSuccess)` and enters the distinct `LOGIN_SUCCESS_SENT` phase. It does **not** mark the client as PLAY immediately. The post-login callback is responsible for resource-pack negotiation and world startup; it must call `markPlayReady(connection)` only after the required StartGame/world initialization packets have been sent. A bounded post-login timeout closes sessions that never complete this phase. Unit tests cover identity propagation, the pre-PLAY phase, and the explicit world-ready transition.

**Still not complete:** no real unmodified 1.26.60 client has been connected to verify that the SDP identity, Login payload and PlayStatus sequence match the live client. The legacy certificate-chain verifier is not used as the production NetherNet trust anchor; the Mojang GameServerToken plus detached fingerprint proof is. Resource-pack negotiation, the StartGame serializer, a preloaded-spawn-chunk startup handler, and the subchunk request response path are now present as components. Full terrain data (including runtime palette, biome, fluid, and heightmap requirements), production composition, and live-client verification remain incomplete. Unit tests do not establish playable compatibility.

## Acceptance gates

A transport implementation is not considered supported until all of these are demonstrated against an unmodified Bedrock 1.26.60 client:

1. A standalone NEXORA process exposes the required NetherNet signaling/connection mechanism.
2. A real client establishes a transport connection.
3. Bedrock protocol negotiation reaches protocol 2225.
4. Login/authentication and encryption complete correctly.
5. The client receives the resource-pack/start-game flow and enters the world.
6. Chunk streaming, movement input, block updates, disconnect and reconnect work.
7. Repeated connection tests produce useful transport/session diagnostics.

Unit tests and protocol serialization tests are necessary but do not replace real-client integration tests.

## Version update policy

When targeting a newer Bedrock release:
1. Read its official release notes and protocol changelog.
2. Record the exact game version and protocol number.
3. Diff packet schemas and supporting types against the pinned version.
4. Audit changes affecting login, network settings, start game, chunks, movement, inventory and gameplay.
5. Add/update version-specific serialization tests.
6. Re-run transport and login integration tests against that exact client version.
7. Change the supported-version claim only after the acceptance gates pass.

## NetherNet onboarding details (Mojang partner guide)

### Signaling

- Client first requests `GET /v1/join`; a successful response identifies NetherNet support and returns server metadata including name, protocol, version, level, player count, maximum players, and game type.
- Client then sends one complete SDP offer to `POST /v1/join/{networkId}` as `application/sdp`. The `NetworkID` is opaque; do not parse or rely on its current numeric representation.
- HTTP signaling uses full ICE: the offer and answer contain gathered candidates; trickle ICE is disabled.
- The host creates the WebRTC answer, gathers its ICE candidates, and returns the SDP answer as `application/sdp`.
- Signaling is only used to establish the peer connection. Game traffic subsequently flows directly over WebRTC data channels.
- Mojang's guide says to prefer HTTPS signaling.

### Identity and authentication

- The client's SDP offer contains an `a=identity` assertion with a Minecraft `GameServerToken` JWT and a detached JWS signature over the SDP DTLS fingerprint values.
- The server must validate the token against the Minecraft authentication service's public keys, reconstruct the canonical fingerprint JSON, verify the detached JWS using the token's `cpk` public key, and authorize the resulting player identity.
- The server's SDP answer must also contain an `a=identity` assertion. The server identity JWT is self-signed and contains the operator's long-lived `cpk` public key; the detached JWS signs the answer's DTLS fingerprints.
- The client rejects an answer with a missing or malformed server identity assertion. The operator key is the trust identity; it should be persisted securely and rotated deliberately.
- Remove the custom `a=identity` SDP attribute before passing the SDP to a generic WebRTC implementation, as described by Mojang.

### Peer connection and data channels

- Client configuration: TCP ICE candidates disabled, max-bundle, full ICE, and no configured STUN/TURN URLs in the documented client setup.
- The client opens two SCTP data channels:
  - `ReliableDataChannel`: ordered, reliable, supports fragmentation.
  - `UnreliableDataChannel`: unordered, zero retransmits, no fragmentation.
- Every data-channel message begins with a one-byte NetherNet fragment header. `0` means complete/final fragment; positive values count down remaining fragments. Fragmentation is supported only on the reliable channel.
- The server must be prepared to receive both channels and send/receive on each.

### Implementation consequences

1. NEXORA needs a signaling HTTP endpoint and a WebRTC host/peer implementation; an abstract UDP socket or deprecated RakNet listener is not sufficient.
2. Signaling and the game-data transport are separate concerns and should have separate lifecycle/metrics.
3. Authentication and operator identity are transport-adjacent security requirements. Do not mark a connection authenticated merely because the WebRTC data channel opened.
4. Reliable and unreliable delivery must remain distinct all the way to packet scheduling; do not silently send latency-sensitive movement traffic through the reliable queue.
5. Fragment reassembly and outbound buffering must be bounded (maximum in-flight messages, maximum assembled bytes, per-message limit, queued-byte limit, and timeout) to prevent memory exhaustion.
6. A send operation must report whether a message was accepted, rejected due to backpressure, or rejected because the connection is closed. `ACCEPTED` is not a delivery acknowledgement.
7. The metadata endpoint must advertise the exact NEXORA game version and protocol target: **1.26.60 / 2225**. Do not copy illustrative values from onboarding examples.

These are protocol requirements from Mojang documentation, not proof that NEXORA has implemented them. The implementation remains pending.

## Resource-pack negotiation checkpoint — protocol 2225

The standalone runtime now has a deliberately limited empty-pack codec checkpoint:

- `BedrockResourcePackCodec` encodes packet 6 (`ResourcePacksInfo`) for a server that advertises no required packs, no add-ons, no scripts, and no forced Vibrant Visuals disable. It writes a zero world-template UUID with version `1.0.0` and an empty pack list.
- It encodes packet 7 (`ResourcePackStack`) with an empty texture-pack list, the configured base-game version, zero active experiments, and editor packs disabled.
- The experiment-toggle collection uses the schema's **uncompressed uint32 collection count** (native Bedrock byte order, little-endian), rather than the ordinary varuint32 collection count.
- It decodes packet 8 (`ResourcePackClientResponse`) response strings using the protocol-2225 enum names: `Cancel`, `Downloading`, `DownloadingFinished`, and `ResourcePackStackFinished`. The Downloading variant is bounded and parses its pack-ID list.

The official protocol reference identifies packet IDs 6, 7 and 8 and documents the packet fields; the protocol-2225 primitive reference defines native byte order and the no-size-compression annotation. In particular, protocol 1.26.60 changed the resource-pack response enum values from 0–3 to 1–4. Sources:
- https://mojang.github.io/bedrock-protocol-docs/1.26.60/packets/resource-packs-info-packet/
- https://mojang.github.io/bedrock-protocol-docs/1.26.60/packets/resource-pack-stack-packet/
- https://mojang.github.io/bedrock-protocol-docs/1.26.60/packets/resource-pack-client-response-packet/
- https://mojang.github.io/bedrock-protocol-docs/1.26.60/primitives/

**Scope limitation:** the post-login negotiation coordinator and startup components exist, but they do not prove that a live client accepts the chosen empty world-template metadata. The reusable `BedrockWorldStartupHandler` can send StartGame and skeleton chunks, but the production server composition still does not instantiate and wire it with a verified block registry and loaded spawn chunks. `LOGIN_SUCCESS_SENT` must remain distinct from `PLAY`; only the later completed world-startup path may call `markPlayReady(connection)`. No real Bedrock client compatibility is claimed.
### Post-login negotiation coordinator

`BedrockPostLoginCoordinator` now connects the packet codecs into a bounded state flow:

1. After the router sends `PlayStatus(LoginSuccess)`, the coordinator sends `ResourcePacksInfo` (packet 6).
2. It accepts only reliable-ordered packet 8 responses. An optional empty `Downloading` progress response is tolerated; a non-empty download list is rejected because this server advertised no packs.
3. On `DownloadingFinished`, it sends `ResourcePackStack` (packet 7).
4. On `ResourcePackStackFinished`, it invokes the injected `WorldStartupHandler`.
5. Only when that handler's completion stage succeeds does it call the router's `markPlayReady` transition. Startup failure, malformed/out-of-order responses, cancellation, and transport close all fail closed.

Composition is intentionally explicit so the world-startup implementation cannot be mistaken for an existing feature:

```java
BedrockPostLoginCoordinator postLogin = new BedrockPostLoginCoordinator(
    networkSettings, "1.26.60", worldStartupHandler, playMessageHandler
);
BedrockInitialTransportRouter router = new BedrockInitialTransportRouter(
    networkSettings, loginAuthenticator, postLogin, postLogin::handleMessage
);
postLogin.attachTo(router); // before starting the transport module
```

The `WorldStartupHandler` remains an injected boundary. `BedrockWorldStartupHandler` now provides a concrete minimal implementation: it verifies that configured spawn chunks are already loaded, then sends packet 11 (`StartGame`) followed by packet 58 skeleton chunks in one reliable-ordered batch. The subchunk request handler can answer requests during asynchronous startup before the router transitions to PLAY. Full section streaming still depends on a verified 1.26.60 runtime block registry and complete terrain metadata. Coordinator and startup tests are not live-client tests.
### StartGame serializer foundation — protocol 2225

Before writing the complete packet, the runtime now has the missing numeric primitives required by its schema:

- ZigZag `varint64` and base-128 `varuint64`, including ten-byte overflow checks.
- Explicit little-endian 32-bit and 64-bit values and IEEE-754 float encoding, independent of the caller's `ByteBuffer` byte order.
- Typed `Vec3`, `Vec2`, signed `BlockPosition`, and `SyncedPlayerMovementSettings` encoders/decoders.

The protocol reference marks StartGame packet 11 as 25 top-level fields. Its nested `LevelSettings` has 51 ordered fields in protocol 2225. The `Player Property Data` field uses the documented `PropertySyncData` structure (integer entries followed by float entries); the minimal NEXORA configuration writes both arrays and currently leaves them empty unless supplied. The optional `server_config` field is explicitly absent, and `ServerTelemetryData` writes its four protocol-2225 strings. The serializer emits every top-level field in schema order rather than truncating the packet.

Tests cover signed/unsigned 64-bit varint boundaries, overflow and truncation, native-order numeric bytes, vectors, signed block positions, and movement settings. These are unit-test additions; execution remains pending a functioning CI runner.

Protocol references:
- https://mojang.github.io/bedrock-protocol-docs/1.26.60/packets/start-game-packet/
- https://mojang.github.io/bedrock-protocol-docs/1.26.60/primitives/
### Complete LevelSettings object serializer

`BedrockLevelSettingsCodec` now writes all 51 `LevelSettings` fields in protocol-2225 order for a minimal survival flat world. It includes the fixed-width empty experiment count, empty game-rule list, optional experimental-gameplay flag encoded as absent, the new editor migration byte, and all trailing policy fields. Seed, dimension, spawn position, difficulty, day-cycle stop time, chunk tick range, and base-game version are supplied explicitly.

A schema-order test reads the serialized result field by field and verifies that no bytes remain after field 50. `BedrockStartGamePacketCodec` now embeds this object and serializes the complete protocol-2225 StartGame field sequence, including `PropertySyncData`, absent optional server configuration, and telemetry. The supported minimal profile currently emits no server block properties and no server configuration.

Reference: https://mojang.github.io/bedrock-protocol-docs/1.26.60/types/level-settings/
### StartGame packet serializer — protocol 2225

`BedrockStartGamePacketCodec` now serializes packet 11 in the exact 25-field schema order and can produce a `BedrockGamePacket` ready for the normal packet-batch encoder.

- Encodes the signed actor unique ID, unsigned runtime ID, game type, position, rotation, and the complete 51-field `LevelSettings`.
- Encodes level metadata, movement settings, current world time, signed enchantment seed, an empty server-block-property list, session correlation ID, and server version.
- Encodes `Player Property Data` as `PropertySyncData`: a varuint32-counted integer list (`varuint32` index + ZigZag `varint32` value), followed by a varuint32-counted float list (`varuint32` index + little-endian float value).
- Encodes the block registry checksum and both world-template UUID halves as little-endian uint64 values, followed by the three network flags.
- Explicitly marks optional `server_config` as absent, then serializes protocol-2225 `ServerTelemetryData` as four strings (`serverId`, `scenarioId`, `worldId`, `ownerId`).

**Current profile limits:** block properties are empty; optional server configuration is absent; player property lists are bounded to 4096 entries each; telemetry and packet strings have explicit byte limits. This is a valid schema-complete minimal profile, not evidence that every optional StartGame configuration is supported.

A new schema-order test reads every top-level field and asserts that no payload bytes remain. A packet-level test checks packet ID 11 and sub-client header values. Tests have been added but have not yet been executed because the GitHub Actions runner allocation has been failing before job steps begin. No live unmodified Bedrock client test has been performed. The skeleton-chunk startup path is implemented, but full terrain streaming and playable-world verification remain outstanding.

Protocol references:
- https://mojang.github.io/bedrock-protocol-docs/1.26.60/packets/start-game-packet/
- https://mojang.github.io/bedrock-protocol-docs/1.26.60/types/property-sync-data/
- https://mojang.github.io/bedrock-protocol-docs/1.26.60/types/server-telemetry-data/
### LevelChunk packet envelope — protocol 2225

`BedrockLevelChunkPacketCodec` now serializes all eight fields of packet 58 in schema order: chunk X/Z, dimension, subchunk count, optional client request limit, cache flag and metadata, length-prefixed binary chunk data, and the 1.26.60 client-biome-update flag.

- Binary chunk data is treated as an opaque byte sequence with a varuint32 byte length; it is never decoded or converted through UTF-8.
- Cache metadata uses little-endian uint64 blob IDs and is rejected unless cache mode is enabled.
- The codec can emit a skeleton LevelChunk using the `0xffffffff` subchunk-request sentinel, with an empty payload and no block entities.
- Bounds are applied to subchunk counts, cache metadata, and serialized chunk bytes.

**Important boundary:** this class is only the outer LevelChunk packet envelope. The later sections document the newly added runtime palette serializer, version-9 subchunk encoder, packet-174 response codec, world-backed response builder, and request handler. Those components still lack the bundled 1.26.60 runtime ID table, biome palettes, fluid storage, block entities, computed heightmaps, and production server composition. No playable-world or live-client compatibility claim is made.

Protocol references:
- https://mojang.github.io/bedrock-protocol-docs/1.26.60/packets/level-chunk-packet/
- https://mojang.github.io/bedrock-protocol-docs/1.26.60/packets/sub-chunk-packet/
- https://mojang.github.io/bedrock-protocol-docs/guides/sub-chunk-request-system-v1-18-10/
### Client subchunk requests — protocol 2225

`BedrockSubChunkRequestPacketCodec` now decodes client packet 175 using the stable 1.26.60 schema:

- Dimension is ZigZag `varint32`.
- The offset list uses a bounded `varuint32` count; each offset is three signed `int8` values.
- The center subchunk position is three little-endian `int32` coordinates.
- The decoder rejects zero/oversized request counts, truncated offset/position data, invalid dimensions, and trailing bytes.
- `Request.absolutePositions()` resolves relative offsets against the center using overflow-checked coordinate addition.

This is only the client request parser. NEXORA now has an initial world-backed response builder and packet-174 serializer. The remaining limitations are the externally supplied exact runtime block registry, biome/heightmap completeness, block entities, and wiring the builder into the live transport message handler. The request-system guide explains that a skeleton LevelChunk enables this request flow, but it does not replace the required subchunk responses.

Protocol references:
- https://mojang.github.io/bedrock-protocol-docs/1.26.60/packets/sub-chunk-request-packet/
- https://mojang.github.io/bedrock-protocol-docs/1.26.60/packets/sub-chunk-packet/
- https://mojang.github.io/bedrock-protocol-docs/guides/sub-chunk-request-system-v1-18-10/
### Runtime block palettes and SubChunkPacket responses — protocol 2225

The terrain serialization milestone now includes four standalone components:

- `BedrockRuntimeBlockRegistry` is an explicit adapter from NEXORA `BlockState` values to signed Bedrock int32 network IDs. It requires an air entry, rejects duplicate IDs and unknown states, and never guesses IDs from block names. Signed values are required for hashed block network IDs.
- `BedrockPalettedStorageCodec` emits network paletted storage: runtime header bit, XZY-ordered 4096 indices, little-endian packed words, varint palette size, and signed ZigZag-varint32 block IDs. Uniform sections use a zero-bit header and write their sole signed runtime ID directly, with no palette-size prefix; non-uniform palettes write the size and entries.
- `BedrockSubChunkTerrainCodec` emits a version-9 subchunk header, one block-storage layer, its network palette, and the empty block-actor NBT terminator (`TAG_End`). The current profile has no fluid layer or block actors.
- `BedrockSubChunkPacketCodec` emits packet 174 with cache disabled, relative signed-byte offsets, protocol-2225 result values, binary length-prefixed subchunk data, and explicit `NoData` heightmap markers. It uses the 1.26.60 enum values: Success=1, LevelChunkDoesntExist=2, WrongDimension=3, PlayerDoesntExist=4, IndexOutOfBounds=5, SuccessAllAir=6.

`BedrockSubChunkResponseBuilder` now resolves request offsets against the center position, checks player dimension, loaded chunk presence, and world section bounds, then serializes the corresponding loaded section. Empty sections return `SuccessAllAir`; missing chunks and invalid section Y values return their protocol result codes. Each request is capped at 64 offsets, each response packet at 64 entries, and each chunk snapshot is reused within the request.

**Remaining integration and protocol limits:** the exact 1.26.60 runtime block-ID table is not yet bundled (a strict external loader is available, but no verified palette has been supplied); the request handler is implemented but not yet installed by a production server composition root; the initial subchunk blob contains one block layer and an empty block-actor terminator only; it does not yet include fluid storage, biome palette data, actual block entities, or computed heightmaps. The packet codec marks both heightmap variants `NoData`, so this is not yet a complete production terrain stream. The LevelChunk skeleton and SubChunk response flow still require a real client test.

Tests have been added for uniform and multi-entry palettes, XZY index order, missing runtime IDs, version-9 subchunk headers and the empty block-actor terminator, protocol-2225 result values, world-backed section lookup, missing chunks, wrong dimensions, out-of-range Y, mixed transport batches, StartGame-before-skeleton ordering, and startup failure/backpressure. Tests have not been executed: the latest GitHub Actions jobs have been failing before any steps run because no runner is assigned. No live Bedrock client test has been performed.

Protocol references:
- https://mojang.github.io/bedrock-protocol-docs/1.26.60/packets/sub-chunk-packet/
- https://mojang.github.io/bedrock-protocol-docs/1.26.60/types/sub-chunk-packet-data/
- https://mojang.github.io/bedrock-protocol-docs/1.26.60/types/sub-chunk-heightmap-data/
- https://mojang.github.io/bedrock-protocol-docs/1.26.60/types/sub-chunk-pos-offset/
- https://mojang.github.io/bedrock-protocol-docs/guides/sub-chunk-request-system-v1-18-10/
### SubChunkRequest transport handler

`BedrockSubChunkRequestHandler` can now be supplied as the router's post-login/play message handler (or composed ahead of an existing gameplay handler). It:

- Decodes the negotiated Bedrock compression envelope and packet batch.
- Extracts packet 175 requests from incoming batches and routes them to the subchunk request parser and world response builder.
- Rebuilds and delegates any other gameplay packets in mixed batches to the configured fallback handler; batches without subchunk requests are passed through unchanged.
- Requires reliable-ordered delivery for subchunk requests and zero sub-client IDs.
- Sends packet 174 responses as compressed reliable-ordered batches and closes on malformed requests, invalid routing, or transport backpressure.
- Caps each client request at 64 subchunk offsets and processes at most 16 request packets per inbound message to bound CPU, memory, and outbound queue pressure.

Compose this handler as the gameplay delegate for the post-login coordinator. Use the same `BedrockRuntimeBlockRegistryProfile` for startup and subchunk serialization; the complete profile-aware example appears in the next section.

Additional reference:
- https://mojang.github.io/bedrock-protocol-docs/1.26.60/packets/sub-chunk-request-packet/
### Minimal world-startup handler

`BedrockWorldStartupHandler` now implements the coordinator's injected startup boundary for the first NEXORA world session:

1. It verifies that the configured spawn chunks are already loaded in the standalone `World`.
2. It creates the protocol-2225 `StartGame` packet using a caller-supplied per-player settings factory.
3. It appends one skeleton `LevelChunk` packet (subchunk request sentinel `0xffffffff`) for each configured spawn chunk.
4. It sends the ordered packet batch on the reliable channel and completes only when the transport accepts it.
5. The post-login coordinator then performs the explicit `LOGIN_SUCCESS_SENT` → `PLAY` transition. Requests arriving during the asynchronous startup window are routed to `BedrockSubChunkRequestHandler`.

Spawn chunks must be generated/loaded before this handler starts. The handler deliberately does not fabricate missing chunks or invent a Bedrock runtime palette. The subchunk request handler and StartGame serializer must share one version-pinned registry profile; without it, non-air NEXORA block states cannot be serialized safely.

Example using the computed hash profile and the new composition helper:

```java
BedrockRuntimeBlockRegistryProfile blockProfile =
    BedrockRuntimeBlockRegistryProfile.hashing();
BedrockWorldSessionPipeline pipeline = new BedrockWorldSessionPipeline(
    networkSettings,
    overworld,
    0,
    -1,
    List.of(new ChunkPosition(0, 0)),
    blockProfile,
    startGameSettingsFactory, // must set blockNetworkIdsAreHashes=true
    loginAuthenticator,
    otherPlayMessageHandler
);

// Register pipeline.router() as the authenticated peer connection callback.
// Close the pipeline when the server's transport module shuts down.
```

### Version-pinned external block registry file

`BedrockRuntimeBlockRegistryFile` loads a strict UTF-8 registry file and rejects a protocol/game-version mismatch. It enforces a 32 MiB file limit, 500,000 state limit, unique block states and IDs, valid namespaced identifiers, valid property pairs, and a required `minecraft:air` entry. It computes SHA-256 over the exact file bytes and can verify an externally pinned expected digest.

Supported file header:

```text
NEXORA-BEDROCK-BLOCK-REGISTRY<TAB>1
protocol<TAB>2225
game<TAB>1.26.60
id-mode<TAB>runtime
--
<namespaced-block-id><TAB><signed-int32-id>[<TAB><property>=<value>...]
```

`id-mode` is either `runtime` or `hash`. The loaded profile exposes `blockNetworkIdsAreHashes()` and `validateStartGameSettings(settings)`. The StartGame settings factory must copy this exact flag into `Settings.blockNetworkIdsAreHashes` and call `blockProfile.validateStartGameSettings(settings)` before returning them. `BedrockWorldStartupHandler` also requires the unified profile and validates the flag again before sending StartGame. Never set the flag independently from the registry source. Rows are data from a trusted, version-matched palette provider; NEXORA does not generate fabricated vanilla IDs.

No indexed 1.26.60 runtime-ID palette is bundled. The external loader supports that mode when a verified table is available; the computed hash profile is the alternative for states whose NBT property types are represented correctly in NEXORA.
### Computed hashed block-state IDs

`BedrockBlockStateHash` now computes signed 32-bit FNV-1a IDs from the little-endian NBT block-state compound `{ name, states }`, sorting property keys before serialization. The `minecraft:stone` empty-state hash is covered by a known-value test (`-2144268767`); `minecraft:unknown` uses Bedrock's reserved `-2` value.

`BedrockRuntimeBlockRegistry.hashing()` provides a dynamic lookup, and `BedrockRuntimeBlockRegistryProfile.hashing()` binds it to protocol 2225 with `BlockNetworkIdsAreHashes=true`. This avoids bundling Mojang's ordered block palette when the hash-based mode is selected. The hashing registry tracks encountered states process-wide, rejects hash collisions between different states, and caps its cache at 500,000 entries.

BlockState property strings support explicit NBT type prefixes for hash serialization: `s:` string, `b:` byte, `h:` short, `i:` int, `l:` long, `f:` float, and `d:` double. A value without a prefix remains a string for backwards compatibility. Use the prefix matching the exact vanilla block-state NBT type; an incorrect type produces a different hash and therefore a client-visible unknown block.

The same `BedrockRuntimeBlockRegistryProfile` must be passed to both `BedrockWorldStartupHandler` and `BedrockSubChunkRequestHandler`. Startup rejects a StartGame packet whose `BlockNetworkIdsAreHashes` flag differs from the profile. For indexed runtime-ID mode, load the externally sourced table with `BedrockRuntimeBlockRegistryProfile.from(loadedFile)` instead.
### Network biome palette codec

`BedrockBiomePaletteCodec` now serializes one 16×16×16 biome section using the Bedrock network palette layout: packed little-endian index words, unsigned VarInt biome IDs, and the network palette flag. Uniform sections omit both packed words and the palette-size prefix. Consecutive identical sections can use the `0xff` same-as-previous marker. The codec accepts exactly 4096 non-negative biome IDs per section and caps a sequence at 128 sections.

This codec is now backed by the standalone world model: `World` exposes block-resolution `biomeAt`/`setBiome` access and 4096-value section snapshots in Bedrock XZY order. `InMemoryWorld` stores sparse non-default biome sections, defaulting to Bedrock plains (ID 1). The internal `.nxw` format is now version 2 and persists those sections while retaining read compatibility with version 1. The codec is not yet inserted into `LevelChunk` or the SubChunk response body; full client-facing biome streaming remains pending. Protocol background: https://mojang.github.io/bedrock-protocol-docs/guides/sub-chunk-request-system-v1-18-10/
### Chunk and biome delivery findings (protocol 2225)

The current skeleton-LevelChunk plus SubChunkRequest path is not sufficient to deliver the world's biome palette. Keep the following protocol paths distinct:

- Mojang's protocol-2225 LevelChunkPacket schema describes its length-prefixed serialized chunk data as containing block, biome, heightmap, and block-entity data. The stable 1.26.60 changelog adds the trailing Is Client Biome Update boolean: https://mojang.github.io/bedrock-protocol-docs/1.26.60/packets/level-chunk-packet/
- SubChunkPacket (174) is a separate response containing serialized subchunk data and heightmap data; it does not expose a standalone biome-palette field: https://mojang.github.io/bedrock-protocol-docs/1.26.60/packets/sub-chunk-packet/
- ClientCacheMissResponsePacket (136) explicitly describes each blob as subchunk data plus biome data: https://mojang.github.io/bedrock-protocol-docs/1.26.60-preview.29/packets/client-cache-miss-response-packet/
- A detailed secondary layout reference documents a full LevelChunk column as subchunk sections, then one biome palette per included section, then border-block data and block-entity NBT. Its stated implementation basis is PowerNukkitX 3.0.5 / protocol 2193, so it is useful for investigation but must not be treated as authoritative proof of the exact 1.26.60 layout: https://versai.me/packets/level-chunk

Consequently, do not append biome palettes to SubChunkPacket entries or silently alter the existing subchunk blob. The current NEXORA path disables client blob caching and sends skeleton LevelChunk packets, so biome data is not yet delivered to the client. The next implementation must choose and validate one complete route: (a) full serialized LevelChunk column data, or (b) negotiated client-cache blobs with the required subchunk-plus-biome payload and matching cache metadata. Add byte-level fixtures and verify against an unmodified 1.26.60 client before claiming biome compatibility.
