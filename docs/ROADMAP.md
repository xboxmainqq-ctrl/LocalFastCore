# LocalFastCore + NexoraAI Roadmap

This is the continuing project plan for the LocalFastCore 0.12.0 line and NexoraAI. Keep this roadmap updated as work is completed; do not treat planned items as implemented until code, tests, and documentation are merged.

## Product direction

Prioritize a stable, fast, observable Minecraft server core. NexoraAI should help operators understand problems, detect abuse, and prepare safe fixes. It must not silently rewrite core behavior or punish players based only on a heuristic.

## Priority 1 — Core stability and performance

- [ ] Establish repeatable baselines for tick-like subsystem latency, Bedrock inbound handling, mob AI, furnace updates, saves, heap, and thread counts. Clearly distinguish measured subsystem latency from true server TPS.
- [ ] Profile hot paths before optimizing; avoid speculative micro-optimizations.
- [ ] Reduce avoidable allocations and repeated world/entity scans in measured hot paths.
- [ ] Audit locks and per-session synchronization for contention and deadlock risks.
- [ ] Audit chunk/world loading, entity lifecycle, and scheduled work for bounded cost and safe shutdown.
- [ ] Improve autosave reliability: bounded work, clear failure reporting, and recovery tests.
- [ ] Add regression/load tests for player movement, mob AI, world saves, and many concurrent Bedrock sessions.
- [ ] Keep LocalFastCore 0.12.0 compatibility as the target unless the project explicitly changes version.

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
- [ ] Add deterministic tests for targeting, movement, despawn, and edge cases.
- [ ] Add optional diagnostics for stuck or repeatedly recalculating mobs.

## Priority 5 — Security and moderation intelligence

- [ ] Continue server-authoritative detection for KillAura, Speed, Fly, and Reach; record evidence, not conclusions.
- [ ] Add confidence levels, false-positive review, and moderator case history.
- [ ] Improve flood and connection-abuse detection using bounded counters and rate limits.
- [ ] Add DDoS resilience at the application layer (connection limits, handshake timeouts, packet validation, resource bounds); document that volumetric upstream DDoS needs hosting/network-provider mitigation.
- [ ] Keep moderation decisions human-reviewed; no automatic ban/kick/mute from AI suspicion.
- [ ] Never expose XUIDs, authentication tokens, or private network details in player-facing output.

## Priority 6 — Operator and moderator experience

- [ ] Build a clear operator health dashboard: core status, resource pressure, slow subsystems, recent incidents, and suggested actions.
- [ ] Build a separate moderator workflow for evidence review and case decisions.
- [ ] Add permission checks to every command and panel action; test unauthorized access.
- [ ] Keep outputs concise, paginated, and safe for Bedrock chat/UI limits.

## Delivery rules

1. Inspect the current implementation before changing it.
2. Make small, reviewable commits on the active development branch.
3. Add tests for behavior changes and update documentation in the same workstream.
4. Run the project build and tests; report the actual result and link to CI.
5. Do not claim a feature is complete merely because it is listed here.
6. Prefer reversible, observable changes; never hide failures or silently swallow exceptions.

## Current next steps

1. Finish and verify the NexoraAI Guard moderator case workflow and its CI run.
2. Inspect the core's real update loops and build a trustworthy performance baseline.
3. Select one measured hot path (likely Bedrock inbound handling or mob AI) and optimize it with regression tests.
4. Extend diagnostics with rolling latency summaries and incident correlation.
5. Continue the security/moderation roadmap after the core baseline is stable.
