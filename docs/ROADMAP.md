# Nerox + NexoraAI Roadmap

This is the continuing project plan for the Nerox 0.12.0 line and NexoraAI. Keep this roadmap updated as work is completed; do not treat planned items as implemented until code, tests, and documentation are merged.

## Product direction

Prioritize a stable, fast, observable Minecraft Bedrock server core. NexoraAI should help operators understand problems, detect abuse, and prepare safe fixes. It must not silently rewrite core behavior or punish players based only on a heuristic.

NEXORA is an independently implemented server core. Nukkit is an architectural and behavioral reference for understanding how a Bedrock server can organize its systems; it is not the codebase to copy, rename, or mechanically fork.

## NEXORA independence and Nukkit reference policy

These rules apply to all future architecture, implementation, review, and planning work:

- [x] Record the product decision: NEXORA is its own Minecraft Bedrock server core, not a renamed or mechanically modified Nukkit fork.
- [ ] Use Nukkit only as a reference for studying architecture, subsystem boundaries, protocol handling concepts, gameplay behavior, lifecycle design, and operational practices.
- [ ] Design NEXORA's architecture independently before implementing a subsystem; document the intended NEXORA contract and ownership boundaries.
- [ ] Implement NEXORA classes, modules, APIs, data models, and runtime behavior in original code. Do not copy/paste Nukkit source, rename its classes, or reproduce its internal implementation mechanically.
- [ ] When consulting Nukkit or any other external project, respect its license and notices; keep a clear record of any third-party code or dependencies actually incorporated. Architectural study alone must not be represented as copied or derived source code.
- [ ] Preserve required Minecraft Bedrock protocol compatibility through independently implemented and tested protocol behavior; compatibility does not require adopting Nukkit's internal architecture.
- [ ] Prefer NEXORA-owned abstractions and interfaces so core subsystems do not depend on Nukkit internals or require Nukkit at runtime.
- [ ] For each major subsystem, record the design rationale, tests, compatibility evidence, and any known gaps before marking it complete.

**Decision rule:** learn from Nukkit's approach, then design and write NEXORA's own solution. If a proposed change would turn NEXORA into a source-level Nukkit copy or a thin fork, stop and redesign it.

## Architecture foundations

- [x] Introduce a validated immutable process configuration so invalid ports, player limits, autosave intervals, and WebRTC ranges fail fast before network startup.
- [x] Centralize owned server resources in a reverse-order lifecycle coordinator; startup rollback and shutdown both attempt every close operation and preserve suppressed failures.
- [x] Split process bootstrap into explicit runtime modules (network, world/persistence, gameplay, diagnostics) with dependency boundaries and focused integration tests. `NetworkRuntimeModule` owns discovery/signaling/WebRTC; `GameplayRuntimeModule` configures and owns Bedrock gameplay sessions and NexoraAI assistant wiring; `WorldRuntimeModule` owns world services; `DiagnosticsRuntimeModule` owns diagnostic storage and monitoring.
- [x] Extract world background services into a `WorldRuntimeModule`; it owns furnace ticking, world/player autosave, and a shared bounded scheduler with isolated tick and persistence lanes. It stops ticking before final save and aggregates shutdown failures.
- [x] Introduce a shared bounded task/tick orchestration layer; per-lane task registration is capped, tick and persistence run on separate workers, cancellation releases capacity, and shutdown waits for workers.
- [x] Define explicit STARTING/READY/STOPPING/STOPPED readiness states; reject new NetherNet session negotiations as soon as shutdown begins while keeping server metadata discoverable.
- [x] Order shutdown by dependencies: close legacy discovery and signaling first, stop WebRTC negotiation, persist active Bedrock sessions, then stop world ticking and perform final world/player persistence; keep diagnostics alive until the other modules stop.
- [x] Extend graceful shutdown with bounded active-session draining before final persistence; close admission, stop discovery/signaling, wait up to five seconds for active Bedrock sessions, close WebRTC, then persist/clear remaining gameplay sessions and finally save world/player state.

## Priority 1 — Core stability and performance

