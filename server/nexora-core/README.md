# NEXORA Core

Independent Minecraft Bedrock server implementation. The code is an early development scaffold, not yet a playable Bedrock server.

## Current transport direction: NetherNet

The initial target is modern Minecraft Bedrock clients. NetherNet is the required primary transport for current clients; RakNet is retained only as a legacy protocol experiment.

The modern connection path is:

1. Client calls GET /v1/join to verify NetherNet support and read server metadata.
2. Client sends its complete WebRTC SDP offer to POST /v1/join/{networkId}.
3. NEXORA validates the client's GameServerToken and detached DTLS-fingerprint JWS, then creates a WebRTC answer.
4. NEXORA signs its server identity assertion and returns the complete SDP answer.
5. Client and server establish ICE, DTLS and SCTP data channels.
6. NEXORA receives framed Bedrock game messages on the reliable and unreliable channels.

Official Mojang onboarding guide: https://mojang.github.io/bedrock-protocol-docs/guides/nether-net-onboarding-guide/

## Implemented

- Java 21 / Maven project.
- Legacy RakNet unconnected ping/pong codec and UDP discovery responder (for protocol study; not sufficient for modern clients).
- NetherNet server metadata model and JSON serialization.
- NetherNet HTTP signaling endpoint:
  - GET /v1/join returns server metadata.
  - POST /v1/join/{networkId} validates the SDP request and delegates negotiation.
  - Request size limit, content-type validation, bounded worker pool, and explicit error responses.
- WebRTC SDP answerer using webrtc-java, with full ICE gathering and peer-session limits.
- NetherNet identity implementation:
  - Verifies client GameServerToken RS256 signatures against a configurable Minecraft auth JWKS endpoint.
  - Checks the configured issuer and validates expiry/not-before when those claims are present; audience validation can be enabled through the identity-service constructor.
  - Verifies the client's ES384 detached DTLS-fingerprint assertion.
  - Creates a persistent P-384 server identity keypair, self-signed server JWT and signed fingerprint assertion.
- NetherNet message framing and reliable-channel fragmentation/reassembly with size limits.
- WebRTC DTLS provides transport encryption; the legacy Bedrock application-layer cipher is intentionally disabled on NetherNet.
- Bedrock packet batch codec: VarInt packet lengths and headers, protocol 2225 RequestNetworkSettings parsing, NetworkSettings response, raw-DEFLATE compression framing and uncompressed framing.
- Initial Bedrock session stage: validates the requested protocol, sends NetworkSettings, and switches to negotiated compression.
- Login stage: matches the Login GameServerToken to the verified NetherNet identity, verifies the client's ES384 client-data JWT, requires an authenticated Xbox Live identity, and handles the empty ClientToServerHandshake used with NetherNet's DTLS transport.
- Empty resource-pack exchange: sends LoginSuccess, ResourcePacksInfo and ResourcePackStack, then validates the client's completion response.
- Initial world bootstrap: sends a protocol-2225 StartGame packet with a unique player runtime ID, survival mode, Overworld spawn metadata, followed by SetTime, SetDifficulty, SetCommandsEnabled, SetSpawnPosition, SetPlayerGameType and NetworkChunkPublisherUpdate.
- Protocol-2225 biome registry captured from Bedrock Dedicated Server 1.26.60.6, stored with third-party attribution and sent during world bootstrap.
- Initial LevelChunk encoder: hashed block-state network IDs, version-9 sub-chunks, negative-height empty sections, 3D plains biome palettes and protocol-2225 chunk framing.
- Sends the generated spawn chunk after StartGame and waits for the client's SetLocalPlayerAsInitialized acknowledgement before reporting PlayerSpawn.
- Initial server-authoritative world model: 24 vertical sections (-64..319), sparse chunk sections, negative block coordinates, and a deterministic flat starter platform.
- Versioned world-chunk persistence with CRC32C integrity checks and atomic file replacement; loaded chunks are restored at startup and saved on shutdown.
- Unit tests for legacy RakNet discovery, signaling HTTP behavior, identity signatures, NetherNet framing, Bedrock batches, login parsing and the initial login/resource-pack stages.

## Not implemented / not wired yet

