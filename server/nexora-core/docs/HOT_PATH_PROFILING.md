# Nexora hot-path profiling

NEXORA now contains bounded hot-path instrumentation for repeatable performance baselines.

## Instrumented paths

- `MOB_TARGETING` — nearest-player target selection.
- `MOB_AI_DECISION` — generic living-entity AI decision generation.
- `WORLD_ENTITY_TICK` — reserved for world/entity coordinator timing.
- `CHUNK_RESIDENCY` — reserved for chunk residency timing.
- `BEDROCK_INBOUND` — reserved for transport ingress timing.

The profiler keeps a bounded recent window per path. It reports sample count, average, p50, p95, and maximum duration in nanoseconds.

These values are **subsystem timings, not TPS**. They must not be used to infer global server TPS.

## Baseline procedure

1. Run a representative workload for a fixed wall-clock interval.
2. Capture the profiler snapshots for the same interval.
3. Repeat the workload at least three times.
4. Compare p50/p95/max and sample counts between runs.
5. Only optimize a hot path after a repeatable baseline shows that the path is materially expensive.

The current instrumentation is intentionally conservative: it observes execution cost without changing scheduling policy or gameplay behavior.
