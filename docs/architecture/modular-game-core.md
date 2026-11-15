# NEXORA modular game core

Status: product architecture decision. This document defines the intended runtime boundaries; it does not claim that all listed modules are implemented.

## Product baseline

NEXORA is a standalone fantasy MMORPG with its own Android client, network protocol, visual identity and gameplay rules. The primary experience is an original RPG world with character classes, attributes, abilities, equipment, quests, dungeons, bosses, loot, guilds and persistent progression. Minecraft/Bedrock compatibility is optional future work, not a requirement for the initial release.

The client and server must remain independently owned and extensible. Do not copy Minecraft source code or redistribute proprietary assets. Use original or appropriately licensed content. Visual style, HUD, controls and resource definitions are NEXORA-owned rather than a Minecraft clone profile.

## Runtime layers

The game runtime is split into contracts with one-way dependencies:

1. **foundation** — identifiers, versioning, events, lifecycle, diagnostics and configuration primitives. No Android, renderer, storage-provider or network dependency.
2. **world** — dimensions, chunks, block states, biomes, block entities, world generation, tick scheduling and world persistence. Owns authoritative simulation rules.
3. **content** — registries for blocks, items, entities, recipes, effects and tags. Content definitions refer to stable namespaced identifiers, never renderer-specific numeric IDs.
4. **resources** — resource-pack discovery, manifests, dependency resolution, priority/overrides, validation, asset handles and cache invalidation. Supports textures, models, sounds, fonts, localization and UI assets.
5. **presentation** — renderer-facing models, materials, animation and camera. Reads world/content/resource snapshots; it must not own authoritative gameplay state.
6. **ui** — screen navigation, widgets, RPG HUD, character sheet, skill bar, inventory, input mapping and accessibility. UI definitions are data-driven and themeable; gameplay actions go through typed commands.
7. **client** — Android lifecycle, touch input, local session, rendering orchestration and connection UX. Adapts platform APIs to the runtime contracts.
8. **network** — versioned transport, packet codecs, authentication and replication. Converts validated network messages into world/session commands.
9. **server** — headless host for the same world/content contracts. Must not depend on Android, UI or graphics APIs.

The server and Android client may use different platform adapters, but they should share the same platform-neutral world, content identifiers, deterministic rules and test vectors wherever practical. The server remains buildable without Android SDK or graphics libraries.

## Extension points

### Content packs
A content pack has a stable pack ID, semantic version, supported NEXORA API range, dependency list and declared capabilities. Definitions use namespaced IDs such as `nexora:granite`. Duplicate IDs are rejected unless the pack explicitly declares a supported override and wins deterministic priority resolution. Missing dependencies, cycles, incompatible API versions and invalid definitions fail validation before a world is opened.

### Resource packs
Resource packs can override textures, models, sounds, fonts, localization and UI assets by namespaced asset key. Pack priority is deterministic and visible to users. A resource reload builds a new immutable asset index and swaps it atomically; active render frames never observe a half-loaded pack. Invalid packs produce actionable diagnostics and fall back to the previous valid asset.

### UI themes and layouts
Screens are composed from reusable widgets and layout definitions. Theme tokens define colors, spacing, typography, borders, focus and pressed states. The vanilla-style profile ships as the default theme/layout set. UI actions emit typed intents (for example, open inventory or request block interaction); they do not directly mutate world state.

### Gameplay modules
Gameplay modules register content and subscribe to documented lifecycle/events. Registration happens during startup, before a world begins ticking. Runtime hot-unload is not assumed: modules that own live entities or world data must declare safe shutdown/migration behavior first.

## Data and compatibility rules

- Persistent and network data use stable namespaced identifiers and explicit schema/protocol versions.
- Runtime numeric IDs are assigned from a deterministic, versioned registry and are never persisted as the only identity.
- World saves record the required content-pack IDs and versions. Missing required content must be reported; the engine must not silently replace unknown blocks with air.
- Resource-pack changes must not rewrite world data.
- Rendering may cache meshes and textures, but cache contents are disposable and rebuildable from world/content/resource data.
- Client prediction is presentation-only until the server accepts the corresponding authoritative action.
- A private NEXORA protocol is never described as Bedrock-compatible. Bedrock interoperability has a separate acceptance gate based on real-client tests.

## Recommended implementation sequence

### Phase 1 — stable contracts
- Define module IDs, API versions, lifecycle states and typed registration interfaces.
- Add deterministic namespaced registries for blocks/items/entities.
- Add tests for duplicate IDs, missing dependencies, version mismatch and stable registry snapshots.

### Phase 2 — resource pipeline
- Define pack manifests and safe archive/path validation.
- Load original test textures and localization from a resource pack.
- Add deterministic priority/override resolution and atomic reload.
- Keep asset loading independent of Android UI and server startup.

### Phase 3 — data-driven UI
- Define theme tokens and a small widget/layout schema.
- Move the current menu and HUD presentation toward data-driven layouts.
- Preserve accessible touch targets, keyboard/controller focus semantics and localization.

### Phase 4 — playable single-player
- Connect the Android client to the platform-neutral local world session.
- Add persistent world creation/list/open/delete flows.
- Implement chunk streaming, collision, block breaking/placement, inventory and save/reload.
- Replace the temporary preview renderer only after the actual world/session pipeline supplies its data.

### Phase 5 — multiplayer
- Replicate the same world/content state through the versioned network layer.
- Keep server authority for movement validation, inventory, block changes and entity simulation.
- Verify NEXORA-client/server integration separately from unmodified Bedrock interoperability.

## Non-goals and safeguards

- This architecture does not promise complete Minecraft feature parity or compatibility with every Bedrock release.
- Do not bundle proprietary Minecraft assets or use undocumented official services.
- Do not make the server depend on Android classes or renderer state.
- Do not expose unauthenticated development transport to public networks.
- Do not mark a milestone complete until implementation, automated tests and an appropriate client/device integration test pass.