- [ ] Establish repeatable baselines for tick-like subsystem latency, Bedrock inbound handling, mob AI, furnace updates, saves, heap, and thread counts. Clearly distinguish measured subsystem latency from true server TPS.
- [x] Expose Mob AI scheduler decision count and aggregate/last/max decision duration; label it as scheduler overhead rather than full AI execution or server TPS.
- [x] Measure actual living-entity AI decision count, aggregate/last/max/average duration, and failures at the decision algorithm boundary; overload delegation is counted once.
- [x] Add immutable JVM resource snapshots for heap/non-heap usage, committed/max heap, live/peak/daemon threads, processor count, and system load, with invariant tests.
- [x] Expose aggregate furnace tick batches, processed/changed furnace counts, and cumulative/max/last execution duration; verify counters with deterministic manager tests. These measurements are subsystem diagnostics, not global TPS.
- [x] Expose aggregate world autosave attempts, successes/failures, and cumulative/max/last flush duration; cover manual, periodic, final, and failed flush accounting with deterministic tests.
- [x] Add thread-safe per-lane scheduler execution counters and duration snapshots; expose tick and persistence measurements through the world runtime module without presenting them as global TPS.
- [x] Add low-overhead NetherNet inbound frame/byte/message/failure counters and processing-duration snapshots, exposed through Bedrock session diagnostics and covered by concurrent ingress tests.
- [ ] Profile hot paths before optimizing; avoid speculative micro-optimizations. The first mob-AI cleanup replaces per-mob Stream-based nearest-target scans with allocation-free loops; this is a code-path cleanup, not a measured performance claim.
- [ ] Reduce avoidable allocations and repeated world/entity scans in measured hot paths.
- [ ] Audit locks and per-session synchronization for contention and deadlock risks.
- [ ] Audit chunk/world loading, entity lifecycle, and scheduled work for bounded cost and safe shutdown.
- [ ] Improve autosave reliability: bounded work, clear failure reporting, and recovery tests.
- [ ] Add regression/load tests for player movement, mob AI, and world saves.
- [x] Add a deterministic 128-peer concurrency workload for NetherNet identity registration, initial Bedrock network-settings negotiation, and concurrent disconnect/drain cleanup; this does not replace authenticated real-client load testing.
- [ ] Keep Nerox 0.12.0 compatibility as the target unless the project explicitly changes version.

## Priority 2 — NexoraAI core observability

- [x] Add bounded 60-second rolling subsystem-latency summaries (maximum 60 samples per subsystem), including sample count, observed window span, average, p95, maximum, and recent budget breaches; expose them in operator evidence and label them as subsystem timings, not TPS.
- [x] Add bounded 5-minute and 1-hour subsystem latency aggregates using at most 60 minute buckets per subsystem; summaries expose sample count, budget breaches, average, and max. These are minute-granularity subsystem timings, not TPS.
- [x] Add 5-minute and 1-hour failure counts/rates over the actual observed minute span plus bounded JVM resource-pressure samples (heap ratio and system load per processor); pressure is diagnostic only and sampled during operator/monitor snapshots.
- [x] Add bounded per-subsystem health states from 5-minute latency buckets with evidence, wall-clock/monotonic timestamps, confidence tiers, sparse-data UNKNOWN status, and deterministic expiry tests; thresholds are diagnostic heuristics, not TPS.
- [x] Detect sustained JVM heap pressure and thread-count growth across three consecutive minute buckets; require at least two resource snapshots per minute, report diagnostic evidence only, and keep missing/sparse windows unknown.
- [x] Detect sustained subsystem latency-budget breaches across three consecutive minute buckets; require at least five measurements per minute and a breach ratio of at least 25% in every minute. Sparse windows remain unknown and reporting is diagnostic only.
- [ ] Detect save failures and unusual inbound traffic patterns.
- [x] Add minute-aligned comparison of Bedrock inbound and mob AI/pathfinding latency over 5-minute and 1-hour windows; report shared-minute co-occurrence and aggregate timings, never causal claims or player identifiers.
- [x] Add a bounded structured incident timeline with monotonic sequence/uptime ordering, wall-clock timestamps, and sanitized descriptions; correlate broader symptoms without exposing player XUIDs, secrets, or sensitive payloads.
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
- [x] Add a conservative Mob AI load controller: keep mobs near players and mobs with active targets at full update rate; stagger only distant idle mobs, with deterministic tests and operator metrics.
- [x] Add deterministic tests for targeting, movement, despawn, physics, and edge cases; run the full CI suite once a GitHub runner is available.
- [x] Add conservative deterministic Mob AI scheduling: nearby mobs and mobs with active aggro remain full-rate; distant unprovoked mobs are staggered across ticks. Expose aggregate counters in `/ai mob stats`.
- [x] Add a deterministic 10,000-mob / 20-tick scheduling workload regression (200,000 decisions) covering full-rate nearby/targeted mobs and staggered distant idle mobs; this validates scheduling fairness, not live server TPS.
- [x] Add bounded, diagnostic-only stuck-mob detection with a 2-second no-progress threshold and operator report (`/ai mob stuck`); no automatic teleport or punishment.
- [x] Expose read-only Mob AI cache counters and recent mob-ai/mob-pathfinding latency summaries through the allowlisted `/ai mob stats` operator command.
- [x] Reuse nearest-player targeting results between scheduling and generic decisions; remove stream allocation from creeper target selection.
- [x] Add selected species movement-speed, melee-reach, and attack-damage profiles with regression tests.
- [x] Centralize transient AI/navigation/projectile cleanup across manual despawn and mob death paths.
- [x] Expand deterministic loot/XP coverage for common hostile and passive mob families, with bounded Looting bonuses.
- [x] Add server-side gravity, collision-resolved falling, knockback damping, and 3D flight targeting for selected flying species.
- [x] Add bounded hostile-mob natural despawn after a 30-second grace period beyond 128 blocks; passive/neutral mobs are preserved.
- [x] Add bounded fall-distance tracking and landing damage for ground-bound mobs.
- [x] Reset creeper fuse progress when its target leaves trigger range or its chunk unloads; require a fresh fuse after reload, with deterministic tracker tests.
- [x] Add conservative one-block obstacle jumping with headroom checks, cooldowns, loaded-chunk safety, and invoke it from the live mob movement loop before pathfinding.
- [x] Add bounded spider and cave-spider wall climbing with headroom and loaded-chunk checks.
- [ ] Add fluid movement and complete species-specific special attacks. Water/lava behavior remains blocked by the current world block/fluid model and must not be simulated with unsafe guesses.

