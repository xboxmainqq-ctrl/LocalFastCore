# NEXORA Android block textures

## Importing a texture pack

In the Android client, open **РЕСУРС-ПАКИ** and choose a ZIP file. The ZIP
must contain the supported PNG files directly under:

`textures/blocks/`

Example:
`textures/blocks/grass_top.png`

Supported names:

- `grass_top.png`, `grass_side.png`
- `dirt.png`, `stone.png`, `sand.png`, `gravel.png`
- `log_side.png`, `log_top.png`, `leaves.png`
- `water.png`, `snow.png`, `bedrock.png`
- `planks.png`, `clay.png`, `deepslate.png`

The installer extracts only these fixed names. It rejects traversal paths,
duplicate texture names, invalid PNG files, images larger than 1024x1024,
individual files over 4 MiB, archives with more than 128 entries, and total
extracted texture data over 32 MiB. Installation is staged before replacing
the active pack. If a texture is missing, the generated built-in atlas tile
remains in use.

Textures are scaled to 16x16 with nearest-neighbour filtering. Reopen the
world preview after importing so the renderer creates its atlas from the
new active pack.

## Current scope

This client stage supports manual ZIP import and local texture replacement.
Server delivery, automatic pack negotiation, pack selection/history, and
live reload while a world is already open are not implemented yet.
