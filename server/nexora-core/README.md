# NEXORA Core

Independent Minecraft Bedrock server implementation. The code is an early development scaffold, not yet a playable Bedrock server.

## Current transport direction: NetherNet

The initial target is modern Minecraft Bedrock clients. NetherNet is the required primary transport for current clients; RakNet is retained only as a legacy protocol experiment.

The modern connection path is:

1. Client calls \`GET /v1/join\` to verify NetherNet support and read server metadata.
2. Client sends its complete WebRTC SDP offer to \`POST /v1/join/{networkId}\`.
3. NEXORA creates a WebRTC peer, validates the client's identity assertion, creates a complete SDP answer, and signs the server identity assertion.
4. Client and server establish ICE, DTLS and SCTP data channels.
5. NEXORA receives Bedrock game batches from the reliable and unreliable data channels.

Official Mojang onboarding guide: https://mojang.github.io/bedrock-protocol-docs/guides/nether-net-onboarding-guide/

## Implemented

- Java 21 / Maven project.
- Legacy RakNet unconnected ping/pong codec and UDP discovery responder (for protocol study; not sufficient for modern clients).
- NetherNet server metadata model and JSON serialization.
- NetherNet HTTP signaling endpoint:
  - \`GET /v1/join\` returns server metadata.
  - \`POST /v1/join/{networkId}\` validates the SDP request and delegates negotiation to an \`SdpNegotiator\`.
  - Request size limit, content-type validation, bounded worker pool, and explicit error responses.
- Unit tests for legacy RakNet discovery and NetherNet HTTP signaling.

## Not implemented yet

- A production WebRTC peer-connection provider / native binding.
- Client GameServerToken and signed fingerprint validation.
- Server operator identity JWT and detached fingerprint signature.
- ICE candidate reachability and deployment port allocation.
- NetherNet reliable/unreliable data-channel framing and fragmentation.
- Bedrock packet batching, compression, encryption/login, resource-pack negotiation, player spawn or gameplay.
- World, entities, inventory or persistence.

The signaling HTTP layer is deliberately separated from WebRTC through \`SdpNegotiator\`. It must not be exposed as a working NetherNet service until a real implementation validates identity assertions, creates a WebRTC answer with reachable ICE candidates, and includes the mandatory server \`a=identity\` assertion.

## Protocol target

Initial stable game target: **Bedrock 1.26.62 / game protocol 2225**. The transport target is NetherNet. Re-check Mojang's official protocol reference before changing either value.

## Legacy RakNet discovery experiment

The current bootstrap still runs the legacy UDP discovery responder on port 19132. This is not a playable server and modern Bedrock clients may not connect through it.

Build:

\`\`\`sh
mvn -f server/nexora-core/pom.xml clean verify
\`\`\`

Run the legacy discovery experiment:

\`\`\`sh
java -jar server/nexora-core/target/nexora-core-0.1.0-SNAPSHOT.jar 0.0.0.0 19132 "NEXORA Core" 20
\`\`\`

## Engineering rules

- Keep transport/protocol, world simulation, persistence and gameplay modules separated.
- Keep the server authoritative: clients request actions; the server validates and applies them.
- Start with one inexpensive-hosting profile and explicit limits for players, chunks and entities.
- Treat Nukkit-MOT as a protocol/architecture reference only, not as the NEXORA implementation base.
- Preserve applicable third-party license notices and obligations when studying or reusing external code.
