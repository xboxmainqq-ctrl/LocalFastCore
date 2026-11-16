# Bedrock 1.26.60 NetherNet runtime notes

## Status

The `nexora/compatibility-edition` branch now contains an opt-in Bedrock ingress based on CloudburstMC's NetherNet transport. It binds the TCP HTTP signaling endpoint and the UDP WebRTC media endpoint, then adapts authenticated NetherNet child channels to NEXORA's transport-neutral Bedrock session router.

This is an implementation milestone, **not a compatibility certification**. A real retail Bedrock 1.26.60 client still has to be tested against the complete flow on the actual hosting provider.

## Enable

Example startup command:

```bash
java -jar target/nexora-runtime-0.1.0-SNAPSHOT.jar \
  --data-dir ./nexora-data \
  --enable-bedrock-network \
  --bedrock-bind-address 0.0.0.0 \
  --bedrock-port 19132 \
  --bedrock-ice-port 0
```

- `--bedrock-port` is the TCP signaling port and, when ICE port is 0, the UDP media port too.
- `--bedrock-ice-port 0` enables shared-port operation. Set a different nonzero UDP port only when the hosting provider allocates it separately.
- `--bedrock-identity-file` defaults to `nethernet-identity.pem`, relative to the data directory.
- Bedrock networking is disabled unless `--enable-bedrock-network` is supplied.
- The legacy unauthenticated development listener is a separate feature and must not be exposed publicly.

## Hosting and firewall requirements

The hosting provider must forward **TCP and UDP** for the configured port. NetherNet is not a UDP-only RakNet listener: the client first uses HTTP signaling over TCP and then carries gameplay over WebRTC/UDP. If the panel exposes only TCP or only UDP, retail clients will not be able to complete the connection.

STUN discovery is configured to help the server announce a reachable public media candidate. It does not bypass a host firewall or a provider that blocks UDP. A real connection test from outside the hosting network is mandatory.

The runtime includes a persistent P-384 operator identity. Preserve `nethernet-identity.pem` when updating the JAR or moving the server. Deleting it rotates the server identity and can invalidate client trust-on-first-use pins.

## Authentication and session path

1. Cloudburst's NetherNet signaling validates the Minecraft-issued identity assertion and its SDP fingerprint binding using `TokenTrust.MINECRAFT_AUTH`.
2. The accepted child channel exposes the validated player claims and client public key.
3. The Bedrock Login multiplayer token is independently verified against the Minecraft authorization JWKS.
4. The client-data JWT is verified with the key in that login token.
5. The router compares the Bedrock Login identity (XUID, gamertag and P-384 key) with the identity authenticated by NetherNet. A mismatch closes the connection.
6. The protocol-2225 pipeline sends NetworkSettings, LoginSuccess, resource-pack negotiation, StartGame and initial spawn chunks. SubChunkRequest responses use the current block compatibility registry.

The transport adapter preserves reliable-ordered versus unreliable message delivery. Basic PlayerAuthInput movement tracking, bounded movement correction, player entity registration and radius-based chunk streaming are wired. Movement now passes through a conservative swept player AABB resolver: non-air blocks are treated as full solid cubes, unloaded chunks are solid boundaries, horizontal motion can slide along walls, and floor/ceiling collisions produce position corrections. This is deliberately an early collision layer, not full physics: gravity, jumping, step-up, block-specific collision shapes, block breaking/placing, inventory and entity replication remain incomplete. The server is not yet a complete playable Bedrock server.

## Verification gates

- [ ] Java 25 Maven package and unit tests pass in GitHub Actions.
- [ ] The packaged JAR starts on the actual MCPEHost runtime and loads the Linux native WebRTC dependency.
- [ ] TCP signaling and UDP media bind to the provider-assigned port(s).
- [ ] An unmodified Bedrock 1.26.60 retail client appears in server logs and completes NetherNet negotiation.
- [ ] Invalid or mismatched signaling/Login identities are rejected.
- [ ] The client receives NetworkSettings, LoginSuccess, StartGame and initial chunks without disconnecting.
- [ ] The client can move, request chunks, break/place blocks and reconnect after a restart with the same identity file.

Passing unit tests or GitHub Actions alone does not satisfy the real-client gates above.
