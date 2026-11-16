NEXORA DEDICATED SERVER — BEDROCK TEST PREVIEW

Requirements:
- Java 25 (64-bit)
- 2 GB RAM recommended
- A host allocation that exposes BOTH TCP and UDP on the configured Bedrock port

Start:
- Windows: double-click start.bat
- Linux: chmod +x start.sh && ./start.sh

The first start copies the packaged server.properties into nexora-data/server.properties.
The packaged test profile enables authenticated Bedrock 1.26.60 NetherNet ingress on port 19132.
IMPORTANT: On a game host, replace bedrock.port in nexora-data/server.properties with the exact allocated port.
The same port is used for TCP signaling and UDP media when bedrock.ice-port=0. The host must expose both protocols.
If the host assigns a separate UDP media port, set bedrock.ice-port to that allocated UDP port.
Restart after changing settings.

The server creates nexora-data/nethernet-identity.pem on first enabled start. Keep this file between restarts and deployments; it is the server's persistent cryptographic identity.

Authentication:
- Retail Bedrock login with Microsoft/Xbox authentication is required.
- This is not an offline/cracked login mode.
- No client resource-pack prompt or UI result should be assumed until verified in-game.

IMPORTANT LIMITATIONS:
This is an early NEXORA server runtime preview, not a finished public multiplayer server. The authenticated Bedrock listener and post-login pipeline have automated tests, but a retail 1.26.60 client and host-specific TCP/UDP routing still need end-to-end testing. Movement physics, inventory presentation, resource-pack HUD rendering, and full survival gameplay remain incomplete.
