# NEXORA — single-player survival and RPG server split

This document records the confirmed gameplay split for NEXORA.

## The two experiences

### 1. Single Player: classic survival sandbox

Single Player should feel like starting an ordinary Minecraft-style survival world, not like entering the network's RPG mode.

The player creates a local world, spawns into a natural block world and survives independently:
- gather resources, mine and place blocks;
- craft tools, weapons, armor and useful items;
- build shelters, farms and bases;
- explore terrain, caves and different biomes;
- fight ordinary hostile mobs and survive day/night;
- manage health, hunger, inventory, equipment and basic progression;
- save the world and player state locally, quit, and continue later.

Keep this mode approachable and familiar. Do not force RPG classes, magic, server ranks, quests, network economy or RPG progression into ordinary single-player survival. Optional creative mode or world settings may be added later, but survival is the first target.

### 2. Online servers: customizable RPG experience

The dedicated multiplayer server is where the special NEXORA RPG identity lives. Server owners can build a rich custom RPG experience with:
- character creation and persistent characters;
- classes, stats, levels, skill trees and magic;
- active abilities, cooldowns, buffs/debuffs and class-specific combat;
- custom weapons, armor, items, rarity, loot and crafting;
- custom mobs, elite enemies, bosses and NPCs;
- quests, storylines, dungeons, raids, events and locations;
- server economy, shops, trading, parties, guilds and social systems;
- permissions, moderation, staff tools, logs and server administration.

These RPG systems are server-authoritative. The server validates combat, inventory, rewards, progression, economy and player actions; the client presents the interface and sends player intent.

## Shared engine, different rulesets

Both experiences use the same NEXORA Core, world/chunk/block/entity foundations, renderer, asset pipeline and reusable gameplay services wherever possible. Single Player runs the same Core embedded in the Android app; a dedicated server runs it headlessly.

However, **sharing the Core does not mean forcing the same game rules on both modes**:
- Single Player loads the built-in Survival ruleset.
- RPG servers load a server-selected RPG ruleset and their configured content/modules.
- Rulesets are explicit, versioned and data-driven. Keep common systems reusable, while mode-specific mechanics remain isolated and configurable.
- Local single-player worlds must remain playable offline and must not depend on network login or remote services.

## Client: make customization easy, attractive and efficient

The NEXORA Android Client is more than a renderer. Its architecture should make future customization easy for server owners and content creators without making every change require a new client build.

Design for:
- data-driven definitions for blocks, items, recipes, mobs, biomes, classes, stats, abilities, quests and UI;
- versioned resource packs for textures, models, sounds, fonts, icons and visual effects, using original or properly licensed assets;
- configurable HUD, menus, inventory, character screens, quest panels, hotbars and RPG interfaces;
- reusable UI components, themes, layouts and safe extension points;
- in-client creator tools such as World Studio, RPG Studio, Item/Block Studio, Model Studio and UI/HUD Studio;
- preview, validation, import/export, versioning and rollback for content packages;
- hot-reload where safe, with clear compatibility checks and no arbitrary unsafe client code;
- efficient rendering and asset loading, atlases/batching, caching, LOD and memory budgets for lower-end Android devices;
- network-delivered server content that is sandboxed, size-limited, validated and clearly identified.

The goal is a beautiful, polished mobile client that is economical to develop and maintain: build reusable systems once, configure content through tools/data, and avoid hardcoding each server's unique interface and mechanics into the Android app.

## Performance and chunk streaming requirements

Performance is a core engine requirement for both offline Survival and online RPG servers. Do not treat chunk generation, loading, meshing or saving as unlimited work.

### Android client

- Keep the render/gameplay thread free of terrain generation, disk I/O, chunk decoding and mesh building.
- Use bounded worker pools and bounded queues. Never create an unbounded task per requested chunk.
- Prioritize chunks by distance and view direction; load the spawn/nearby area first, then expand gradually.
- Cancel or deprioritize stale requests when the player moves, and reject results for chunks that are no longer needed.
- Maintain configurable view distance and hard memory budgets. Evict distant chunk data and GPU meshes safely; keep dirty chunks persisted before eviction.
- Generate and decode chunks incrementally so a large batch cannot cause a long frame hitch.
- Build chunk meshes off the render thread using immutable snapshots; upload completed meshes to the GPU in a small per-frame budget.
- Cull hidden block faces, batch geometry by material/texture, use texture atlases, frustum/distance culling and appropriate LOD.
- Avoid rebuilding neighboring chunk meshes for unrelated changes; update only affected sections and required borders.
- Use compact chunk/block representations and avoid large per-block object graphs in hot paths where practical.
- Save changed chunks asynchronously with bounded write queues, batching and crash-safe ordering.
- Adapt view distance, generation concurrency and mesh upload budget to device memory/thermal/performance conditions, while keeping user-configurable quality limits.

### Dedicated server

- Use a bounded generation scheduler shared across players; prioritize chunks requested by nearby active players and their movement direction.
- Deduplicate concurrent requests for the same chunk so one chunk is generated/loaded only once.
- Apply per-player and global limits for requested, queued, loaded and in-flight chunks to prevent request floods.
- Keep disk reads/writes and expensive generation off the authoritative tick thread.
- Persist chunks asynchronously and safely; never acknowledge durable world changes before the storage policy allows it.
- Track loaded-chunk ownership/reference counts so chunks are unloaded only when no player, entity, ticket or world process needs them.
- Apply backpressure when storage or generation falls behind instead of allowing queues and memory usage to grow without bound.
- Keep server simulation authoritative; send clients only the chunk data and updates needed for their subscribed area.

### Shared observability and acceptance checks

Measure client FPS/frame time, memory use, chunk generation time, queue depth, mesh build/upload time, disk I/O latency, server tick duration and per-player chunk counts. Add stress tests for multiple players moving into unexplored terrain, rapid direction changes, repeated chunk requests, world reloads and low-memory conditions.

Do not claim performance is optimized based on architecture or code review alone. Verify with repeatable benchmarks on a low-end Android device and with server load tests. Set explicit budgets from measured target hardware; do not invent universal FPS or memory guarantees before profiling.

## Architecture guardrails

- Keep the Core independent from Android UI, rendering and transport.
- Embedded single-player and dedicated servers use the same Core, with different explicit rulesets and service composition.
- Do not create a fake/simplified single-player engine.
- Do not make the client authoritative for multiplayer RPG mechanics.
- Keep common survival foundations reusable; keep RPG server mechanics modular.
- Prefer data/configuration/resource packs and validated extensions over frequent client recompilation.
- Never execute arbitrary untrusted scripts or content from a server without a designed sandbox and permission model.
- Keep Android performance, download size, memory, battery and touch usability as first-class constraints.

## Implementation order

1. Finish a real playable offline Survival vertical slice: world generation, first-person movement, collision, chunks, break/place, crafting/inventory, health/hunger, basic mobs, save/reload.
2. Run the same Core as a dedicated server and connect two Android clients.
3. Add the server-side RPG ruleset: character profiles, classes, magic/abilities, combat, mobs, quests and persistence.
4. Build the data-driven content and resource-pack pipeline, then creator tools and configurable UI.
5. Add network lobby, profiles, routing and multiple RPG worlds/instances after one online RPG world works.

Do not claim a feature is implemented until code and tests/device checks demonstrate it.
