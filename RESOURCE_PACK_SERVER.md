# NEXORA Android server resource packs

The first-party Android client receives Bedrock-style resource-pack ZIP files
through the authenticated NEXORA session. The archive format and texture
directory follow Bedrock resource-pack conventions; the transfer itself uses
the versioned NEXORA session protocol.

## Server folder

By default, place one ZIP in:

```text
nexora-data/resource-packs/
```

If the server is launched with `--data-dir /path/to/data`, use
`/path/to/data/resource-packs/`. The server loads and validates the pack set
at startup. Restart the server after changing an archive.

Each ZIP must contain a root `manifest.json` using the NEXORA pack schema:

```json
{
  "id": "nexora:starter_textures",
  "version": "1.0.0",
  "api_version": "1.0.0",
  "type": "resource"
}
```

Place supported PNG textures under `textures/blocks/`, for example
`textures/blocks/grass_top.png` and `textures/blocks/stone.png`.

## Current support

- One ordinary resource pack per world.
- ZIP archives up to 64 MiB.
- Sequential 1 MiB chunks over the reliable NetherNet channel.
- SHA-256 verification before the Android client installs the archive.
- The server holds initial chunk requests until the client confirms installation.
- Invalid manifests, unsupported pack types, unsafe ZIP paths, and multiple
  configured packs are rejected.

Script packs, add-on packs, ray-tracing packs, encrypted packs, subpacks, CDN
delivery, and multiple stacked packs are not supported yet. The client
installs supported block textures into its private resource-pack library;
the renderer applies the active textures when the world view is refreshed.

## Launching

Enable the existing private-network development transport:

```bash
java -jar nexora-runtime.jar --enable-development-network
```

The pack directory is read from the selected data directory during startup.
Do not expose the unauthenticated development signaling mode to the public
Internet.
