# Bedrock block compatibility

## Purpose

NEXORA stores stable, namespaced block identifiers in its own world model.
Bedrock runtime palette IDs are not stored in worlds: they belong to the exact
Bedrock protocol/version and the session's selected ID mode.

The compatibility registry translates a NEXORA world identifier into the
Bedrock block state used by the existing palette serializer. The serializer
then resolves that state through the version-pinned
`BedrockRuntimeBlockRegistryProfile`.

## Administrator mappings

Custom aliases can be loaded with
`BedrockBlockCompatibilityRegistryFile.loadWithBuiltIns(InputStream)`.
The loader starts with the built-in mappings and lets explicit file entries
override them. Pass the resulting registry to the custom
`BedrockWorldSessionPipeline` constructor.

UTF-8 TSV format:

```text
NEXORA-BEDROCK-BLOCK-MAPPINGS    1
# world identifier<TAB>Bedrock identifier<TAB>resource pack required
nexora:mistwood_soil    minecraft:podzol    false
nexora:glow_crystal     nexora:glow_crystal true
```

The separators between fields must be literal TAB characters. The third field
is exactly `true` or `false`. `true` documents that the mapped block requires
a resource pack; it does not install or distribute that pack.

## Important limits

- A custom block mapped to a vanilla identifier is rendered using the
  Bedrock client's corresponding built-in block appearance.
- A custom Bedrock identifier requires a matching client resource pack and a
  valid version-specific block-state palette entry. A mapping alone cannot
  create a block definition on the client.
- The loader does not download Minecraft assets, textures, or block
  definitions. Use original or appropriately licensed assets.
- Unknown world identifiers fail explicitly rather than becoming arbitrary
  palette entries.
- Runtime IDs are never guessed from names. The selected profile must match
  the Bedrock protocol and StartGame ID mode.

## Verification status

The mapping-to-palette adapter and TSV loader have unit tests. Full Bedrock
client acceptance (login, StartGame, chunk requests, and rendered custom
aliases) remains a separate integration test and is not yet claimed complete.
