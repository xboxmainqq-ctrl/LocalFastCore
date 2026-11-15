# NEXORA Android block textures

## Importing a texture pack

In the Android client, open **РЕСУРС-ПАКИ** and choose a ZIP file. The ZIP
can use NEXORA/Bedrock-style paths or standard Java resource-pack paths.
A ZIP root folder is also accepted. Examples:

- `textures/blocks/grass_top.png`
- `MyPack/assets/minecraft/textures/block/grass_block_top.png`
- `assets/minecraft/textures/block/oak_log.png`

Supported names:

- `grass_top.png`, `grass_side.png`
- `dirt.png`, `stone.png`, `sand.png`, `gravel.png`
- `log_side.png`, `log_top.png`, `leaves.png`
- `water.png`, `snow.png`, `bedrock.png`
- `planks.png`, `clay.png`, `deepslate.png`

The importer maps common Java names (grass block top/side, wood logs, leaves, planks and water) to NEXORA atlas slots, and extracts only supported PNGs. It rejects traversal paths,
unsupported paths are ignored, repeated atlas aliases keep the first ZIP entry, invalid PNG files, images larger than 1024x1024,
individual files over 4 MiB, archives with more than 128 entries, and total
extracted texture data over 32 MiB. Installation is staged before replacing
the active pack. If a texture is missing, the generated built-in atlas tile
remains in use.

Textures are scaled to 16x16 with nearest-neighbour filtering. Reopen the
world preview after importing so the renderer creates its atlas from the
new active pack.

## Current scope

The Android client keeps each imported pack in a private local library. Open **РЕСУРС-ПАКИ** to import another ZIP, choose an installed pack as active, or delete a pack. The active pack is copied into the renderer's active texture folder; reopen the world preview after switching or importing. Deleting the active pack returns the renderer to its generated built-in textures.

Server delivery, automatic pack negotiation, and live reload while a world is already open are not implemented yet.
