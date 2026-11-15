# NEXORA Bedrock resource-pack delivery

The Bedrock login pipeline can advertise and transfer validated NEXORA
resource-pack ZIP archives before StartGame. Archives are loaded from a
server-selected directory; the default convention is `resource-packs/`.

## Archive layout

Each ZIP must contain a root `manifest.json` using the NEXORA pack schema:

```json
{
  "id": "nexora:starter_textures",
  "version": "1.0.0",
  "api_version": "1.0.0",
  "type": "resource"
}
```

Place supported Bedrock block PNG files under `textures/blocks/`, for example
`textures/blocks/grass_top.png` and `textures/blocks/stone.png`.

Only `RESOURCE` packs are delivered by this path. Manifests, dependencies,
ZIP paths, entry counts, compressed size, and expanded size are validated
before the archive is retained for transfer. Dependencies are resolved
dependency-first and pack UUIDs are derived deterministically from the
namespaced manifest IDs.

## Wiring the pack set

Load packs once during server startup and pass the resulting immutable list
to the resource-pack-enabled `BedrockWorldSessionPipeline` constructor:

```java
List<BedrockResourcePackTransferCodec.Pack> packs =
    new BedrockResourcePackArchiveLoader().load(
        Path.of("resource-packs"), "1.0.0");

BedrockWorldSessionPipeline pipeline = new BedrockWorldSessionPipeline(
    networkSettings, world, dimension, subChunkLimit, initialChunks,
    blockProfile, blockMappings, packs, true,
    settingsFactory, loginAuthenticator, playMessageHandler);
```

Set the boolean to `false` if packs are optional. The list is snapshotted at
startup; restart/rebuild the pipeline after changing archives.

## Android client scope

The Android client downloads the advertised archive in 1 MiB chunks, verifies
the SHA-256 digest, and installs its supported block textures before accepting
the pack stack. The current renderer supports one ordinary, unencrypted
resource pack at a time. Script packs, add-on packs, ray-tracing packs,
subpacks, CDN delivery, and multiple stacked packs are not supported yet.
Unsupported pack metadata is rejected rather than silently ignored.
