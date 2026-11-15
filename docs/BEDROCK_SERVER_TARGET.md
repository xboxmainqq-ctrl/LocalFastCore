# NEXORA Bedrock Server Target

## Deployment target

NEXORA is being prepared for the MCPEHost **Custom Java Core** slot. The deliverable for this target is one executable JAR named `server.jar`; Windows launchers and a desktop ZIP are not part of this deployment path.

The host panel screenshot lists Bedrock server families including Nukkit and accepts a custom Java core. The JAR must therefore start as a Bedrock server process and use the transport required by the target Bedrock client version. A Java JAR that only starts the current NEXORA runtime is not sufficient.

## Client compatibility target

Initial compatibility target: Minecraft Bedrock / MCPE 1.26.60–1.26.63, matching the version range shown in the user's MCPEHost panel.

**Transport change at 1.26.60:** Mojang's Bedrock 1.26.60 release removed the legacy RakNet client transport in favor of NetherNet. A RakNet-only listener is therefore not sufficient for this target. The primary listener must use NetherNet signaling over HTTP(S) and WebRTC media over UDP. Earlier Bedrock clients may need a separate legacy RakNet listener later, but it is not the first release target.

The 1.26.60 protocol number is 2225. Pin and test the exact codec before claiming support for 1.26.61–1.26.63 as well.

## Network architecture

The current NEXORA development network (WebRTC/NetherNet signaling) is a private NEXORA-client transport. It is not yet a Bedrock NetherNet server implementation and must not be advertised as Bedrock compatibility.

Implementation building blocks:

- CloudburstMC Network `netty-transport-nethernet` (NetherNet transport and server signaling).
- Bedrock protocol codec for protocol 2225 / Minecraft 1.26.60.
- Cloudburst-compatible Bedrock packet/session layer, adapted to the NetherNet Netty channel rather than assuming RakNet.
- Retail Minecraft identity validation. Never admit clients based only on an unauthenticated development offer.
- Translate Bedrock login, player movement, chunk/world data, block interaction, inventory, and disconnect events into NEXORA runtime services.

Upstream references:
- https://github.com/CloudburstMC/Network/tree/nethernet
- https://github.com/CloudburstMC/Protocol
- https://github.com/RyzeMCBE/Protocol

Use upstream Apache-2.0 components according to their licenses. Pin dependency versions and native libdatachannel binaries; do not rely on floating snapshots in a release artifact.

## Integration order

1. Keep the existing NEXORA private-client transport separate.
2. Add the Bedrock 1.26.60 codec and NetherNet transport dependencies; verify reproducible resolution in CI.
3. Implement NetherNet server signaling, persistent server identity, ICE/media binding, and retail Minecraft identity validation.
4. Prove a Bedrock client can discover the server and complete the authenticated transport/login handshake.
5. Add the world adapter that serializes NEXORA chunks into Bedrock chunk/subchunk packets and maps Bedrock block identifiers to NEXORA block states.
6. Add player spawn, movement, collision, block interaction, inventory, and disconnect lifecycle.
7. Run an end-to-end test using a real Bedrock client 1.26.60, then verify 1.26.61–1.26.63 separately.
8. Build one shaded executable `server.jar`, with the correct main class and native dependencies, then test it on MCPEHost.

## Hosting requirements to verify

NetherNet uses both signaling and media networking. The host must expose the required TCP signaling endpoint and UDP media/ICE endpoint(s), and the runtime must advertise reachable public addresses or discover them through STUN. A single custom JAR upload does not guarantee that the host's allocated ports and NAT setup support this. Confirm MCPEHost's allocated TCP/UDP port details before enabling public joins.

## Release gate

Do not label or publish a JAR as MCPEHost-ready until all of these pass:

- JAR starts using the panel's custom Java core option.
- Signaling and UDP media bind to host-assigned reachable ports.
- Bedrock client can discover and complete authenticated login.
- Player appears at spawn and receives generated terrain.
- Movement and collision work.
- Breaking/placing a block works and survives save/restart.
- Server shuts down without corrupting world data.

Until these gates pass, the existing NEXORA standalone JAR is a development runtime, not a Bedrock-compatible server.
