# NEXORA — permanent project brief

This is the canonical hand-off context for every coding session and automated coding agent. Read it before proposing or changing architecture. Do not ask the user to repeat these decisions.

## One-sentence goal

Build NEXORA as an Android-first block-world game platform with two distinct experiences: familiar offline survival single-player and customizable online RPG servers. Both run on the same original NEXORA Core, with explicit mode-specific rulesets. The platform also includes a separate Android client, network/lobby services and creator tools.

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
- **Single-player:** offline mode must start the exact same NEXORA Core used by the dedicated RPG server, not a separate simplified game implementation.- **RPG scope:** character creation, classes, stats, levels, skills/abilities, equipment, mobs, bosses, quests, dungeons, loot, economy, parties/social features and optional PvP integrated into the RPG experience.
- **Build order:** finish one genuinely playable offline Android RPG world first; then prove one online dedicated server with two Android clients; then expand to lobby, multiple worlds/instances and network services. Do not prematurely build a distributed cluster.
- Detailed product brief: [PRODUCT_VISION_RPG_NETWORK.md](PRODUCT_VISION_RPG_NETWORK.md).


## Confirmed gameplay split — survival single-player vs RPG servers

The user clarified on 2026-11-15 that the game modes must be distinct:

- **Single Player:** make it feel like ordinary Minecraft-style survival: create a local world, spawn into a block world, gather/mine/place resources, craft, build, explore, survive day/night, fight basic mobs, manage health/hunger/inventory, save and continue later. Do not force RPG classes, magic, server quests or network economy into this mode.
- **Online dedicated servers:** this is where NEXORA's custom RPG systems live: character creation, classes, stats, levels, magic, abilities, combat, custom equipment/items, mobs/bosses, NPCs, quests, dungeons, economy, parties/guilds and staff/admin systems.
- **Same Core, different rulesets:** embedded single-player and dedicated servers must run the same NEXORA Core. Single Player loads the built-in Survival ruleset; online servers load configured RPG rulesets/modules. Reuse world, chunk, block, entity, inventory and other common foundations, but do not force the same mode-specific rules onto both.
- **Android client and creator direction:** design the client so future customization is attractive, efficient and low-cost: data-driven content, resource packs, configurable HUD/menus, reusable UI components, creator studios, validation/versioning/rollback, safe reload, asset caching and mobile performance budgets. Avoid hardcoding every server's unique RPG content or UI into the app.
- **Priority order:** (1) playable offline Survival on Android; (2) same Core as dedicated server and two-client connection; (3) RPG ruleset for online servers; (4) customization/resource-pack/creator tools; (5) lobby and multi-world network.
- Canonical detail: [SINGLEPLAYER_AND_SERVER_MODES.md](SINGLEPLAYER_AND_SERVER_MODES.md).


## Confirmed server-owner platform model — installable Core and creator client

The user clarified on 2026-11-15 that the main product must be a server platform owners can install on their own host and customize deeply, paired with a separate NEXORA Client that exposes creation and customization tools.

### Dedicated server must be independently installable and extensible

- Ship NEXORA Core as a standalone headless dedicated-server distribution, installable without the Android client.
- A server owner must be able to configure the server from files and tools: server identity/settings, worlds, rulesets, permissions, commands, gameplay systems, content packs and enabled extensions.
- Provide a stable, documented, versioned server Extension API. Extensions/modules must register through lifecycle-managed APIs and have declared dependencies, compatibility ranges, permissions and resource limits.
- Prefer data-driven definitions and declarative configuration for blocks, items, recipes, entities, biomes, loot, classes, stats, abilities, quests, NPCs, UI metadata and world settings. Use code extensions only when configuration/data cannot express the mechanic.
- Support extension/content package install, enable/disable, dependency validation, schema validation, backups, migration, rollback and safe reload where supported. Never promise hot reload for state that cannot be safely replaced.
- Keep server-authoritative mechanics on the server: combat, movement validation, inventory, item effects, abilities, rewards, quests, progression, economy, permissions and persistence.
- Server distribution must include a clear config directory, plugins/extensions directory, content/resource-pack directory, logs, world data and versioned upgrade/migration tooling.

### NEXORA Client is the visual editor and player runtime

- The first-party Android client is separate from the dedicated server and is the main rich customization environment.
- Build in-client editors for HUD/layouts, buttons/touch controls, menus, inventory screens, fonts/icons, themes, textures, block/item models, sounds, particles and visual effects.
- Include creator workflows for custom blocks/items, recipes, mobs, biomes, classes, abilities, quests, NPCs and other mechanics. Editors should produce versioned content packages and/or validated server extension projects.
- Include live preview, device preview, import/export, package validation, compatibility checks, version history, backup and rollback.
- Visual customizations (HUD, textures, buttons, UI themes and models) should be data/resource-pack driven so owners do not need a new client APK for every server change.
- New gameplay mechanics require server-side implementation or a supported declarative mechanic system; client-side visuals alone must never grant authority over multiplayer state.
- The client must sandbox and validate downloaded content, enforce size/memory limits and avoid executing arbitrary server-provided code.

### Compatibility boundary

