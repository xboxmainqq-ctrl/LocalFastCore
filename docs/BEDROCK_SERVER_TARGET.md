# NEXORA Bedrock Server Target

## Deployment target

NEXORA is being prepared for the MCPEHost **Custom Java Core** slot. The deliverable for this target is one executable JAR named `server.jar`; Windows launchers and a desktop ZIP are not part of this deployment path.

The host's panel screenshot lists Bedrock server families including Nukkit and accepts a custom Java core. The JAR must therefore start as a Bedrock server process and bind the host-provided UDP port. A Java JAR that only starts the current NEXORA runtime is not sufficient.

## Client compatibility target

Initial compatibility target: Minecraft Bedrock / MCPE 1.26.60–1.26.63, matching the version range shown in the user's MCPEHost panel. Compatibility must be proven by a real Bedrock client login test, not inferred from a successful Java compilation.

## Network architecture

The current NEXORA development network (WebRTC/NetherNet signaling) is a private NEXORA-client transport. It is not the Bedrock RakNet transport and must not be advertised as Bedrock compatibility.

Implement a separate Bedrock ingress based on the Cloudburst ecosystem:

- CloudburstMC Network `netty-transport-raknet` for UDP/RakNet transport.
- CloudburstMC Protocol `bedrock-connection` for Bedrock packet framing, codecs, and connection handling.
- Bedrock login/session flow with authenticated identity handling; do not accept unauthenticated production sessions.
- Translate Bedrock player movement, chunk/world data, block interaction, and disconnect events into NEXORA runtime services.

Upstream references:
- https://github.com/CloudburstMC/Network
- https://github.com/CloudburstMC/Protocol

Use the upstream Apache-2.0 components according to their licenses. Pin dependency versions once a reproducible build has verified the required Bedrock protocol version; avoid relying on a floating snapshot in a release artifact.

## Integration order

1. Add a dedicated Bedrock transport module without changing the existing WebRTC development transport.
2. Prove RakNet UDP discovery and Bedrock network handshake in automated tests.
3. Implement the Bedrock login sequence and reject unsupported protocol versions cleanly.
4. Add a world adapter that serializes NEXORA chunks into Bedrock chunk/subchunk packets and maps Bedrock block identifiers to NEXORA block states.
5. Add player spawn, movement, collision, block interaction, inventory, and disconnect lifecycle.
6. Add an end-to-end test using a real Bedrock client version 1.26.60–1.26.63.
7. Build a single shaded executable `server.jar`, with the correct main class and all required runtime dependencies, then test it on MCPEHost.

## Release gate

Do not label or publish a JAR as MCPEHost-ready until all of these pass:

- JAR starts using the panel's custom Java core option.
- Server binds the allocated Bedrock UDP port (not an assumed hard-coded port).
- Bedrock client can discover and complete login.
- Player appears at spawn and receives generated terrain.
- Movement and collision work.
- Breaking/placing a block works and survives save/restart.
- Server shuts down without corrupting world data.

Until these gates pass, the existing NEXORA standalone JAR is a development runtime, not a Bedrock-compatible server.
