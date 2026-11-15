# NEXORA Minecraft compatibility and client audit

Status: repository inspection after the product-direction change. This is a source-code audit, not a claim that the listed flows work end-to-end.

## Confirmed foundations

### Server and world

- Java 21 Maven server runtime with lifecycle-managed modules and a tick loop.
- World registry, world-height contract, block states, sparse chunks, biome storage, chunk generation and file-based world persistence.
- Asynchronous chunk generation is bounded and commits generated chunks on game ticks.
- Entity registry and entity simulation module exist.
- Player inventory model and file-backed inventory storage exist.
- RPG class registry and XP progression are implemented as an isolated domain foundation.

### Bedrock-related implementation

- The repository contains selected Bedrock 1.26.60 / protocol 2225 codecs and handshake/post-login classes.
- Resource-pack negotiation, StartGame, LevelChunk and SubChunk-related codecs/handlers are present.
- BedrockWorldSessionPipeline composes a Bedrock login/world-startup pipeline.
- These classes are partial protocol implementation; they do not prove complete Bedrock server compatibility.

### Current Android client

- MainActivity is a native Android Java activity with a hard-coded home screen and navigation.
- ChunkPreviewView uses OpenGL ES 2.0 and can render received voxel chunk snapshots with camera interaction.
- The current preview is not a complete Minecraft-style playable client.
- The home-screen buttons use programmatic styling; there is no data-driven theme/layout editor or Visual Studio yet.

## Critical integration gap

NexoraMain.createRuntime currently registers the development network path using WebRtcJavaNetherNetPeerHost, HTTP signaling and NexoraPrivateProtocolHandler. That is the private NEXORA protocol path.

The Bedrock pipeline (BedrockWorldSessionPipeline / BedrockInitialTransportRouter) exists in the source tree but is not wired into the normal NexoraMain launch path inspected for this audit. Therefore, the current launcher must not be described as accepting ordinary Minecraft Bedrock clients.

The current private protocol is also not a substitute for Bedrock compatibility. A real compatibility milestone needs a transport and complete protocol flow accepted by the pinned target Bedrock client, then verified with an actual client connection.

## First-party client gap

The Android app currently contains a home screen, server connection flow, world preview and some local preview/player UI code. It does not yet have the complete gameplay loop required for an RPG server client:

- authenticated player session and reliable reconnect;
- continuous chunk streaming over a useful view distance;
- server-authoritative movement, collision and interaction;
- complete inventory/equipment/gameplay screens;
- data-driven UI widgets and theme/resource loading;
- Visual Studio editor with preview, validation, save and reload.

## Immediate engineering priorities

1. Keep the domain model transport-neutral; do not put Bedrock packet fields directly into world or RPG rules.
2. Audit the exact Bedrock transport requirements for the pinned target version and decide the transport adapter before adding more packet codecs.
3. Wire one authenticated session lifecycle into world/player state, then add movement and chunk streaming with bounded server-side validation.
4. Establish a real-client acceptance test for Bedrock compatibility before advertising it.
5. Separately evolve the NEXORA Android client into a playable first-party client.
6. Build the UI foundation in layers: design tokens/theme -> widget and layout schema -> resource pack loader -> live preview -> Visual Studio editor.
7. Keep creator tools permission-checked and server-authoritative. Editing a HUD must never grant gameplay permissions or mutate server state.

## Product constraints

- Standard Bedrock clients can only render the UI and assets supported by their protocol/resource-pack capabilities. Arbitrary custom widgets, gradients, layouts and editor screens are first-party NEXORA Client capabilities.
- Use original or appropriately licensed assets. Do not copy Minecraft source or bundle proprietary game assets.
- Keep unauthenticated development networking restricted to trusted test environments.
