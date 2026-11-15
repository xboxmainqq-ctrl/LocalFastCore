# NEXORA — RPG mobile game network vision

## Product definition

NEXORA is an original, mobile-first RPG game network inspired by the structure and convenience of large Minecraft server networks such as Cristalix. It is not a clone of Cristalix, Minecraft, or any other product. NEXORA has its own engine/core, dedicated server software, Android client, protocol, world format, identity, and original or properly licensed content.

**The product focus is RPG only.** Do not turn the project into a general-purpose minigame network. BedWars, SkyWars, parkour hubs, and unrelated minigame modes are outside the current product direction unless the user explicitly changes this decision.

**The first client target is Android phones and tablets.** The user primarily tests from an Android phone and does not have a PC. Prioritize touch-first UX, modest-device performance, installable APKs, and GitHub Actions artifacts. Do not assume a desktop or Java Edition client is required.

## What "like a large server network" means

The inspiration is the network/platform model:
- one recognizable game brand and Android app;
- a central entry/lobby experience;
- player accounts/identity, profiles, friends/social features and progression;
- multiple RPG worlds, locations, instances or shards managed by the same network;
- persistent characters, classes, levels, equipment, quests and economy;
- live server operations, moderation, permissions, events and content updates;
- a backend that can scale from a small test server to multiple dedicated game-server instances.

This is a product/architecture reference only. Do not copy another network's source code, assets, maps, branding, UI, names, or proprietary content.

## Main components

1. **NEXORA Core** — authoritative game simulation: worlds, chunks, blocks, entities, player sessions, movement/physics, combat, inventory, items, stats, skills, quests, persistence, permissions, events and extension APIs.
2. **NEXORA Network / Backend** — account/session services, player profiles, character persistence, lobby and world routing, server registry, party/social features, economy services, moderation and operational controls. Keep network services separate from per-world simulation where appropriate.
3. **NEXORA RPG Dedicated Server** — headless server process that runs the authoritative RPG world/instance. Multiple instances can be started and managed by the network as the project grows.
4. **NEXORA Android Client** — first-party mobile app with 3D renderer, first-person camera, touch joystick/look/jump/interact controls, HUD, inventory, character/class screens, quest UI, server/lobby navigation and settings.
5. **NEXORA Protocol** — versioned authenticated client/server protocol for login/session, world entry, chunk streaming, player movement, interactions, combat, inventory, abilities and server messages.
6. **Embedded Single Player** — offline/local play starts the exact same NEXORA Core used by the dedicated RPG server, with local transport. It must not be a second simplified gameplay implementation.
7. **RPG Content and Creator System** — configurable classes, abilities, stats, items, equipment, mobs, bosses, quests, dungeons, locations, drops, recipes, economy and UI/resource packs. Provide validated data-driven tools and extension APIs rather than requiring source edits for every content change.

## Core RPG experience

The long-term RPG feature set includes:
- character creation and persistent character profiles;
- classes, attributes, levels, experience and skill trees;
- active abilities, cooldowns, effects and class-specific progression;
- equipment, weapons, armor, rarity, loot and crafting;
- NPCs, dialogue, quests, daily/weekly tasks and achievements;
- mobs, elite enemies, bosses, dungeons, raids and world events;
- gold/currency, shops, trading and an economy with server-side validation;
- parties, guilds, friends, chat and social progression;
- optional PvP zones/duels as part of the RPG world, not as unrelated standalone minigames;
- staff/admin tools, permissions, moderation, logs and live server configuration.

These are product goals, not claims that all features already exist. Implement in milestones and keep the status of each feature explicit.

## Player journey

1. Install the NEXORA Android APK.
2. Open the app and sign in or choose an available offline/local mode during development.
3. Enter the NEXORA lobby/network.
4. Create or select an RPG character.
5. Choose an available RPG world or activity.
6. Play with touch controls; the server remains authoritative in multiplayer.
7. Keep character progression and inventory saved between sessions.
8. For offline testing, choose Single Player and play through the same Core locally.

## Architecture and implementation rules

- Keep NEXORA Core independent from the Android UI and network transport.
- Keep the dedicated server headless; it must not depend on Android.
- Use the same authoritative Core for embedded single-player and dedicated multiplayer.
- Treat the network/backend as orchestration and account services; individual game servers own real-time simulation.
- Design for multiple RPG worlds/instances, but do not prematurely build a complex distributed cluster before one complete playable RPG loop works.
- Keep authentication, permissions, economy, combat, inventory and progression authoritative on the server.
- Version APIs, protocol messages, content schemas and persisted data; include validation and migration paths.
- Prioritize a complete playable vertical slice over broad placeholder menus.
- Do not claim playable, multiplayer-ready, secure, or device-tested until the corresponding tests have actually passed.

## Delivery milestones

### M0 — Foundations
Maintain the existing Java server/core, Android app, shared world model, world persistence, CI workflows and architecture documentation. Audit current code before changing it.

### M1 — Playable offline RPG world
- Android app opens a world and starts the embedded NEXORA Core.
- Real first-person 3D world, touch movement/look/jump, gravity and collision.
- Chunk streaming around the player.
- Break/place blocks and interact with basic objects.
- Save/reload world, player position and inventory.
- Basic player profile, one working RPG class, stats, XP and one ability.
- Install/test through a GitHub Actions APK artifact on a real Android phone.

### M2 — First online RPG server
- Run the same Core as a separate dedicated server.
- Authenticated client/server session and secure server-authoritative movement/interactions.
- Join the server from two Android clients and see synchronized players/world.
- Persist player characters and world state.
- Lobby-to-RPG-world entry and clean disconnect/reconnect.

### M3 — RPG gameplay loop
- Character selection, classes, attributes, XP/levels and abilities.
- Mobs/combat, drops/equipment, NPCs/quests and a small dungeon/boss encounter.
- Basic economy and server-side inventory validation.
- Staff permissions, moderation and operational commands.

### M4 — NEXORA network
- Account/profile services, lobby and server registry.
- Multiple RPG worlds/instances and controlled routing/scaling.
- Parties/friends/guild foundations, chat, events and network-wide progression.
- Admin panel, monitoring, backups and safe content rollout.

### M5 — RPG creator tools
- RPG Studio for classes, stats, abilities, mobs, quests, dungeons, loot and balance.
- World Studio and Block/Item Studio.
- Server Studio for configuration, permissions, operations and resource packs.
- Validated content packages, versioning, safe reload and rollback.

## Immediate priority

Do not jump directly to a large distributed network. First finish **M1: a genuinely playable offline Android RPG world**, then prove M2 with a real dedicated server and two Android clients. Build the network/lobby foundation in a way that supports M4 without blocking the playable core.

## Boundaries

- Android-first; Java Edition and desktop game clients are not current targets.
- RPG-only product direction; unrelated minigame modes are out of scope.
- NEXORA is an independent implementation. Use original or properly licensed assets and clean-room code.
- Minecraft Bedrock may be used as a gameplay/protocol reference, but unmodified Bedrock-client compatibility is optional future work and must be verified against real pinned client versions before being claimed.
- Luanti is not the product engine and must not be introduced as a substitute.