- The NEXORA Client is the full-featured client for custom HUDs, controls, visuals and creator tools.
- An optional adapter may let unmodified Minecraft Bedrock clients join NEXORA servers using only capabilities Bedrock actually exposes (protocol-compatible blocks/items/forms/resource packs and supported behavior). Do not promise arbitrary custom buttons, HUD widgets or client mechanics in an unmodified Minecraft app.
- Keep the Bedrock adapter isolated from the canonical NEXORA Core and prove each supported client version on real devices.
- Never copy Minecraft source code or proprietary assets; use clean-room protocol work and original or properly licensed content.

### Delivery sequence — server first (confirmed 2026-11-15)

The user explicitly changed the immediate priority: **finish the independently installable dedicated server before continuing client development.** Do not switch back to client-first work unless the user changes this decision.

1. Make the dedicated server independently installable and runnable on hosting, with a clear distribution, startup instructions, configuration and clean shutdown.
2. Implement server configuration and persistent world management (world list, seed, height, ruleset and per-world settings).
3. Implement a generator registry with selectable built-in presets and a documented extension point for custom generators. Generator settings must cover seed, terrain/biomes, world height, resources/ores and structures as those systems become available.
4. Stabilize the versioned server Extension API and data/content package formats; validate dependencies, permissions, compatibility and safe failure/rollback.
5. Verify the server artifact and acceptance tests on CI, including fresh install, startup, world generation, save/restart and configuration validation.
6. Only after the server milestone is verified, resume NEXORA Client work: connect to the dedicated server, then build visual/content editors and resource-pack workflows.
7. Add the optional Bedrock compatibility gateway after the NEXORA client/server path is reliable.


## Current implementation priority — Core first (confirmed 2026-11-17)

The user clarified that RPG is not the current task: **finish and stabilize the general-purpose NEXORA Core first; magic and RPG systems come later and must build on that core.** Do not implement or expand RPG classes, progression, magic, spells, abilities, quests, bosses or RPG plugins during the core-first phase. Existing RPG files may remain untouched.

Immediate engineering focus: server reliability and configuration, world lifecycle and persistence, chunks and bounded async generation, block/entity simulation, player sessions and authoritative interactions, safe networking, extension API and standalone distribution.

The active work log and ordered task list are [CORE_DEVELOPMENT_LOG.md](CORE_DEVELOPMENT_LOG.md). Update it after every meaningful implementation change with completed work, test/CI evidence, blockers and the next exact task. Never claim tests pass without a confirmed CI result.

---

## OVERRIDING PRODUCT DECISION — 2026-11-17

This section is the latest user-confirmed direction and overrides every conflicting historical roadmap section above. Older sections are retained as project history only; do not use them as current requirements.

- **No NEXORA Android client.** Do not plan, build, package or prioritize an Android player client.
- **No separate RPG server/product.** Do not plan an RPG-specific server/network or RPG gameplay roadmap.
- **NEXORA remains a standalone, general-purpose block-world Core and dedicated server.** Server-side runtime and distribution are still in scope; the removed item is the separate RPG server product.
- The user explicitly wants three AI capabilities retained in the NEXORA direction:
  1. **NEXORA Mob AI:** server-authoritative AI for mobs/entities: perception, target selection, navigation/pathfinding, combat/avoidance, environmental reactions, configurable behavior and deterministic/budgeted ticking.
  2. **NEXORA Core AI Assistant:** an optional built-in operator/developer assistant for explaining diagnostics, logs, configuration, performance and operational state. It must use explicit, permission-checked tools; no unrestricted shell/code execution or autonomous destructive changes.
  3. **NEXORA Moderation AI Assistant:** a separate moderation support module that summarizes reports/evidence, detects suspicious patterns and recommends actions. It must respect privacy, permissions, audit logs and human review; automatic punishments are not enabled by default.
- These are **AI systems, not AI mods**. The user cancelled AI mods; do not revive a mod marketplace or user-installed AI-mod direction from that phrase.
- Build order: first stabilize the general Core and server; then implement Mob AI as a native entity/simulation subsystem; then add the Core AI Assistant and Moderation AI as isolated optional modules using a stable, permissioned API. Keep all three in the product roadmap, but do not destabilize core lifecycle, networking or simulation to rush AI features.
- Existing repository code and older documents may contain Android/RPG/Bedrock experiments. Do not infer they remain product goals; preserve useful code until reviewed, but do not extend removed directions.
- See [NEXORA AI systems roadmap](NEXORA_AI_SYSTEMS_ROADMAP.md) for the current AI scope and staged acceptance criteria.


## Verified current AI implementation snapshot — 2026-11-17

- Mob AI code: `MobAiController` is integrated with `EntityTickEngine`; supports configured passive/neutral/hostile behavior, player perception, line-of-sight, provocation and direct collision-aware pursuit. It does not yet provide pathfinding or damage/combat.
- Core assistant code: permission-gated/audited tool registry, bounded tool arguments, optional bounded `AiModelProvider` SPI, assistant facade and lifecycle-managed optional module. No production model provider is bundled.
- Moderation assistant code: bounded heuristic chat signals using message fingerprints, per-player and global LRU memory caps, optional summaries from signal metadata only, mandatory human review and lifecycle-managed optional module. No automatic enforcement or trained classifier.
- Android workflow `.github/workflows/nexora-android.yml` has been removed from active CI. Historical Android source and RPG source are retained but are not active product scope.
