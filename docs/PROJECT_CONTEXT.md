# NEXORA — permanent project brief

This is the canonical hand-off context for every coding session and automated coding agent. Read it before proposing or changing architecture. Do not ask the user to repeat these decisions.

## One-sentence goal

Build NEXORA as an Android-first RPG game network: an original block-world Core and dedicated RPG server platform, a separate first-party Android client, network/lobby services, persistent RPG gameplay and creator tools. The product direction is inspired by the structure of large Minecraft server networks, not a copy of any one network.

## Non-negotiable product decisions

- Target mobile Android / Minecraft Bedrock-style gameplay and UX. **Java Edition is out of scope.**
- NEXORA is its own product: own server core, client, protocol, world format, branding and content.
- Do not copy proprietary Minecraft source code, assets, textures, sounds, models or UI. Use original or properly licensed assets and clean-room protocol work.
- Provide a standalone dedicated server that server owners can run independently of the Android client.
- Provide a separate NEXORA Android client that can connect to NEXORA servers.
- Single-player must work offline: the Android client starts an embedded instance of the **same NEXORA authoritative server core** and connects locally. Do not create a separate simplified single-player ruleset.
- Multiplayer and single-player must share the same world/gameplay authority, block definitions, entity rules, inventory, persistence and gameplay logic.
- RPG is the product focus. Build persistent characters, classes, stats, levels, abilities, equipment, mobs, bosses, quests, dungeons, economy, parties/social systems and RPG worlds. Do not expand into unrelated minigame modes such as BedWars or SkyWars unless the user explicitly changes this direction.
- NEXORA should grow into a network with a lobby, player profiles, multiple RPG worlds/instances, server routing and network operations. Build this incrementally: prove one playable RPG world and one dedicated server before adding distributed infrastructure.
- Customization is a first-class feature: server owners should be able to create/configure blocks, items, recipes, mobs, biomes, commands, permissions, RPG classes/abilities, quests, economy, maps, UI/HUD and resource packs through configuration, extensions and in-client studios.
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

## Engine and Minecraft compatibility boundary

Do not use Luanti or another unrelated game as the product engine. The user explicitly rejected Luanti and wants a Minecraft-style platform with an original NEXORA core and a separate Android client.

Use Minecraft Bedrock as the gameplay and compatibility reference: study public protocol specifications, documented formats and observable client behavior; implement clean-room server/client code. Do not copy proprietary Minecraft source, decompile or redistribute game code, or bundle copyrighted Minecraft assets. Use original or properly licensed textures, sounds, models and UI.

The first-party NEXORA Client is a separate Android app. The standalone NEXORA Dedicated Server runs headlessly. The embedded single-player host runs the same NEXORA Core in-process. An optional Bedrock adapter may later allow unmodified Bedrock clients to connect, but compatibility must be proven against a pinned real client version and is not a substitute for the NEXORA Client.

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
- RPG gameplay: persistent characters, classes, stats, abilities, mobs, quests, dungeons, bosses, equipment, economy, parties/social features and optional RPG PvP.
- NEXORA network: Android lobby, player profiles, server registry/routing and multiple RPG worlds/instances.
- Creator platform: World Studio, RPG Studio, Server Studio, Model/Block Studio and UI/HUD/Visual Studio.

## Working rules for future sessions

- Start by reading this file, README.md, the relevant architecture docs, current branch status and latest CI result.
- Continue from the existing branch and code; do not restart or re-scaffold the project without a demonstrated reason.
- Make small coherent commits. Run relevant unit tests/builds after each meaningful change.
- Inspect failures and fix the actual cause; do not mark a build successful unless CI says success.
- Keep docs and status current. Record what is implemented, what is only planned, blockers, commit SHA and next concrete task.
- For user-facing progress, distinguish implemented, building, tested on emulator/device, and not yet implemented.
- User primarily works from an Android phone and does not have a computer available. Prefer GitHub Actions artifacts and phone-installable APKs for testing.

## Latest project direction update

User explicitly rejected Luanti on 2026-11-15. Do not continue Luanti integration or build its APK. Keep the existing NEXORA code and move forward with the Bedrock-inspired, clean-room NEXORA server/client architecture. The first Luanti workflow failure occurred in Android SDK setup before compilation and is now obsolete as a project goal.


## Latest product direction — Android RPG network

The user confirmed on 2026-11-15 that NEXORA is intended to become a mobile RPG game network inspired by the network model of large Minecraft servers such as Cristalix. This is inspiration for the platform structure only; do not clone another network's code, branding, maps, assets or proprietary content.

- **RPG only:** NEXORA is not intended to become a general minigame network. Unrelated modes such as BedWars, SkyWars and parkour are out of scope unless the user explicitly changes the product direction.
- **Android-first:** the first player-facing client is an installable Android app designed for phones/tablets, touch controls and real-device performance. The user primarily works from an Android phone and has no PC.
- **Network model:** a central lobby and player identity/profile experience, persistent characters and progression, and eventually multiple RPG worlds/instances managed by NEXORA network services.
- **Game servers:** each RPG world/instance is authoritative on a dedicated NEXORA server. Keep real-time simulation in NEXORA Core and account/routing/orchestration services separate.
- **Single-player:** offline mode must start the exact same NEXORA Core used by the dedicated RPG server, not a separate simplified game implementation.
- **RPG scope:** character creation, classes, stats, levels, skills/abilities, equipment, mobs, bosses, quests, dungeons, loot, economy, parties/social features and optional PvP integrated into the RPG experience.
- **Build order:** finish one genuinely playable offline Android RPG world first; then prove one online dedicated server with two Android clients; then expand to lobby, multiple worlds/instances and network services. Do not prematurely build a distributed cluster.
- Detailed product brief: [PRODUCT_VISION_RPG_NETWORK.md](PRODUCT_VISION_RPG_NETWORK.md).
