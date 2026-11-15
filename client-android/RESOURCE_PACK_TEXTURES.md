# NEXORA Android block textures

The Android client keeps a generated built-in atlas as a fallback. To override
individual block materials without rebuilding the client, place PNG files in
the app's private files directory:

`resourcepacks/active/textures/blocks/`

Supported filenames:

- `grass_top.png`
- `grass_side.png`
- `dirt.png`
- `stone.png`
- `sand.png`
- `gravel.png`
- `log_side.png`
- `log_top.png`
- `leaves.png`
- `water.png`
- `snow.png`
- `bedrock.png`
- `planks.png`
- `clay.png`
- `deepslate.png`

Textures are scaled to 16x16 with nearest-neighbour filtering. Missing, invalid,
or oversized files use the built-in tile instead. Each image is limited to
4 MiB and 1024x1024 pixels.

This is the client-side texture loading foundation. Network delivery, archive
installation, pack selection UI, and live texture reload are separate follow-up
work and are not implied by this directory loader.
