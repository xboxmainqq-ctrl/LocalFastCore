# NEXORA RPG Core — Mobile-first design

## Purpose

NEXORA RPG is a server-authoritative RPG foundation for Nukkit-MOT / Bedrock
players. The first client target is the ordinary Minecraft Bedrock mobile
client. The core must not require a custom Android app, custom HUD injection,
or client-side modifications for its basic RPG loop.

## Current platform-neutral services

- Player profiles keyed by stable UUID, not nickname.
- SQLite persistence with versioned schema and an isolated repository interface.
- Total XP progression with a bounded level range (1–100).
- Five stat-point attributes: strength, agility, vitality, intelligence, wisdom.
- Five stat points granted per level gained.
- Class definitions with validated IDs, base attributes, and ability references.
- Ability definitions with validated mana cost, cooldown ticks, and target mode.
- Thread-safe class and ability registries.
- Tick-based per-player/per-ability cooldown tracking.
- Backward-compatible API constructors for existing modules.

The SQLite repository is a blocking persistence boundary. Callers must use a
worker thread for database operations; the server's primary tick thread must
not perform profile reads or writes.

## Mobile interaction model

The default mobile UI should use Bedrock's native form interface, not a
desktop-style inventory maze.

Suggested /rpg hub:
1. Profile — level, class, XP progress, available stat points.
2. Classes — paginated class list, description, base stats, ability preview.
3. Attributes — large, clearly labelled stat allocation actions.
4. Abilities — ability name, mana cost, cooldown, target mode, availability.
5. Quests — active objectives and reward preview.
6. Settings — compact display and notification preferences.

Form requirements:
- One primary decision per screen.
- Short labels and descriptions that fit narrow phone displays.
- Paginate long class and ability lists.
- Always show the result of an action and any failure reason.
- Ask for confirmation before irreversible actions such as class reset or
  stat respec.
- Keep chat-command fallbacks for players whose clients do not render forms.
- Never trust button payloads: re-check player identity, permissions, class,
  resources, cooldown, and target on the server when an action is submitted.

A normal Bedrock form is a pop-up, not a persistent custom HUD. A custom
always-visible HUD or bespoke touch controls would require a separate client
or a compatible protocol extension and is not part of the first release.

## Runtime rules

- Game state and ability execution remain server-authoritative.
- Never query SQLite on every tick.
- Use bounded async work for persistence and return to the main server thread
  before touching live Nukkit player objects.
- Cooldowns use the server tick counter and are transient across restarts.
- Player quit must clear transient cooldown entries.
- Class and ability IDs are stable slugs and are never display names.
- Class selection must fail closed if the class or one of its referenced
  abilities is not registered.
- API and plugin compatibility identifiers must remain stable.

## Release gates

Before calling the RPG core production-ready:
- Verify Maven tests and shaded JAR contents in CI.
- Add async player session load/save and graceful shutdown flushing.
- Add an ability execution pipeline with permission/resource/target checks.
- Add a Nukkit-MOT form adapter after verifying exact API signatures against
  the pinned Nukkit-MOT dependency.
- Add command and form integration tests.
- Test with real Bedrock mobile clients at narrow and wide resolutions.
- Test reconnects, server restarts, database corruption, and backup recovery.

This document describes the design target. It does not claim that the mobile
forms, session pipeline, or ability execution layer are already implemented.
