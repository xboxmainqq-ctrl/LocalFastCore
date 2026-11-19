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

| Synthetic players | Full scan | Adaptive lookup | Index build |
|---:|---:|---:|---:|
| 8 | 0.414 us | 0.215 us | 0.003 ms |
| 64 | 1.262 us | 0.334 us | 0.453 ms |
| 256 | 1.126 us | 0.486 us | 0.262 ms |
| 1,024 | 4.634 us | 0.833 us | 0.534 ms |

The adaptive path uses a direct scan for up to 16 players and the spatial grid above
that threshold. In this run it reduced per-mob lookup time by about 3.8x at 64
players and 5.6x at 1,024 players. The 8-player result is affected by microbenchmark
noise and should be read as a small-population sanity check, not a guaranteed gain.
These are synthetic lookup timings, not whole-tick or live-server speedups.


Synthetic actor-fanout index microbenchmark:
`mvn -f server/nexora-core/pom.xml -Dtest=ActorReplicationIndexBenchmarkTest test`

It compares 10,000 actor-chunk lookups against a full scan of 1,000 synthetic
session chunk sets, asserts that recipient counts match, and reports average lookup
time plus index build time. It measures recipient selection only, not packet encoding,
transport queueing or real client delivery.


One observed GitHub CI actor-fanout index run (1,000 synthetic sessions, 10,000
actor-chunk lookups):

| Lookup strategy | Average per actor |
|---|---:|
| Scan every session's chunk set | 29.981 us |
| Reverse chunk-viewer index | 0.826 us |

Index build time for the 1,000 session views was 17.166 ms. The measured lookup
was roughly 36x faster in this run, with identical aggregate recipient counts
(85,900). This measures index selection only; it excludes packet serialization,
network queues and real Bedrock clients.


## Mob physics registry writes

The mob physics hot path now commits position and velocity with one
`WorldLivingEntityManager.updatePhysicsState` call. When gravity, drag or knockback
changes both values, the old path constructed and published an immutable actor twice
(one velocity replacement and one position replacement); the combined path creates
one replacement and performs one registry write. If neither position nor velocity
changes, it skips the registry write entirely. Existing physics behavior and collision
checks are unchanged; the manager test verifies position, velocity, health and fire
state survive the combined replacement.


## NetherNet outbound back-pressure

The WebRTC send path now observes each data channel's buffered byte count before
submitting another frame. At 512 KiB of native WebRTC buffering, additional frames
are placed in a per-channel FIFO instead of being pushed into the SCTP send buffer.
The FIFO is bounded to 4 MiB and 2,048 frames per channel; it drains in order after
the native buffered amount falls to 256 KiB or less. Before each send, the scheduler
also accounts for the next frame's size so the projected native buffer stays below
512 KiB; a single frame larger than that watermark is allowed only when the native
buffer is empty, avoiding permanent head-of-line blocking. The queue owns its frame copies
and is cleared when the peer session closes. Reliable fragmented inbound messages
also have a 15-second monotonic assembly deadline; a stalled or backwards-time
assembly is reset and rejected instead of retaining a partial message indefinitely.
If the bounded queue is exhausted, the
session is closed rather than allowing unbounded memory growth or silently dropping
reliable Bedrock frames. Buffered-amount callbacks only schedule a coalesced drain
task on the negotiator executor; the callback itself does not call WebRTC send APIs,
avoiding re-entrant native calls on the data-channel observer thread.

This is congestion containment, not a direct ping reduction: it limits queued memory
and prevents unlimited application-side buildup, but cannot improve the player's
physical route, ISP latency, packet loss, or a saturated uplink. No end-to-end ping
or throughput claim is made. The queue's FIFO, ownership, byte/frame bounds, restore
order and cleanup are covered by `NetherNetOutboundQueueTest`; live multi-client
WebRTC congestion testing is still required.

