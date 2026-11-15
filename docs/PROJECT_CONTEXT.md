# NEXORA — permanent project brief

This is the canonical hand-off context for every coding session and automated coding agent. Read it before proposing or changing architecture. Do not ask the user to repeat these decisions.

## One-sentence goal

Build a Minecraft-style block-world server platform with an original NEXORA server core, a separate first-party Android client, and creator tools that make server customization easy without editing code for every change.

## Non-negotiable product decisions

- Target mobile Android / Minecraft Bedrock-style gameplay and UX. **Java Edition is out of scope.**
- NEXORA is its own product: own server core, client, protocol, world format, branding and content.
- Do not copy proprietary Minecraft source code, assets, textures, sounds, models or UI. Use original or properly licensed assets and clean-room protocol work.
- Provide a standalone dedicated server that server owners can run independently of the Android client.
- Provide a separate NEXORA Android client that can connect to NEXORA servers.
- Single-player must work offline: the Android client starts an embedded instance of the **same NEXORA authoritative server core** and connects locally. Do not create a separate simplified single-player ruleset.
- Multiplayer and single-player must share the same world/gameplay authority, block definitions, entity rules, inventory, persistence and gameplay logic.
- Customization is a first-class feature: server owners should be able to create/configure blocks, items, recipes, mobs, biomes, commands, permissions, game modes, RPG classes/abilities, quests, economy, maps, UI/HUD and resource packs through configuration, extensions and in-client studios.
- Do not promise unmodified Bedrock client compatibility until a pinned client version completes real-device integration tests. NEXORA-only rich UI/editor features belong to the NEXORA Client.
- Do not delete or replace useful existing NEXORA code until an alternative is built, tested and explicitly migrated.

## Target architecture

1. **NEXORA Core / Dedicated Server** — authoritative worlds, chunks, blocks, entities, player sessions, inventory, physics/gameplay, permissions, commands, events, game modes, persistence, extension API and server lifecycle. It runs headlessly on a host.
2. **NEXORA Android Client** — separate installable Android app; renderer, camera, touch controls, UI/HUD, local world list, resource packs, server browser/connection and creator tools. It is not the server authority during multiplayer.
3. **NEXORA Protocol** — versioned client/server protocol with authentication, capabilities, chunk streaming, movement, interactions, inventory and extension messages. Keep transport DTOs separate from the game domain.
4. **Embedded Single-player Host** — launches the exact same Core in-process with a local transport/session. Offline play, world saving and reload must work without internet.
5. **Bedrock Compatibility Adapter (optional milestone)** — isolated gateway/transport adapter for supported unmodified Bedrock clients. Never contaminate the canonical world model with packet-specific fields.
6. **Extension and Content System** — versioned server plugins/modules, data-driven definitions, scripts/configs, resource packs, schema validation, permissions, safe reload/migration and rollback.
7. **Creator tools** — World Studio, Model/Block Studio, RPG Studio, Server Studio, UI/HUD/Visual Studio and resource-pack tools. Editors generate validated data/content packages; server validates every state-changing action.

## Luanti evaluation boundary

The repository has a pinned Luanti source submodule and an Android proof workflow. Treat Luanti as a **candidate source of reusable rendering, input and Android build technology**, not as an automatic replacement for NEXORA Core.

Luanti's built-in server, protocol and world-save format are not automatically compatible with the NEXORA Core/protocol. Do not silently switch the project to a Luanti server or claim that a Luanti APK is already the NEXORA client. First prove whether its Android renderer/input can be integrated behind NEXORA's client networking and world interfaces. If the integration requires adopting Luanti's server/protocol as the product authority, stop and document the trade-off instead of making that architectural change without an explicit user decision.

The first Luanti CI attempt failed before compilation because the Android SDK setup action passed the multiline package list as one invalid package name. Fix the workflow package argument and rerun. This failure does **not** establish that Luanti itself fails to compile.

## Existing repository foundations (verify before relying on them)

- Java 21 Maven server/runtime with lifecycle, tick loop and modular startup/shutdown.
- World/chunk/block data model, deterministic generation, bounded async chunk pipeline, world registry and .nxw persistence.
- RPG class and XP progression foundations.
- Partial Bedrock protocol codecs and a private NEXORA protocol experiment; neither proves complete Bedrock compatibility.
- Android Java application and OpenGL ES voxel preview/client experiments.
- Luanti upstream source pinned as a Git submodule under third_party/luanti.
- Android world storage/preview experiments and shared world-core work exist in project history; inspect the current branch before editing.

These are foundations, not a finished playable game. Do not claim the app/server is playable or Bedrock-compatible until acceptance tests pass.

## First playable milestone: NEXORA Playable 0.1

Acceptance flow:
1. Install and open the NEXORA Android APK.
2. Tap Single Player and create/select a world.
3. The app starts an embedded NEXORA Core instance offline.
4. Spawn into a real first-person 3D block world with touch movement, camera, jump, gravity and collision.
5. Stream/load chunks around the player.
6. Break and place blocks.
7. Save world edits, player position and inventory; close and reopen and verify persistence.
8. Stop the embedded core cleanly on exit.

Then connect the same Android client to a separately running dedicated NEXORA server and verify two clients can share the same world.

## Product roadmap

- Playable 0.1: offline single-player, embedded same core, 3D world, movement/physics, chunk streaming, break/place, persistence.
- Multiplayer 0.2: dedicated server, authentication/session lifecycle, client-server protocol, chunk streaming, player sync, permissions and safe networking.
- Customization 0.3: data-driven blocks/items/recipes/entities/biomes, resource packs, extension API, reload/versioning.
- RPG and modes: classes, stats, abilities, mobs, quests, economy, survival, PvP, BedWars, SkyWars, parkour and custom modes.
- Creator platform: World Studio, RPG Studio, Server Studio, Model/Block Studio and UI/HUD/Visual Studio.

## Working rules for future sessions

- Start by reading this file, README.md, the relevant architecture docs, current branch status and latest CI result.
- Continue from the existing branch and code; do not restart or re-scaffold the project without a demonstrated reason.
- Make small coherent commits. Run relevant unit tests/builds after each meaningful change.
- Inspect failures and fix the actual cause; do not mark a build successful unless CI says success.
- Keep docs and status current. Record what is implemented, what is only planned, blockers, commit SHA and next concrete task.
- For user-facing progress, distinguish implemented, building, tested on emulator/device, and not yet implemented.
- User primarily works from an Android phone and does not have a computer available. Prefer GitHub Actions artifacts and phone-installable APKs for testing.
