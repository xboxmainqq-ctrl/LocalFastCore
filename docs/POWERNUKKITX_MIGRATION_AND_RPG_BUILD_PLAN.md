# NEXORA PowerNukkitX evaluation and RPG network build plan

Status: implementation plan on an isolated branch. This document does not claim that PowerNukkitX has been integrated or that a playable server build exists.

## Goal

Build a polished Minecraft Bedrock RPG network under the NEXORA identity. The initial release should be a playable server experience with a spawn/lobby, character progression, combat, quests, bosses, economy, shops, clans, passes, ranks and staff tools.

## Important compatibility gate

PowerNukkitX is a candidate foundation, not yet an approved replacement. Before migrating gameplay code, verify all of the following on the actual target environment:

- The selected PowerNukkitX release builds and starts on the agreed Java 21 runtime.
- Its Bedrock protocol support accepts the intended retail Bedrock client version (currently reported as 1.26.60).
- The host can expose the required UDP game port and the server process can bind it.
- Login, resource-pack negotiation, spawn, movement, chunk streaming, block interaction and reconnect work with an unmodified client.
- The license and extension APIs permit the intended distribution and customization.

If any gate fails, keep the existing NEXORA core and use PowerNukkitX only as a comparison/test branch. Do not delete or rewrite the existing runtime.

## Repository safety

- Base working branch: `nexora/compatibility-edition`.
- This plan is maintained on `nexora/powernukkitx-rpg-foundation`.
- Never push experimental work to `main`.
- Preserve existing NEXORA world, RPG domain and protocol experiments until their value is assessed.
- Do not claim a build is playable until it has passed an actual Bedrock client test.

## Proposed runtime layout

The preferred structure is a small set of independently testable NEXORA modules, implemented as PowerNukkitX plugins/extensions only where the platform API supports them. Keep domain logic isolated from platform-specific adapters so that it can be reused if the core decision changes.

- `nexora-platform`: shared IDs, configuration, storage, migrations, service registry and diagnostics.
- `nexora-profiles`: player identity, profile, settings, statistics and character slots.
- `nexora-permissions`: permission nodes, rank inheritance, staff roles and audit log.
- `nexora-rpg`: classes, XP, levels, attributes, health, mana, skills, cooldowns and effects.
- `nexora-items`: item definitions, rarity, equipment, affixes, crafting and loot tables.
- `nexora-combat`: server-authoritative damage, targeting, cooldown and status-effect pipeline.
- `nexora-mobs`: custom mob definitions, spawn rules, drops and AI adapters.
- `nexora-bosses`: encounter state machine, phases, arena rules, rewards and raid records.
- `nexora-quests`: objective engine, dialogue/NPC adapters, daily/weekly/story quest progress.
- `nexora-economy`: wallet, currencies, transaction ledger and anti-duplication checks.
- `nexora-shops`: server shops, rotating stock, sell rules and optional auction house.
- `nexora-clans`: membership, roles, invitations, bank ledger, progression and wars.
- `nexora-passes`: seasonal progression, free/premium tracks, missions and claim history.
- `nexora-ranks`: player privileges, cosmetics, rank expiry and entitlement history.
- `nexora-cosmetics`: prefixes, badges, chat styling, particles and cosmetic unlocks.
- `nexora-staff`: staff ranks, moderation actions, reports, vanish/fly where supported and immutable audit records.
- `nexora-menus`: consistent menu definitions, navigation and server-validated actions.
- `nexora-lobby`: spawn protection, navigation, NPCs, portals and mode selection.
- `nexora-resource-pack`: versioned Bedrock assets, custom item/entity textures and UI resources where supported.

Avoid one giant plugin. Modules should communicate through documented service interfaces and domain events. Persistent data must be versioned and migrated safely.

## Initial product systems

### RPG
Four starting archetypes: Warrior, Mage, Ranger and Guardian. Each has a class identity, base attributes, active skills, passive talents and progression. Start with one character per player; add class switching only with an explicit policy. All XP, stats, cooldowns, health, mana, equipment and combat outcomes are server-authoritative.

### Bosses and dungeons
Start with one repeatable dungeon and one boss with at least three readable phases, telegraphed attacks, enrage timer, party scaling and a reward table. Add more bosses only after encounter persistence and anti-exploit checks are stable.

### Economy and shops
Use integer minor units (no floating-point currency), an append-only transaction ledger, idempotent purchase IDs, stock validation and atomic reward claims. Separate earned currency from premium currency. Never trust a client-side price or balance.

### Clans
Create/invite/leave/kick, leader and officer roles, clan progression, shared bank ledger and clan quests. Territory wars are a later milestone after anti-cheat and world protection.

### Ranks and donations
Use configurable rank definitions and permission nodes, not hard-coded rank checks. Entitlements need source, purchase/reference ID, grant time, expiry and revocation history. Cosmetic benefits should be preferred over pay-to-win advantages. Purchases must be confirmed by a trusted payment provider/server-side receipt; never grant paid ranks from a client command.

### Quests and passes
Use data-driven quest definitions with validated objectives and reward bundles. Seasonal pass XP should come from server-confirmed gameplay events. Track every claim to prevent duplicate rewards. Support free and premium reward tracks with clearly disclosed benefits.

### Administration
Separate operator, administrator, moderator and helper capabilities. Every moderation or economy action must record actor, target, reason, timestamp and before/after values where applicable. High-risk actions require explicit permission and confirmation.

## Bedrock presentation constraints

- Chat formatting, name tags, forms, item icons and resource-pack glyphs depend on the exact Bedrock client and server API.
- Smooth RGB gradients, arbitrary inline images and fully custom HUD panels must not be promised for vanilla clients until tested. Resource packs can supply textures/glyphs, but they do not grant arbitrary UI control.
- Keep a presentation capability layer so unsupported features degrade gracefully.
- If a first-party NEXORA client is pursued later, richer custom screens can be added through a separately versioned client protocol; it is not required for the first server-only milestone.

## Delivery milestones

1. **Foundation verification:** choose exact PowerNukkitX build, Java 21 runtime, host startup method, UDP port and retail-client login test.
2. **Playable base:** spawn, worlds, persistence, permissions, profile storage, safe restart and backups.
3. **RPG vertical slice:** one class end-to-end, XP/levels/stats, health/mana, one skill, one mob, one boss and one dungeon.
4. **Progression loop:** item rarity/equipment, loot, quests, daily rewards, economy and shops.
5. **Social systems:** clans, parties, guild missions and leaderboards.
6. **Monetization and seasons:** ranks, cosmetics, premium currency, battle pass, trusted payment verification and claim audit.
7. **Operations:** staff panel, reports, moderation, logs, backups, metrics and anti-exploit testing.
8. **Polish:** resource pack, visual identity, menu consistency, sound/particle feedback, onboarding and performance/load tests.

## First implementation slice

Do not implement all systems simultaneously. First complete the foundation gate, then deliver a small but complete vertical slice:

- Join the server with the target Bedrock client.
- Spawn safely and open the NEXORA main menu.
- Create/load a persistent player profile.
- Select Warrior, Mage, Ranger or Guardian.
- Gain XP from a server-confirmed action and level up.
- Use one server-validated skill with cooldown and mana cost.
- Defeat one test mob and receive a persisted reward.
- Restart the server and verify the profile and reward remain correct.

Only after this slice passes should the remaining systems be layered in.