## Priority 5 — Vanilla-style mob spawners

- [x] Add a dedicated internal spawner block and Bedrock runtime-state mapping without changing the existing chunk file format.
- [x] Implement 16-block player activation, configurable 20–800 tick delay, bounded spawn attempts, entity-height-aware valid-position checks, shared nearby same-type caps, and bounded registry/work.
- [x] Enforce a 128-spawner per-tick processing budget with round-robin fairness and make spawner processing idempotent within one server tick, even when multiple Bedrock input callbacks arrive.
- [x] Persist spawner type, delay, coordinates, and next-spawn timer atomically with checksum validation across world saves/restarts.
- [x] Replicate spawned actors and operator-driven spawner block updates to initialized Bedrock clients; protocol compatibility still needs real-client verification.
- [x] Add allowlisted operator setup/inspection/configuration/removal commands; survival item placement and loot are still pending.
- [x] Add unit and persistence tests for unloaded/inactive areas, caps, invalid positions, corrupted data, timer pause, and restart recovery; verified by the full CI suite (581 tests, 0 failures, 0 errors).
- [x] Add a Bedrock integration regression proving an active spawner still creates and replicates mobs while a distant idle mob is deferred by the generic Mob AI scheduler.
- [ ] Verify on a real Bedrock client/server that Mob AI load scheduling never prevents a spawner from producing mobs or removes spawned entities.
- [ ] Run integration/load tests with more than 128 active spawners, multiple players, and a full living-entity cap; record measured results rather than inferring TPS.

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
7. Treat Nukkit and other external server projects as references, not source templates; follow the NEXORA independence policy above.

## Current next steps

1. Validate the completed runtime-module, bounded scheduler, and graceful-drain integration on CI; fix all regressions before declaring this architecture stage stable.
2. Continue mob and spawner integration/load checks; real Bedrock client verification remains a separate gate.
3. Extend subsystem latency and JVM resource telemetry with bounded, low-overhead measurements; three-minute JVM thread-growth, sustained heap-pressure, and repeated latency-breach detection are implemented with sparse-window safeguards. Do not infer true TPS from isolated timings. The operator `/ai admin` snapshot now includes actual mob-decision timings, JVM heap/thread counters, and rolling latency summaries for instrumented subsystems; the diagnostics service also exposes a bounded structured incident timeline; `/ai mob stats` includes the detailed AI execution summary. 5-minute/1-hour minute-bucket latency aggregates are now available; 5-minute/1-hour failure rates and JVM resource-pressure sampling are available. Bedrock inbound and mob AI/pathfinding timings are compared in shared minute buckets; this is co-occurrence, not proof of causality.
