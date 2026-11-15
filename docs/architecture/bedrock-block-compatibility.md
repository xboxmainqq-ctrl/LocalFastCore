# Bedrock block compatibility

## Purpose

NEXORA stores stable, namespaced block identifiers in its own world model.
It must not store Bedrock runtime palette IDs: those IDs are client/protocol
session data and can change with the Bedrock version and negotiated palette.

The BedrockBlockCompatibilityRegistry is the first translation layer. It maps
a world identifier to the Bedrock block identifier a compatible client can
render. The built-in registry covers identifiers currently emitted by the
overworld generator. A custom NEXORA block can map to a vanilla Bedrock block,
for example nexora:mistwood_soil to minecraft:podzol.

## Important limits

- This registry maps names only; it does not yet translate a block into a
  Bedrock runtime palette/network ID.
- It does not download or extract Minecraft assets, textures, or block
  definitions from a user's game installation.
- A Bedrock client may need a resource pack for custom blocks or visual
  variants. Such a pack must use original or appropriately licensed assets.
- Unknown mappings fail explicitly rather than becoming arbitrary palette
  entries.
- Runtime palette resolution belongs in the Bedrock session adapter after the
  target client version and its block palette are known.

## Next integration milestone

Wire this registry into the Bedrock chunk serialization path, resolve each
mapped identifier against the connected client's negotiated block palette,
and reject or deliberately substitute unsupported custom blocks. Add protocol
tests and verify the result with a real Bedrock client.
