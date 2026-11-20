# MCPEHost deployment notes

This is an initial deployment checklist for the NEXORA Core development build.

## JAR name

The Maven build produces `server/nexora-core/target/server.jar` (the shaded standalone JAR). For the MCPEHost custom Java core option, use the `/server.jar` placement shown by the panel. Do not rename it to `nukkit.jar`.

## Runtime

- Select Java OpenJDK 21.
- Start with a conservative heap setting appropriate to the purchased RAM; leave memory for native WebRTC buffers and the operating system. Do not set `-Xmx` equal to all allocated RAM.
- Keep the existing server files backed up. Do not delete Nukkit files until NEXORA has passed a separate test.

## Networking requirements

NEXORA's current NetherNet transport uses the allocated server port for TCP signaling and a fixed UDP ICE port. A single allocation such as 23472 must be reachable on both TCP and UDP. The server's old Nukkit `server.properties` file is not automatically the configuration source for NEXORA; the process reads NEXORA environment variables.

For the MCPEHost allocation, the intended values are:
- `SERVER_PORT=23472` (or the host's injected port variable)
- `NEXORA_UDP_PORT_MIN=23472`
- `NEXORA_UDP_PORT_MAX=23472`
- `NEXORA_ADVERTISED_HOST=storm.mcpehost.ru`
- `NEXORA_PUBLIC_UDP_PORT=23472`

These values must be supplied through the hosting panel's environment/startup configuration if supported. Do not assume that editing `server.properties` sets them.

## Public connection prerequisites

- Public NetherNet signaling requires HTTPS. If the panel cannot mount a certificate/key pair or provide a TLS reverse proxy, public retail-client connections are not ready.
- The advertised hostname and UDP allocation must actually route to the container. A hostname alone does not provide UDP forwarding or TLS.
- Query/RCON settings from the old Nukkit configuration do not enable those services in NEXORA.

## Validation status

A successful Maven build and startup smoke test do not prove that a retail Minecraft Bedrock client can join. Before inviting players, validate process startup on the host, TCP signaling, public UDP/ICE reachability, HTTPS, authenticated Bedrock login, resource-pack completion, world bootstrap and client initialization.

The current project remains under active development. Treat a first host deployment as an isolated technical test, not a production server.