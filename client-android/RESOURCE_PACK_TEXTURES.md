# NEXORA Android block textures

## Importing a Bedrock resource pack

In the Android client, open **РЕСУРС-ПАКИ** and choose a Bedrock resource-pack
ZIP file. The importer reads supported PNG textures from `textures/blocks/`;
a ZIP root folder is also accepted.

Examples:

- `textures/blocks/grass_top.png`
- `MyBedrockPack/textures/blocks/stone.png`
- `textures/blocks/log_oak.png`
- `textures/blocks/planks_oak.png`

Only Bedrock-style paths are supported. Java Edition's
`assets/minecraft/textures/block/` layout is intentionally not supported.

Supported NEXORA atlas slots:

- `grass_top.png`, `grass_side.png`
- `dirt.png`, `stone.png`, `sand.png`, `gravel.png`
- `log_side.png`, `log_top.png`, `leaves.png`
- `water.png`, `snow.png`, `bedrock.png`
- `planks.png`, `clay.png`, `deepslate.png`

Common Bedrock names such as `log_oak.png`, `log_oak_top.png`,
`leaves_oak.png`, `water_still.png` and `planks_oak.png` are mapped to
the matching NEXORA atlas slots. Only allow-listed PNG textures are extracted.
The importer rejects traversal and unsafe paths, invalid PNG files, images larger
than 1024x1024, selected textures over 4 MiB, individual ZIP entries over
16 MiB, archives with more than 4096 entries, and total expanded archive data
over 128 MiB. Every entry is streamed through the limits, including files not
used by the renderer. Unsupported paths are ignored; if multiple files map to
one atlas slot, the first ZIP entry is kept. Installation is staged before
replacing the active pack.

Textures are scaled to 16x16 with nearest-neighbour filtering. Reopen the
world preview after importing so the renderer creates its atlas from the
new active pack. Missing textures use generated built-in atlas tiles.

## Server delivery

The standalone NEXORA runtime can load one server resource-pack ZIP from
`<data-directory>/resource-packs/` and transfer it to the first-party Android
client over the authenticated NEXORA session. The archive must use the NEXORA
resource-pack manifest schema documented in
[`RESOURCE_PACK_SERVER.md`](../RESOURCE_PACK_SERVER.md). The client verifies
the SHA-256 digest before installing the pack. Restart the server after
changing the ZIP.

This is separate from the retail Minecraft Bedrock login pipeline: the current
Bedrock 1.26.60 pipeline sends the bundled NEXORA HUD pack and does not yet load
custom ZIPs from the server's `resource-packs/` directory. Do not assume that
a pack configured for the first-party Android client is automatically sent to
a retail Bedrock client.

## Current scope

The Android client keeps imported packs in a private local library. Open
**РЕСУРС-ПАКИ** to import another ZIP, choose an installed pack as active, or
delete a pack. The active pack is copied into the renderer's active texture
folder; reopen the world preview after switching or importing. Deleting the
active pack returns the renderer to its generated built-in textures.

Only block-atlas textures listed above are applied by the current renderer.
Custom geometry, entity models, animation controllers, scripts, add-ons,
ray-tracing features, live reload while a world is open, and multiple stacked
server packs are not implemented yet.
