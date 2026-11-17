# Nerox + NexoraAI Roadmap

This is the continuing project plan for the Nerox 0.12.0 line and NexoraAI. Keep this roadmap updated as work is completed; do not treat planned items as implemented until code, tests, and documentation are merged.

## Product direction

Prioritize a stable, fast, observable Minecraft server core. NexoraAI should help operators understand problems, detect abuse, and prepare safe fixes. It must not silently rewrite core behavior or punish players based only on a heuristic.

## Priority 1 — Core stability and performance

- [ ] Establish repeatable baselines for tick-like subsystem latency, Bedrock inbound handling, mob AI, furnace updates, saves, heap, and thread counts. Clearly distinguish measured subsystem latency from true server TPS.
- [ ] Profile hot paths before optimizing; avoid speculative micro-optimizations. The first mob-AI cleanup replaces per-mob Stream-based nearest-target scans with allocation-free loops; this is a code-path cleanup, not a measured performance claim.
- [ ] Reduce avoidable allocations and repeated world/entity scans in measured hot paths.
- [ ] Audit locks and per-session synchronization for contention and deadlock risks.
- [ ] Audit chunk/world loading, entity lifecycle, and scheduled work for bounded cost and safe shutdown.
- [ ] Improve autosave reliability: bounded work, clear failure reporting, and recovery tests.
- [ ] Add regression/load tests for player movement, mob AI, world saves, and many concurrent Bedrock sessions.
- [ ] Keep Nerox 0.12.0 compatibility as the target unless the project explicitly changes version.

## Priority 2 — NexoraAI core observability

- [ ] Extend diagnostics with bounded, low-overhead metrics and useful time windows (recent, 5-minute, 1-hour).
- [ ] Add subsystem health states with evidence, timestamps, and confidence; avoid claiming a global TPS value unless the core exposes a valid tick source.
- [ ] Detect sustained latency, memory pressure, thread growth, save failures, and unusual inbound traffic patterns.
- [ ] Add structured incident timelines that correlate symptoms without exposing player XUIDs, secrets, or sensitive payloads.
- [ ] Ensure monitoring is rate-limited and cannot itself overload the server.

## Priority 3 — Safe AI-assisted repair

- [ ] Let NexoraAI explain likely causes and rank suggested fixes with supporting measurements.
- [ ] Generate proposed patches/config changes for operator review; never apply source-code or risky runtime changes autonomously.
- [ ] Require explicit operator approval, backups, validation, and a rollback path for every change.
- [ ] Run tests/build checks before a proposed fix is considered ready.
- [ ] Keep a clear audit log of who approved and applied a change.

## Priority 4 — Mob AI and gameplay quality

- [ ] Profile mob AI update cost and entity counts under load.
- [ ] Improve navigation/target selection without changing gameplay unexpectedly.
- [ ] Bound AI work per update and avoid repeated expensive path calculations.
- [ ] Add a conservative Mob AI load controller: keep mobs near players and mobs with active targets at full update rate; stagger only distant idle mobs, with deterministic tests and operator metrics.
- [ ] Add deterministic tests for targeting, movement, despawn, and edge cases.
- [x] Add conservative deterministic Mob AI scheduling: nearby mobs and mobs with active aggro remain full-rate; distant unprovoked mobs are staggered across ticks. Expose aggregate counters in `/ai mob stats`.
- [x] Add bounded, diagnostic-only stuck-mob detection with a 2-second no-progress threshold and operator report (`/ai mob stuck`); no automatic teleport or punishment.
- [x] Expose read-only Mob AI cache counters and recent mob-ai/mob-pathfinding latency summaries through the allowlisted `/ai mob stats` operator command.
- [x] Reuse nearest-player targeting results between scheduling and generic decisions; remove stream allocation from creeper target selection.
- [x] Add selected species movement-speed, melee-reach, and attack-damage profiles with regression tests.
- [x] Centralize transient AI/navigation/projectile cleanup across manual despawn and mob death paths.
- [x] Expand deterministic loot/XP coverage for common hostile and passive mob families, with bounded Looting bonuses.
- [x] Add server-side gravity, collision-resolved falling, knockback damping, and 3D flight targeting for selected flying species.
- [x] Add bounded hostile-mob natural despawn after a 30-second grace period beyond 128 blocks; passive/neutral mobs are preserved.
- [x] Add bounded fall-distance tracking and landing damage for ground-bound mobs.
- [x] Add conservative one-block obstacle jumping with headroom checks, cooldowns, and loaded-chunk safety.
- [ ] Add fluid movement, climbing, and species-specific special attacks.

