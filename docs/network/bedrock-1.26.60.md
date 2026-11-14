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
- How transport identity/session metadata is bound to Bedrock login and encryption in a real-client exchange.
- Android client compatibility and any platform-specific constraints.
- Which packet schema changes among the 64 changes affect the minimum playable login/world/chunk/movement path; these must be audited individually against the 1.26.60 reference before implementing those packet serializers.

No packet bytes, handshake messages, encryption behavior, or transport guarantees are to be invented to fill these gaps.

## Current implementation checkpoint — Login envelope

The standalone runtime currently has bounded Login packet decoding and a structural parser for the Connection Request JSON envelope:

- Extracts the `chain` array and `clientData` compact JWT string.
- Bounds the JSON input, JWT string lengths, chain count, parser nesting depth (64), and rejects duplicate JSON keys and trailing JSON content.
- Checks only the compact three-segment JWT shape and Base64URL character set.
- Exposes the parsed envelope through `BedrockLoginPacket.parseConnectionRequest()`.

**Security boundary:** this is parsing only. It does not verify JWT signatures, certificate-chain trust, issuer/audience/expiry claims, Xbox identity, or player identity. It must never be used as proof that a player is authenticated. Those checks require a separate verifier against the correct Mojang trust material and must be tested with genuine client login exchanges.

### Server encryption handshake primitive

A first cryptographic building block now exists in `BedrockServerEncryptionHandshake`:

- Requires an EC server key pair and an authenticated P-384 client identity public key.
- Creates an ES384 compact JWT with the server public key in `x5u` and a random salt claim.
- Derives the 256-bit session key as SHA-256(salt || ECDH-secret) using P-384 ECDH.
- Derives 256-bit key material as SHA-256(salt || ECDH-secret), using P-384 ECDH.
- Includes unit tests for JWT signature, client/server key agreement, and key size.

The protocol-2225 `PlayStatus` values and 4-byte big-endian packet codec are also implemented and covered by tests. The codec is not yet connected to the router's login completion path.
- Deliberately does **not** select or expose an application cipher. Earlier CFB8 assumptions were removed rather than treating an unverified cipher mode as protocol fact.

**Transport boundary:** NEXORA targets NetherNet. Mojang's onboarding guide specifies WebRTC data channels, which are protected by DTLS. The existence of the legacy Bedrock packet 3/4 schemas does not by itself prove that this extra application-layer handshake is required over NetherNet. NEXORA must follow the NetherNet path and confirm the exact login exchange against an unmodified 1.26.60 client; do not force a legacy cipher onto the DTLS-protected channel.

**Current router checkpoint:** the boolean callback has been replaced with a typed `BedrockAuthenticatedPlayer` result, which must contain a verified identity and its P-384 client key. After the authenticator accepts the Login, the router sends the compressed `PlayStatus(LoginSuccess)` packet and only then transitions to PLAY and notifies the play listener. Tests cover the successful path and rejection path.

**Still not complete:** there is no production Bedrock Login authenticator yet. The router contract is now ready for one, but no certificate/JWT trust chain is accepted by default, and the cryptographic key-derivation primitive is not wired into packet flow. NetherNet DTLS remains the transport security layer; the exact packet 3/4 behavior must be confirmed with an unmodified 1.26.60 client before any application-layer handshake is added. Unit tests do not establish playable compatibility.

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
