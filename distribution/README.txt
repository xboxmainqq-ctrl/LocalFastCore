NEXORA DEDICATED SERVER — PREVIEW

Requirements:
- Java 25 (64-bit)
- 2 GB RAM recommended for this preview

Start:
- Windows: double-click start.bat
- Linux: chmod +x start.sh && ./start.sh

The first start creates nexora-data/server.properties if it does not exist and generates the spawn chunk. World data is saved under nexora-data/worlds when the server shuts down. Stop with Ctrl+C (Linux) or close the console after shutdown begins.

Configuration:
Edit server.properties before starting. world.generator=overworld selects the built-in terrain generator. The seed and vertical world limits are also configurable. Unknown generator IDs stop startup with an error.

Optional Bedrock 1.26.60 networking:
- Network ingress is disabled by default. This is intentional; the server remains in safe offline mode until explicitly configured.
- To enable the authenticated NetherNet listener, add these entries to nexora-data/server.properties:
  bedrock.network.enabled=true
  bedrock.bind-address=0.0.0.0
  bedrock.port=19132
  bedrock.ice-port=0
  bedrock.identity-file=nethernet-identity.pem
- Replace 19132 with the TCP signaling port assigned by your host. If your host assigns a separate UDP media port, set bedrock.ice-port to that UDP port. The signaling TCP port and media UDP port must both be reachable from clients.
- On MCPEHost, create/enable the required additional UDP port in the hosting panel and confirm that the signaling TCP port is also exposed. Do not assume that an assigned TCP-only port can carry NetherNet gameplay.
- Restart the server after changing these settings. The first enabled start creates the persistent operator identity file; keep it private and do not delete it between restarts.
- Do not enable the listener until the host's TCP/UDP port allocation is confirmed. Public deployment still needs external connection testing and operational security review.

IMPORTANT LIMITATIONS:
This is an early NEXORA server runtime preview, not a finished public multiplayer server. The Bedrock 1.26.60 authenticated NetherNet listener and post-login gameplay pipeline are implemented and covered by automated tests, but successful connection from a real external Bedrock client and host-specific TCP/UDP routing have not yet been verified. Movement physics, inventory presentation, resource-pack HUD rendering, and full survival gameplay still require in-game validation.