## Priority 5 — Vanilla-style mob spawners

- [x] Add a dedicated internal spawner block and Bedrock runtime-state mapping without changing the existing chunk file format.
- [x] Implement 16-block player activation, configurable 20–800 tick delay, bounded spawn attempts, basic valid-position checks, shared nearby same-type caps, and bounded registry/work.
- [x] Enforce a 128-spawner per-tick processing budget with round-robin fairness and make spawner processing idempotent within one server tick, even when multiple Bedrock input callbacks arrive.
- [x] Persist spawner type, delay, coordinates, and next-spawn timer atomically with checksum validation across world saves/restarts.
- [x] Replicate spawned actors and operator-driven spawner block updates to initialized Bedrock clients; protocol compatibility still needs real-client verification.
- [x] Add allowlisted operator setup/inspection/configuration/removal commands; survival item placement and loot are still pending.
- [x] Add unit and persistence tests for unloaded/inactive areas, caps, invalid positions, corrupted data, timer pause, and restart recovery; CI verification is pending.
- [ ] Verify on a real Bedrock client/server that Mob AI load scheduling never prevents a spawner from producing mobs or removes spawned entities.

## Priority 6 — Security and moderation intelligence

- [ ] Continue server-authoritative detection for KillAura, Speed, Fly, and Reach; record evidence, not conclusions.
- [ ] Add confidence levels, false-positive review, and moderator case history.
- [ ] Improve flood and connection-abuse detection using bounded counters and rate limits.
- [ ] Add DDoS resilience at the application layer (connection limits, handshake timeouts, packet validation, resource bounds); document that volumetric upstream DDoS needs hosting/network-provider mitigation.
- [ ] Keep moderation decisions human-reviewed; no automatic ban/kick/mute from AI suspicion.
- [ ] Never expose XUIDs, authentication tokens, or private network details in player-facing output.

## Priority 7 — Operator and moderator experience

- [ ] Build a clear operator health dashboard: core status, resource pressure, slow subsystems, recent incidents, and suggested actions.
- [ ] Build a separate moderator workflow for evidence review and case decisions.
- [ ] Add permission checks to every command and panel action; test unauthorized access.
- [ ] Keep outputs concise, paginated, and safe for Bedrock chat/UI limits.

## Mob-system completion gate (freeze after sign-off)

Do not consider the mob system finished after the load controller alone. Complete and verify the full mob workstream before moving to unrelated features: hostile/passive behavior, targeting, navigation/path caching, stuck diagnostics, combat and special mob behavior, lifecycle cleanup, vanilla-style spawners, persistence, Bedrock replication, and load testing. Add/update regression tests and docs for every behavior change. Once the user receives the final added/changed/removed inventory and accepts the completed stage, freeze mob-related code; do not refactor or change mob behavior in later stages unless the user explicitly reopens the mob workstream or a critical defect is found and approved.

## Delivery rules

1. Inspect the current implementation before changing it.
2. Make small, reviewable commits on the active development branch.
3. Add tests for behavior changes and update documentation in the same workstream.
4. Run the project build and tests; report the actual result and link to CI.
5. Do not claim a feature is complete merely because it is listed here.
6. Prefer reversible, observable changes; never hide failures or silently swallow exceptions.

## Current next steps

1. Complete Mob AI behavior and lifecycle regression coverage, including target acquisition, combat, special mobs, navigation, and despawn cleanup.
2. Implement vanilla-style spawner blocks, activation/delay/caps/valid positions, persistence, and Bedrock client replication.
3. Test spawners with multiple players, nearby-mob caps, restart recovery, and the Mob AI load controller.
4. Run controlled entity-load tests and then verify on a real compatible Bedrock host; do not claim performance gains from synthetic tests alone.
5. Publish a final mob-system change inventory (added, changed, removed) and freeze mob code before starting unrelated feature work.
