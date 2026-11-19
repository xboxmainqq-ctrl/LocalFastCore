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


## Synthetic mob-AI load benchmark

Run the repeatable AI workload from the repository root:

```bash
mvn -f server/nexora-core/pom.xml -Dtest=LivingEntityAiLoadBenchmarkTest test
```

The test drives the actual `LivingEntityAiService` target-selection/decision path and
`MobAiLoadController` distance-aware scheduler with mixed mob species and eight
synthetic player targets. It reports workloads at 100, 500, 1,000, 2,000 and 5,000
mobs over 20 simulated ticks.

Reported values:

- `decisions` — number of AI decisions actually executed after scheduling.
- `tick_p50_ms`, `tick_p95_ms`, `tick_max_ms` — measured wall time for one synthetic
  mob-processing pass, across the 20 passes.
- `ai_avg_us`, `ai_max_us` — time spent inside the AI decision algorithm.
- `deferred` — mob updates skipped by the distance-aware scheduler.

The benchmark is deterministic in workload shape, but wall-clock values depend on the
CI runner/JVM and are diagnostic rather than pass/fail thresholds. It is **not** a
live-server stress test: it does not start a Bedrock listener, authenticate real clients,
run the full world tick/physics pipeline, or measure process CPU/RAM and server TPS.
Use it to compare AI/scheduler changes; use a separate real-client server soak test
before making capacity or production-readiness claims.


## Full world entity-tick benchmark

Run the end-to-end in-process world coordinator workload:

```bash
mvn -f server/nexora-core/pom.xml -Dtest=WorldEntityTickLoadBenchmarkTest test
```

Unlike the isolated AI probe above, this workload registers mobs in the real
`WorldLivingEntityManager`, updates `WorldChunkResidencyManager`, filters resident
entities through `WorldEntityTickCoordinator`, and executes `WorldTickPipeline`
including nearest-player targeting, priority scheduling, AI decisions and bounded
profiler sampling. It repeats 20 ticks at 100, 500, 1,000, 2,000 and 5,000 entities,
with eight synthetic player targets and a resident 8-chunk view distance.

It reports coordinator p50/p95/max, targeting p95, decision p95, produced decisions,
deferred work and resident chunk count. The profiler retains a bounded recent window,
so subsystem sample counts are intentionally capped.

This still does not start the Bedrock transport/session stack, run collision physics,
apply movement decisions to a live world, measure OS process CPU/RSS, or calculate
server TPS/MSPT. It is a more complete world-runtime benchmark, not a real-client
capacity test.
