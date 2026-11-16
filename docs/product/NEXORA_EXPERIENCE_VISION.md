# NEXORA — retention, identity and presentation feature vision

This document defines the product bar for the NEXORA Nukkit-MOT server pack. NEXORA must feel like a coherent original game network, not a generic server with a collection of unrelated plugins.

## Product principles

1. Every major system has a clear player benefit and a polished presentation.
2. Progression gives players meaningful short-, medium- and long-term goals.
3. Social play and live events create reasons to return with friends.
4. Cosmetics are expressive but do not create unfair competitive advantages.
5. The server remains authoritative: MOT validates combat, rewards, inventory, abilities and progression.
6. Client visuals must respect actual Bedrock capabilities. Use supported formatting, forms, particles, sounds and resource packs; reserve client-only custom rendering/animations for the later NEXORA Client.
7. Performance, accessibility and clear feedback are part of quality, not optional polish.

## Signature systems

### NEXORA Identity
- Animated-feeling rank presentation using supported Bedrock formatting and resource-pack glyphs/icons where supported.
- Rank progression, profile card, titles, badges, player showcase and cosmetic unlocks.
- Gradient text implemented only where the target client renders it reliably; provide a readable fallback.
- Consistent typography, spacing, iconography, color palette and sound/particle language.

### NEXORA UI
- Branded forms and menus for profile, modes, quests, collection, settings and rewards.
- Clear navigation, contextual help, confirmations and useful empty states.
- Resource-pack-backed icons and custom textures where supported by Bedrock and MOT.
- No claims of arbitrary inline chat images or full custom UI until verified on the target client.

### NEXORA Combat
- Server-authoritative hit validation, critical-hit rules, combos, cooldowns and ability triggers.
- Distinct feedback for hit, critical, blocked hit, kill and ability activation.
- Supported particles/sounds now; advanced client-side hit animations and custom rendering later in NEXORA Client.
- Competitive balance and anti-cheat protections.

### NEXORA RPG progression
- Classes with distinct roles and playstyles.
- Abilities with upgrades, cooldowns and visual identity.
- Quests, achievements, collections, equipment sets and mastery.
- Boss encounters with telegraphed phases, team roles and repeatable rewards.

### NEXORA World and social
- Welcoming lobby, guided first session and discoverable activities.
- Friends, parties, clans/guilds, shared challenges and leaderboards.
- Rotating daily/weekly quests, scheduled world events and seasonal content.
- Player housing or personal spaces as a later retention feature, subject to performance and scope.

### NEXORA Economy and rewards
- Earnable currency, transparent sinks and sources, trading rules and reward history.
- Cosmetics and convenience-focused monetization; avoid pay-to-win advantages.
- Daily/weekly rewards should reward play without punishing players for missing a day.

## Delivery order

### Foundation
- Nukkit-MOT baseline on McpeHost.
- Verify exact Bedrock client compatibility, login, movement, chat, reconnect and two-player session.
- Build a clean plugin/module architecture and shared configuration.

### First playable NEXORA experience
- Branded lobby and navigation.
- Profiles, ranks, permissions, chat and basic cosmetics.
- One complete RPG class loop: choose class, fight, earn XP, unlock an ability, save progress.
- One repeatable quest and one small boss encounter.
- Initial resource pack and consistent presentation.

### Retention and social layer
- Daily/weekly quests, achievements, collections and leaderboards.
- Friends, parties and guilds.
- Scheduled events and seasonal progression.

### Advanced visuals
- Validate each effect on retail Bedrock 1.26.60 first.
- Implement server-supported particles, sounds, forms, resource-pack textures and glyphs.
- Prototype custom client-only combat effects, animations and pulse visuals only after server acceptance testing and a separate client feasibility review.

## Quality gates

- Every feature has a functional test and a real-client visual test.
- Menus are navigable on a phone and do not rely on tiny text.
- Chat remains readable with formatting enabled and disabled.
- Effects have settings to reduce or disable them.
- Progress is persisted safely across restart.
- Rewards and combat outcomes are calculated server-side.
- No feature is called complete until tested on the actual McpeHost MOT deployment and a retail Bedrock client.
