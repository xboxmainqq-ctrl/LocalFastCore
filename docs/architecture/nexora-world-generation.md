# NEXORA World Generation

## Runtime path

The standalone server reads `world.id`, `world.generator`, `world.seed` and vertical bounds from `server.properties`. It loads or creates the configured world, resolves the generator from `WorldGeneratorRegistry`, generates the spawn chunk at startup and routes later missing-chunk requests through the bounded asynchronous chunk pipeline. `WorldStorageModule` persists world chunks on clean shutdown. Existing saved chunks are deliberately not regenerated just because the generator changes.

The default fresh-install profile is:

- World ID: `nexora_rpg`
- Generator: `overworld`
- Seed: `20261116`
- Height: Y=-64 through Y=319
- Tick rate: 20

Changing the seed affects newly generated chunks only. To start a completely new map on a host that already has `nexora-data`, stop the server and archive or remove the old world directory under `nexora-data/worlds/` after making a backup, then deploy the new server package and use the new `server.properties`. The repository does not contain a user's live host world files.

## Terrain and climate

The RPG terrain profile is not a Minecraft-style flat noise stack. It models large continental shelves and coastlines first, then overlays eroded lowlands, rolling uplands, foothills, long mountain belts, narrow ridges and broad valleys. Domain warping reduces obvious repeating noise patterns. Mountain relief is controlled by both the biome profile and a separate mountain-belt field, so highlands form coherent regions rather than isolated random spikes. River and lake channels are applied after the landform pass. Surface materials then transition with altitude and climate: cold regions receive earlier alpine snow, warm regions keep a higher snowline, exposed high slopes become rock, and coasts receive sand and gravel beaches. Forests grow in broad seed-stable clusters with natural clearings; a climate-aware tree line keeps the highest ridges and alpine peaks open.


The built-in `overworld` generator uses deterministic world-coordinate fields. Climate sampling is independent of chunk boundaries, so adjacent chunks agree at their borders. Biome selection uses normalized temperature, moisture and elevation; relief is blended across nearby climates to avoid hard terrain-height seams.

Built-in surface biomes:

| Biome | Environment | Surface / vegetation |
|---|---|---|
| Mistwood | Temperate, wet forest | Podzol, oak |
| Glassgrass Steppe | Warm open grassland | Grass, birch |
| Emberfen | Hot, humid marsh | Mud, dark oak |
| Frostveil | Cold forest | Snow, spruce |
| Skyglass Highlands | Cool highlands | Stone, spruce |
| Saltwind Basin | Hot, dry basin | Sand, sparse scrub |
| Bloomveil Grove | Lush, mossy woodland | Moss, cherry trees |
| Ashen Wastes | Hot volcanic badlands | Basalt, tuff |
| Moonfrost Tundra | Very cold, wet uplands | Packed ice, snow, spruce |

The terrain pass also creates river and lake channels, underground caves, lush cave pockets, underground water pools, crystal geodes, ore veins and biome-specific trees. All structures/features are generated from the world seed and absolute coordinates so the same seed and chunk position produce the same result.

## Extension boundary

Trusted server-side generator extensions are loaded from `nexora-data/extensions/generators/` through the versioned NEXORA generator extension API. The included flat generator is an API example, not a bundled production plugin. Extensions execute as Java code in the server process and must only be installed from trusted sources.

## Validation

The server CI workflow runs the Maven verification suite, assembles a standalone server preview package and renders a PNG from the same authoritative chunk generator. The generated preview is diagnostic output, not a replacement for a live host/client test.

Custom biome IDs are NEXORA world data. An unmodified Bedrock client compatibility layer still needs an explicit mapping to the client's supported biome palette and real-client testing before these custom biomes can be advertised as Bedrock-compatible.
