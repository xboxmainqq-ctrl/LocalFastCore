# NEXORA Bedrock 1.26.60 NetherNet integration plan

## Verified target

- Bedrock 1.26.60 uses protocol 2225; `BedrockProtocolTarget` pins the codec to that protocol.
- The selected transport dependency is CloudburstMC `netty-transport-nethernet`, not a RakNet-only listener.
- NetherNet consists of HTTP(S) signaling over TCP and WebRTC/ICE media over UDP. A codec dependency by itself does not accept a client.
- CloudburstMC's server API is a Netty `ServerBootstrap` with `NetherNetChannelFactory.server(signaling)`, a stable `OperatorIdentity`, and a `NetherNetHTTPServerSignaling` configured with `TokenTrust.MINECRAFT_AUTH`.
- The accepted child channel carries a validated `PlayerInfo`; the Bedrock login chain public key must be checked against the transport identity using `TransportIdentityBinding`. Do not accept a client based only on a self-asserted name or XUID.

## Deployment constraints

For the simplest shared-port deployment, the host must forward **the same public Bedrock port over both TCP and UDP**. TCP is used for HTTP signaling; UDP is used for ICE/WebRTC media. A Java host panel that exposes only a TCP port or only a UDP port is insufficient.

Before enabling NetherNet on MCPEHost, confirm:
1. The allocated port number.
2. Whether the panel forwards both TCP and UDP on that same port.
3. Whether outbound DNS/HTTPS and UDP traffic are allowed from the container.
4. Whether the runtime is Linux glibc or Linux musl (Alpine), and its CPU architecture.
5. If outbound STUN is blocked, the provider's public UDP endpoint and whether it forwards to the allocated game port (configure it with `bedrock.advertised-addresses`).

Do not hardcode 19132: the host-assigned port must be configurable. Do not advertise a private container address as a public ICE candidate. A stable server identity PEM must be preserved between restarts because clients can pin its public key.

## Implementation sequence

1. Add validated network settings for bind address, host-assigned signaling/gameplay port, and optional separate ICE port.
2. Start the NetherNet signaling and media listener with a persistent identity file; fail startup clearly if the native WebRTC library cannot load or the host port cannot bind.
3. Adapt accepted `NetherNetChildChannel` connections into NEXORA's Bedrock session lifecycle. Preserve channel event-loop ownership and close sessions cleanly.
4. Validate the signaling identity against the Bedrock login chain before admitting a player. Retail authentication must remain enabled in production.
5. Negotiate the Bedrock codec, compression, resource-pack and StartGame sequence, then stream chunks and gameplay packets.
6. Test with a real retail Bedrock 1.26.60 client from outside the hosting network. Loopback transport tests alone are not proof of client compatibility.

## Current implementation status

NEXORA now has the Cloudburst NetherNet signaling/media listener, persistent operator identity, retail Minecraft identity validation, Bedrock login verification, and the initial session/chunk pipeline. The listener binds TCP signaling and UDP ICE on the configured port, tries Cloudflare and Google STUN, and supports explicit public ICE endpoints through `bedrock.advertised-addresses` when outbound STUN is unavailable. This is still an early playable implementation: inventory, full survival systems and complete block interaction are not finished. Java 25 CI verifies compilation/tests and JAR assembly only; it is not a real-client connectivity test.

## Upstream API reference

The transport usage and server bootstrap example are documented by CloudburstMC Network's `nethernet` branch:
https://github.com/CloudburstMC/Network/tree/nethernet/transport-nethernet

The NEXORA integration must follow the dependency version pinned in `pom.xml` and re-check API signatures against that exact artifact before implementation.