The following list is intentionally conservative: a protocol stage is only considered implemented after its packet format, state transitions and tests are present. A passing unit test is not a substitute for a real Android Bedrock connection test.

- The main bootstrap now starts the NetherNet HTTP signaling endpoint and the WebRTC answerer alongside the legacy UDP discovery experiment.
- NEXORA's signaling listener is plain HTTP; production TLS termination is provided by the documented Caddy reverse-proxy example. The application does not terminate TLS itself.
- Only the initial spawn chunk is sent. Chunk-radius streaming, chunk unloads, biome variation, block updates, player movement, entity replication and inventory are not implemented. Player data persistence and periodic autosave are also not implemented. The world is still not playable until those gameplay paths and an Android-client end-to-end test are complete.
- No general outbound gameplay-packet pipeline yet; only login, NetworkSettings, PlayStatus and empty resource-pack handshake responses are sent.
- Guest/offline authentication is not supported; the current login path requires an authenticated Xbox Live GameServerToken.
- No Android-client end-to-end connection test.

## Protocol target

Initial stable game target: **Bedrock 1.26.62 / game protocol 2225**. The transport target is NetherNet. Re-check Mojang's official protocol reference before changing either value.

## Legacy RakNet discovery experiment

The bootstrap runs the modern NetherNet signaling endpoint over TCP and keeps the legacy RakNet discovery responder over UDP on the same port. The transport path can establish WebRTC data channels, but it is not yet a playable server.

Build and test locally (the GitHub Actions workflow also runs this command for changes under server/nexora-core):

~~~sh
mvn -f server/nexora-core/pom.xml clean verify
~~~

The build copies runtime dependencies to server/nexora-core/target/lib/ so the application jar can be launched with its manifest classpath:

~~~sh
java -jar server/nexora-core/target/nexora-core-0.1.0-SNAPSHOT.jar 0.0.0.0 19132 "NEXORA Core" 20
~~~

The WebRTC implementation uses native libraries supplied by webrtc-java; the deployment host must match a published native classifier and have the required system runtime libraries installed. If the VPS is behind NAT, set NEXORA_STUN_URLS to a semicolon-separated list of STUN URLs so the answer can include a public server-reflexive ICE candidate. Leave it empty on a directly reachable public host; each configured STUN server adds connection-setup latency.

## HTTPS and firewall deployment

For production, prefer HTTPS signaling. A reverse proxy can terminate TLS while NEXORA serves HTTP on loopback:

~~~sh
NEXORA_HTTP_BIND=127.0.0.1
NEXORA_UDP_PORT_MIN=30000
NEXORA_UDP_PORT_MAX=30199
~~~

Copy Caddyfile.example to your Caddy configuration and replace example.com with a domain whose DNS points to the VPS. Caddy obtains and renews the TLS certificate automatically.

Open the following ports in the VPS firewall:

- TCP 443 for HTTPS signaling through Caddy (or TCP 19132 for direct HTTP signaling).
- UDP 30000-30199 for WebRTC ICE/DTLS/SCTP traffic.
- UDP 19132 only for the legacy RakNet discovery experiment.

The WebRTC UDP range is configurable with NEXORA_UDP_PORT_MIN and NEXORA_UDP_PORT_MAX. Set both to 0 to use the operating system's default ephemeral range. The HTTP listener bind address is configurable with NEXORA_HTTP_BIND; it defaults to the main bind host.

## Identity key safety

The first initialization of DefaultNetherNetIdentityService creates:

- nethernet-identity.pk8 — private P-384 key; keep it secret and back it up securely.
- nethernet-identity.x509 — matching public key.

Keep both files together and persistent across restarts and server migrations. By default they live in data/identity; set NEXORA_DATA_DIR to a persistent data-volume path to relocate them. If one is missing or the keypair does not match, startup fails rather than silently rotating the server identity.

## Engineering rules

- Keep transport/protocol, world simulation, persistence and gameplay modules separated.
- Keep the server authoritative: clients request actions; the server validates and applies them.
- Start with one inexpensive-hosting profile and explicit limits for players, chunks and entities.
- Treat Nukkit-MOT as a protocol/architecture reference only, not as the NEXORA implementation base.
- Preserve applicable third-party license notices and obligations when studying or reusing external code.