The negotiator also requests WebRTC `getStats` snapshots in a round-robin batch,
with at most 32 connected peers sampled per second. This avoids issuing a burst of
stats requests for every player at once on larger servers. It records the nominated,
succeeded ICE candidate pair's
`currentRoundTripTime` (converted from seconds to milliseconds) and cumulative
transport bytes sent/received. Sampling is asynchronous and guarded against
overlapping requests; it does not run on the data-channel callback. This RTT is
transport/ICE round-trip time, not the Bedrock client's gameplay ping. The round-robin
cursor advances by one peer per second (rather than one whole batch), preventing
starvation when the active session count shares a divisor with the batch size. Peers
are sorted by network ID before each snapshot so the round-robin order remains stable
while the connected set is unchanged.
`transportMetrics(networkId)` exposes the latest sample; its `available()`,
`ageMillis(now)` and `stale(now, maxAge)` helpers let callers reject missing or old
measurements instead of presenting them as current ping. Byte totals are retained
when the native report contains them even if its RTT field is unavailable. The server console's
`network` command reports connected sessions, fresh/stale sample counts, average and
maximum fresh ICE RTT, timestamped sent/received transport bytes-per-second, cumulative
transport byte totals, the current bounded application outbound backlog (queued
frames/bytes and peers with queued frames), and Bedrock ingress frame/message/failure
counts with average/maximum framing-and-dispatch processing time. The inbound
anomaly observer keeps per-peer windows and updates them under peer-local locks, so
traffic from different peers does not serialize on one global accounting lock. Its
bounded peer admission/eviction path is separate from the hot update path. Ingress
timings cover the NetherNet framing/dispatch pipeline only, not the downstream Bedrock
decoder or gameplay handlers. The backlog excludes bytes already handed to the native WebRTC/SCTP
buffer; throughput rates are transport-counter deltas rather than decoded Bedrock
gameplay payload rates. Average/max RTT exclude
stale or missing samples; byte counters remain cumulative. NEXORA also derives
per-peer sent/received bytes-per-second from successive timestamped counter samples.
Rates are reported only when both counter baselines are valid, monotonically
increasing and time-aligned; counter resets or incomplete native reports invalidate
the rate until a new baseline pair is available. Stale rates are excluded from the
aggregate, and the console reports how many peers contributed. These are WebRTC
transport rates, not guaranteed gameplay payload throughput. Data-channel traffic
does not expose a trustworthy application packet-loss percentage through this
candidate-pair sample, so NEXORA does not label ICE checks or missing counters
as player packet loss. A live client test is still needed to validate which native
stats fields are populated on each deployment platform.

## Synthetic NetherNet inbound pipeline benchmark

Run the bounded ingress micro-load with:

```bash
mvn -f server/nexora-core/pom.xml -Dtest=NetherNetInboundPipelineLoadBenchmarkTest test
```

The probe drives the production frame validation, per-channel reassembler lookup,
message ownership copy and dispatch path across 64 synthetic peers and both channel
types. It reports sequential frame throughput and per-frame p50/p95/max processing
time, plus concurrent ingress throughput with 32 independent peer callbacks. It is
a JVM-side synthetic microbenchmark only: it excludes native WebRTC/SCTP, encryption,
Bedrock batch decoding, world-thread scheduling and real network conditions.

## NetherNet inbound admission and reassembly bounds

The inbound data-channel observer rejects frames larger than 1 MiB before allocating
a second byte-array copy. The inbound pipeline independently enforces the same
configurable ceiling and caps tracked per-peer/channel reassembly state at 8,192
channels. Disconnect releases that peer's channel state. Reliable fragmented messages
are limited to 4 MiB total and expire after 15 seconds of monotonic elapsed time;
invalid order, oversized fragments, and expired assemblies are reset and rejected.

These limits bound application-owned frame copies and partial reassembly state. They
do not replace native WebRTC/SCTP implementation limits, and they do not prove
protection against every transport-level denial-of-service pattern. Live multi-client
testing is still needed to validate realistic Bedrock frame sizes and throughput.
