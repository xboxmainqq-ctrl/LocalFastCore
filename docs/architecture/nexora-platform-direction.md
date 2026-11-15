# NEXORA platform direction

Status: product decision. This document supersedes earlier architecture statements that described NEXORA as a standalone non-Minecraft MMORPG.

## Product vision

NEXORA is a Minecraft-style multiplayer platform, inspired by large custom server networks: a block-based world, one central platform, multiple game modes, a custom server core, and a first-party Android client. The initial flagship experience is the RPG server the project originally set out to build.

The goal is not to create a completely unrelated fantasy game. The goal is to build a highly extensible Minecraft-based platform where the server owner can configure and create content through tools inside the NEXORA Client.

## Product components

1. **NEXORA Core** — authoritative server runtime for worlds, dimensions, chunks, blocks, block entities, entities, players, inventory, permissions, commands, events, persistence and game modes.
2. **NEXORA Client** — first-party Android client with block-world rendering, camera, touch controls, connection flow, settings and custom presentation.
3. **NEXORA Bedrock Compatibility** — a separate adapter for supported unmodified Minecraft Bedrock clients. Compatibility is a tested milestone, not an assumption. Do not label the current private NEXORA protocol as Bedrock-compatible.
4. **NEXORA Extension Protocol** — versioned messages for capabilities unique to the first-party client, such as editor operations, richer UI definitions and platform metadata. Server authority and validation still apply.
5. **NEXORA Visual Studio** — in-client visual editor for screens, buttons, panels, gradients, images, fonts, borders, shapes, animations, HUD elements, anchors and responsive layouts.
6. **NEXORA World Studio** — in-client world, map, region and block editing tools.
7. **NEXORA RPG Studio** — tools for classes, stats, abilities, items, mobs, quests, loot and progression.
8. **NEXORA Server Studio** — tools for game modes, permissions, commands, server settings, diagnostics and content deployment.

## Core design rule

The server owns authoritative state and game rules. The client owns presentation and editing UX. A UI definition can request a typed gameplay action, but it cannot directly grant items, award XP, move players or change world state. The server validates every gameplay command and editor permission.

Vanilla Bedrock clients can only display features supported by the Bedrock client protocol. Fully custom buttons, gradients, arbitrary images, layouts and HUD widgets require the NEXORA Client (or a separately supported resource-pack/UI mechanism with its own limitations). Do not promise identical custom UI on unmodified clients.

## Engine decision

Luanti was considered and explicitly rejected by the user on 2026-11-15. Do not base the NEXORA product on Luanti or replace NEXORA's server core with it. Use Minecraft Bedrock as a gameplay and protocol reference, implementing NEXORA independently with clean-room code and original/licensed assets.

## Compatibility strategy

- Keep the game domain independent of any one network transport.
- Treat Bedrock protocol support as an adapter, not as the internal game model.
- Keep NEXORA-only extension messages separately versioned.
- Reuse the existing world, chunk, tick, registry and persistence foundations where they fit, after auditing them against Bedrock semantics.
- Do not throw away useful protocol experiments, but do not mistake packet codecs or a private handshake for a complete Minecraft server.
- Verify each compatibility milestone with actual supported Minecraft Bedrock clients, not only unit tests.

## Recommended implementation order

### Phase 0 — align the repository (current)

- Make this product decision canonical in the README and architecture docs.
- Mark standalone-MMORPG wording as superseded.
- Audit current runtime, world, chunk, entity, inventory, persistence and transport code.
- Produce a compatibility gap list before changing core networking.

### Phase 1 — stable Minecraft-style server core

- Define canonical block, item, entity, player, inventory and world contracts.
- Complete world save/load and deterministic chunk state.
- Add authoritative player sessions, movement, collision and block interaction.
- Add game-mode lifecycle and permissions.
- Keep Bedrock packet mapping outside the domain model.

### Phase 2 — first playable RPG server

- Integrate the existing RPG class and progression foundation with player profiles and persistence.
- Add health, mana, combat, skills, items, mobs, quests and loot.
- Provide a small spawn area and one repeatable gameplay loop.
- Test multiplayer state and server authority.

### Phase 3 — first-party client and Visual Studio

- Turn the existing Android voxel preview into a playable client incrementally.
- Implement movement, camera, interaction, inventory and HUD.
- Add data-driven widgets and theme tokens.
- Add image/font/resource loading with validation and safe size limits.
- Add a visual editor for layout, colors, gradients, shapes, animations and HUD placement.
- Save editor output as versioned resource/UI packs that the client can preview and the server can distribute safely.

### Phase 4 — Bedrock compatibility

- Complete the transport, encryption, login, play-state and chunk/movement packet flows for a pinned Bedrock version.
- Verify against a real unmodified Bedrock client.
- Add a regression matrix for supported versions.
- State clearly which advanced NEXORA features require the custom client.

### Phase 5 — platform expansion

- Add more game modes and reusable templates.
- Add World Studio, RPG Studio and Server Studio workflows.
- Add content-pack validation, permissions, publishing, rollback and version migration.

## Honest status

The repository already has a Java runtime, world/chunk foundations, Bedrock packet experiments, a private NEXORA handshake, an Android application and an RPG domain foundation. These are partial building blocks. They do not yet amount to a complete Minecraft-compatible server, a playable Android client or a finished visual editor. Each milestone must be backed by automated tests and the appropriate integration/device tests.

## Licensing and safety

Use clean-room protocol implementations and original or appropriately licensed textures, models, sounds, fonts and other assets. Do not bundle proprietary Minecraft code or assets. Do not expose unauthenticated development networking to public networks.
