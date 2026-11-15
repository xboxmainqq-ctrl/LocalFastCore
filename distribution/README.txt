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

IMPORTANT LIMITATIONS:
This is an early NEXORA server runtime preview, not a finished public multiplayer server. The current default is offline: network ingress is disabled. Do not expose development networking to the public internet. The Android client currently renders a limited spawn-area 3D preview; full first-person movement, collision, gameplay interaction, authentication, public matchmaking, and production network security are not complete.
