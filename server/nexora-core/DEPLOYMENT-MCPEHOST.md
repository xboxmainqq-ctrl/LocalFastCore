# MCPEHost deployment notes

This checklist is for the NEXORA Core standalone JAR on MCPEHost.

## JAR and Java

- Build the latest `main` branch from this repository. The Maven output is `server/nexora-core/target/server.jar`.
- Upload that newly built file as `server.jar` to the server root in MCPEHost. An older JAR can still contain the old `19132` default even when the source code has already been corrected.
- Select Java OpenJDK 21.
- Keep the existing server files backed up. Do not delete Nukkit files until NEXORA has passed a separate client test.

## This deployment's network defaults

The current NEXORA source already has MCPEHost-friendly defaults, so you do **not** need to create environment variables just to use this allocation:

- TCP NetherNet signaling port: `23472` by default.
- UDP ICE port range: `23472-23472` by default.
- Advertised host: `nexor.mcbe.in` by default.
- Advertised public UDP port: `23472` by default (same as the fixed ICE port).

The defaults are implemented in `NexoraServerConfig` and `CloudburstNetherNetConfiguration`. Explicit command-line arguments or environment values such as `NEXORA_PORT`, `SERVER_PORT`, `PORT`, `NEXORA_UDP_PORT_MIN`, `NEXORA_UDP_PORT_MAX`, or `NEXORA_ADVERTISED_HOST` can override them. If the startup command explicitly passes `19132`, that old argument must be removed or changed; a default cannot override an explicit argument.

In the MCPEHost allocation settings, confirm that port `23472` is assigned to this server for both TCP and UDP. The hostname must resolve to the host endpoint assigned to this server. A DNS name alone does not provide UDP forwarding.

## HTTPS is still required for public clients

The current built-in signaling listener uses plain HTTP unless both HTTPS certificate and private-key files are configured. Public Bedrock NetherNet clients require HTTPS. If MCPEHost does not provide a TLS reverse proxy or a way to mount a certificate and key for this process, the server may listen on the correct port and still reject/fail public client connections. Do not treat a successful startup as proof that this requirement is solved.

## Validation after uploading the new JAR

1. Stop the server and back up the existing `server.jar`.
2. Upload the freshly built `server.jar` from the latest `main` branch.
3. Start the server and inspect the console.
4. Confirm the log says signaling is listening on port `23472`, and the ICE range is `23472-23472` (not `19132`).
5. Check the HTTPS warning and public UDP reachability separately.
6. Test with a real Bedrock client and confirm it reaches the world.

A successful Maven build or open TCP port alone does not prove that a Bedrock client can join. Keep the previous server files backed up until a real client completes login and world initialization.

## Diagnostic messages

- `NetherNet signaling listening ... (plain HTTP)`: no built-in TLS certificate is configured.
- `no public ICE address or STUN server is configured`: public ICE addressing was not configured in the running JAR; check that the latest JAR was uploaded and that no startup overrides are set.
- `NetherNet peer established`: signaling and initial peer activation succeeded.
- `first Bedrock data received`: the WebRTC data channel delivered data to NEXORA. If login still fails after this, inspect the following Bedrock protocol/login messages.
