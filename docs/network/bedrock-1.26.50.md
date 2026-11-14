# NEXORA Bedrock 1.26.50 network target

Status: protocol target decision and research baseline. This document does not claim that a working Bedrock transport or playable server exists.

## Target

- Minecraft Bedrock Edition: **1.26.50**.
- Bedrock protocol version: **2193**.
- Primary dedicated-server transport: **NetherNet**.
- RakNet: not the primary transport for this target. Any future RakNet support must be an explicitly scoped legacy adapter and must not leak into the protocol/gameplay layers.

The official 1.26.50 changelog states that NetherNet is the default transport used by dedicated servers. The official protocol changelog lists protocol 2193 for 1.26.50 and 1.26.51. The Mojang protocol reference page for 1.26.50 is currently labelled 1.26.50-beta.0 but documents protocol 2193; use the stable changelog as the version/protocol pin and treat the beta-labelled page as schema reference only until its metadata is reconciled.

## Source of truth

1. Mojang official Bedrock 1.26.50 changelog:
   https://www.minecraft.net/en-us/article/minecraft--bedrock-edition-26-50-changelog
   - Confirms NetherNet is the default transport for dedicated servers.
2. Mojang official protocol reference, 1.26.50:
   https://mojang.github.io/bedrock-protocol-docs/1.26.50/
   - Packet schemas, supporting types, primitives and protocol version 2193.
   - Page metadata currently says 1.26.50-beta.0; do not silently treat that label as stable release metadata.
3. Mojang official protocol changelog:
   https://mojang.github.io/bedrock-protocol-docs/changelog/
   - Records protocol 2193 for 1.26.50 and 1.26.51, and packet schema changes by release.

## Architecture rules

- Keep transport, Bedrock session/protocol, gameplay, world, and persistence as separate layers.
- The game runtime must depend on a transport-neutral connection contract, not on NetherNet implementation classes.
- The transport adapter owns connection establishment, reliability/ordering semantics exposed by its API, close/error propagation, and transport diagnostics.
- The Bedrock protocol layer owns packet framing/serialization, compression/encryption negotiation, login/session state, and packet-version selection.
- Do not assume NetherNet is interchangeable with generic WebRTC. Its exact signaling, channel setup, framing, and platform requirements must be established from authoritative implementation/specification evidence before implementation.
- Do not copy packet IDs or schemas from a different protocol version. Pin schema inputs to protocol 2193 and add version-specific tests.
- Keep RakNet-specific code out of shared gameplay and packet model classes.

## Verified facts vs open questions

### Verified

- 1.26.50 dedicated servers use NetherNet as their default transport, per Mojang's official release notes.
- The official protocol changelog assigns protocol 2193 to 1.26.50 and 1.26.51.
- The official 1.26.50 protocol reference enumerates packet schemas, including Login, NetworkSettings, StartGame, LevelChunk, PlayerAuthInput and related packet types.

### Open questions — must be answered before claiming transport support

- Exact NetherNet wire protocol and server-side implementation contract.
- Signaling/discovery and connection establishment requirements for a standalone third-party server.
- Data-channel configuration, message boundaries, reliability and ordering guarantees.
- How transport identity/session metadata is bound to Bedrock login and encryption.
- Android client compatibility and any platform-specific constraints.
- Whether Mojang publishes a stable normative NetherNet specification or whether a compatible implementation must be derived from a maintained reference implementation and tested against real clients.

No packet bytes, handshake messages, encryption behavior, or transport guarantees are to be invented to fill these gaps.

## Acceptance gates

A transport implementation is not considered supported until all of these are demonstrated against an unmodified Bedrock 1.26.50 client:

1. A standalone NEXORA process binds/listens using the required NetherNet mechanism.
2. A real client establishes a transport connection.
3. Bedrock protocol negotiation reaches the expected protocol 2193 path.
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
4. Add/update version-specific serialization tests.
5. Re-run transport and login integration tests against that exact client version.
6. Change the supported-version claim only after the acceptance gates pass.


## NetherNet onboarding details (Mojang partner guide)

Reference: https://github.com/Mojang/bedrock-protocol-docs/blob/main/additional_docs/NetherNetOnboardingGuide.md

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

1. NEXORA needs a signaling HTTP endpoint and a WebRTC host/peer implementation; an abstract UDP socket or generic RakNet listener is not sufficient.
2. Signaling and the game-data transport are separate concerns and should have separate lifecycle/metrics.
3. Authentication and operator identity are transport-adjacent security requirements. Do not mark a connection authenticated merely because the WebRTC data channel opened.
4. Reliable and unreliable delivery must remain distinct all the way to packet scheduling; do not silently send latency-sensitive movement traffic through the reliable queue.
5. Fragment reassembly must be bounded (maximum in-flight messages, maximum assembled bytes, and timeout) to prevent memory exhaustion.
6. The guide's example metadata contains a sample protocol number that does not match the stable 1.26.50 protocol pin. NEXORA must advertise the pinned stable value **2193**, not copy the illustrative value from the guide.

These are protocol requirements from the Mojang partner onboarding guide, not proof that NEXORA has implemented them. The implementation remains pending.
