# NexoraAI — Nerox 0.12.0

NexoraAI is the built-in diagnostics and operator-assistance layer of the Nerox server core. It is designed to observe server subsystems, explain evidence, and support human-reviewed decisions. It must not silently punish players or apply risky runtime/source changes.

## Safety principles

- Operator-only commands use the authenticated Xbox XUID allowlist.
- Diagnostic output is bounded and avoids exposing XUIDs, tokens, or private payloads.
- AI suspicion is evidence, not proof. Moderation decisions remain human-reviewed.
- Guard case confirmation records a moderator decision; it does not automatically ban, kick, or mute.
- Subsystem latency is not server TPS or CPU utilization.

## Operator commands

### General AI and diagnostics

- `/ai` — open the assistant when configured.
- `/ai admin` — show assistant/operator status.
- `/ai mob` and `/ai mob stats` — inspect mob path-cache, stuck-tracker, load-controller, spawner, and recent mob subsystem metrics.
- `/ai mob stuck` — show a bounded list of mobs with sustained blocked movement.
- `/ai spawner help` — show operator spawner commands.
- `/ai spawner list` — list tracked spawners.
- `/ai spawner place <x> <y> <z> [mob] [delay]` — place and configure a spawner for testing.
- `/ai spawner set <x> <y> <z> <mob> <delay>` — change a tracked spawner.
- `/ai spawner remove <x> <y> <z>` — remove a spawner.

Spawner commands are operator-only. At this stage, command placement is an administrative/testing path; it does not yet mean the survival spawner item, loot, crafting, or full vanilla block-entity visuals are implemented.

### NexoraAI Guard

- `/ai guard` — show Guard status and review summary.
- `/ai guard cases` — list recent bounded combat cases.
- `/ai guard case <ID>` — inspect a case.
- `/ai guard confirm <ID>` — record a moderator confirmation.
- `/ai guard dismiss <ID>` — record a false-positive dismissal.
- `/ai guard help` — show commands.

## Mob AI diagnostics and optimization

### Path cache

Mob navigation uses a bounded LRU path cache with a 150 ms TTL and a 1,024-entry limit. Cached waypoints are collision-checked before reuse. Empty/no-path results are not cached, allowing terrain changes to be observed. Path searches stay inside already resident chunks and do not force-load neighboring chunks; mobs outside loaded chunk footprints are not AI-ticked. Cache counters include hits, misses, recomputations, blocked waypoints, evictions, and current entries.

### Mob movement and physics

Ground-bound mobs receive server-authoritative gravity, terminal fall speed, collision-safe knockback, and ground/air drag. Flying species use three-dimensional pursuit or fleeing and do not receive ground gravity. Species-specific movement speeds and melee reach are applied by the AI service. Physics, pathfinding, raycasts, and projectile movement are restricted to already resident chunks; mob updates do not generate neighboring chunks as a side effect.

### Stuck-mob tracker

The diagnostic tracker reports repeated blocked movement after two seconds without meaningful positional progress. It is bounded to 5,000 runtime IDs. Successful movement, idle behavior, and special behavior clear tracking. It does not teleport, despawn, damage, or otherwise modify mobs.

### Mob AI load controller

Mobs within 24 blocks of a player and mobs with an active aggro target are updated at full rate. Only distant, unprovoked mobs are deterministically staggered, with a conservative two-tick interval. Creeper and ranged special behavior is processed before the generic scheduler. The scheduler shares its nearest-player result with generic decisions to avoid duplicate target scans. Species profiles adjust movement speed, melee reach, and attack damage for selected mobs. Counters report full-rate and deferred decisions; they do not claim measured CPU or TPS improvement.

## Mob spawner simulation

The current server-side spawner implementation provides:

- A dedicated internal `SPAWNER` block and Bedrock runtime-state mapping for the protocol palette used by this branch.
- A 16-block player activation radius; inactive spawners and spawners in unloaded chunks pause their countdown.
- A default 200-tick delay, configurable from 20 to 800 ticks.
- Up to four spawn-position attempts per activation cycle.
- A same-type nearby cap of six mobs in the local 9-block volume.
- Basic spawn-space checks for two air blocks above a non-air floor; candidate validation never forces a neighboring chunk to load.
- A bounded registry of 10,000 spawners.
- Atomic, checksummed persistence of spawner type, delay, coordinates, and next-spawn timer alongside world storage.
- Server-side entity creation and AddActor replication to initialized Bedrock clients; operator placement/removal sends a Bedrock block update.
- Operator commands for placing, configuring, listing, and removing spawners.

This is an initial vanilla-style simulation, not yet a claim of complete Minecraft parity. Survival item acquisition/placement, mob-spawner block-entity NBT/visual animation, exact vanilla spawn rules, full chunk lifecycle integration, and protocol-version validation still require implementation and real-client integration testing.

### Mob movement physics

Ground-bound mobs receive server-side gravity, collision-resolved falling, and damped knockback velocity. Flying species (allays, bats, bees, blazes, ghasts, phantoms, vexes, parrots, and the ender dragon) use three-dimensional target movement and do not receive ground gravity. This is a first physics layer; fall damage, fluids, climbing, jumping, and species-specific flight animation still need dedicated implementation.

### Mob combat and rewards

The generic AI uses species profiles for selected movement speeds, melee reach, and attack damage. Ranged bow users and creepers keep dedicated behavior paths; wither skeletons remain melee attackers. The deterministic reward table covers common drops and XP for several hostile and passive families, with Looting applied as a bounded stack bonus. Items absent from the active item registry are skipped safely rather than creating unknown inventory entries.

### Natural despawn

Hostile mobs that remain more than 128 blocks from every initialized player for 30 seconds are removed. Returning a player inside the radius resets the timer. Passive and neutral mobs are not automatically removed by this tracker. This conservative policy is bounded and is not a complete reproduction of every vanilla persistence/despawn rule.

### Mob lifecycle cleanup

Manual despawn, melee death, fire death, creeper explosion, and thorns-reflection death all clear transient AI state, navigation cache entries, aggro/fuse/cooldown tracking, kill-credit references, and owned mob projectiles. Projectile despawn packets are sent so clients do not retain orphaned arrows.

## Performance verification

Use controlled tests to compare entity counts, path-cache hit rate, deferred AI decisions, spawner attempts, and subsystem latency. Do not report a TPS or CPU gain unless measured with a valid server tick source and a repeatable real-server profile. MCPEhost deployment compatibility must be verified on the target plan before production use.
