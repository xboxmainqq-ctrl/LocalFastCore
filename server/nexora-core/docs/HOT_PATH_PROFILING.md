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
profiler sampling. It warms up for 10 ticks, then measures 60 ticks at 100, 500, 1,000, 2,000 and 5,000 entities,
with eight synthetic player targets and a resident 8-chunk view distance.

It reports coordinator p50/p95/max, targeting p95, decision p95, produced decisions,
deferred work and resident chunk count. The profiler retains a bounded recent window,
so subsystem sample counts are intentionally capped.

This still does not start the Bedrock transport/session stack, run collision physics,
apply movement decisions to a live world, measure OS process CPU/RSS, or calculate
server TPS/MSPT. It is a more complete world-runtime benchmark, not a real-client
capacity test.


The entity registry is keyed by runtime ID in a concurrent sorted map. This preserves
stable runtime-ID iteration order without sorting a newly allocated entity snapshot
on every world tick. An atomic entity count keeps population checks constant-time.
The coordinator benchmark is retained to detect regressions in this hot path.


## Bedrock actor replication fanout

Actor spawn, movement, and removal broadcasts now encode and fragment each identical
Bedrock packet batch once per broadcast, then fan the prepared frames out to the
sessions whose chunk views currently track the actor. Each transport call receives
its own byte-array copy so a transport implementation cannot mutate another
recipient's payload. This removes repeated batch construction, compression and
fragmentation work from the per-recipient loop without changing packet ordering,
reliability, or visibility decisions.

This is an implementation-level optimization, not a measured throughput claim.
The existing `BedrockSessionManagerTest` coverage verifies chunk-view spawn,
despawn and stale-movement behavior. A dedicated fanout benchmark with multiple
real Bedrock clients is still required to quantify network-thread cost, queueing,
bytes per second and end-to-end tick latency.


## Spatial target lookup and actor replication indexes

The production Bedrock mob tick now builds one immutable `PlayerTargetIndex` per
world tick. It partitions alive players into 128-block cells; each mob checks only
the surrounding 3x3 cells and performs exact 3D distance checks. This preserves
the exact nearest target within 128 blocks, including the boundary. When no player
is within 128 blocks, the lookup returns no target; this is equivalent for the
current 16-block hostile acquisition range and 128-block hostile despawn threshold.
The same index resolves a remembered provoker by network ID in constant expected time.

Bedrock actor replication now maintains reverse indexes from chunk to subscribed
sessions and from actor runtime ID to sessions that currently track it. Movement
fanout visits current-chunk subscribers plus previous actor viewers, rather than
walking every connected session for every actor update. Spawn, despawn and chunk-view
reconciliation keep these indexes synchronized; disconnect and shutdown clear them.
Encoded spawn, movement and removal batches are still reused across recipients.

Synthetic microbenchmark:
`mvn -f server/nexora-core/pom.xml -Dtest=PlayerTargetIndexBenchmarkTest test`

It compares the full player scan against the production index for 1,000 mobs and
8/64/256/1,024 synthetic players, checks result equivalence, and reports average
per-mob lookup time plus index-build time. These figures are microbenchmark results,
not live server TPS or a guarantee for dense player clusters. The Bedrock actor
replication test also verifies a mob crossing between two client chunk views and
the reverse-index cleanup on disconnect.


One observed GitHub CI microbenchmark run (1,000 mobs, four measured rounds; average
lookup time per mob; environment-dependent):

| Synthetic players | Full scan | Spatial index | Index build |
|---:|---:|---:|---:|
| 8 | 0.221 us | 0.237 us | 0.011 ms |
| 64 | 1.341 us | 0.267 us | 0.083 ms |
| 256 | 1.023 us | 0.296 us | 0.235 ms |
| 1,024 | 2.535 us | 0.225 us | 0.630 ms |

The index has a small fixed cost for very small player counts, but in this run it
reduced per-mob lookup time by roughly 5x at 64 players and 11x at 1,024 players.
These are synthetic lookup timings, not whole-tick or live-server speedups.
