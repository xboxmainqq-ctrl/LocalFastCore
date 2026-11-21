# NEXORA Multi-Core Architecture

Status: research and design baseline (2026-11-21)
Scope: evolve the existing NEXORA/LocalFastCore server without replacing its Bedrock session authority.

## Executive decision

NEXORA will be a single authoritative game server with multiple implementation languages and optional external services. It will **not** run multiple independent Bedrock game cores against the same player sessions, world, chunks, entities, or inventories.

The existing Java NEXORA core remains the first authority for Bedrock networking, player sessions, world state, entities, inventory, and persistence until a measured migration proves a subsystem can be transferred safely.

Other projects are research sources and potential component/library integrations. A project is not adopted merely because it is another complete server.

## What exists in the repository today

The current `server/nexora-core` Maven module targets Java 21 and builds a standalone `server.jar`. Its dependency set already includes Cloudburst NetherNet transport components and the libdatachannel Java binding. The bootstrap wires separate network, gameplay, world, diagnostics, and lifecycle modules.

Observed ownership boundaries:
- `NetworkRuntimeModule`: discovery, signaling, cluster transport, negotiation and bounded drain.
- `GameplayRuntimeModule`: Bedrock sessions, authoritative movement, item/armor/equipment registries and operator assistant.
- `WorldRuntimeModule`: world runtime (wired by the bootstrap).
- `ServerLifecycle`: reverse-order resource shutdown.
- `ServerReadiness`: explicit STARTING/READY/STOPPING/STOPPED admission state.

This is already a modular Java server, not a blank project. Preserve the existing transport and session path while extending it behind stable contracts.

## Candidate ecosystem review

| Project | Language / role | Potential value to NEXORA | Initial decision |
|---|---|---|---|
| PowerNukkitX | Java Bedrock server | Bedrock protocol serializers, item/block/entity behavior, world generation, AI and compatibility references | Study and selectively reuse compatible libraries or ideas; do not replace the running session authority |
| Cloudburst Protocol / Network | Java protocol and networking libraries | Packet codecs, RakNet/Netty primitives and protocol reference implementations | High-priority technical reference; check exact artifact versions and dependency conflicts before adding anything |
| Dragonfly | Go Bedrock server/library | Asynchronous server design, entity/world APIs and Go ecosystem patterns | Research/reference first; a separate Go service only if a clearly bounded subsystem justifies it |
| Chorus | Rust Bedrock server | Rust protocol/model architecture and safe high-performance implementation patterns | Research/reference; not a drop-in Rust module for the Java runtime |
| Endstone | C++/Python extension framework over official BDS | Python/C++ plugin API ideas, event design and vanilla behavior comparison | Reference only; it is based on official BDS and is not a library to embed in this Java server |
| LeviLamina | C++ mod loader for official BDS | Native hooks and mod-loader architecture | Not an initial dependency; requires the BDS runtime and has platform/runtime constraints |
| Serenity | Rust + TypeScript Bedrock server | Multi-language design and TypeScript tooling ideas | Research/reference only until API, maturity and compatibility are independently verified |

## Integration rules

1. One authoritative owner per mutable game object. Java owns live player sessions, world/chunk state, entities and inventory initially.
2. Cross-language calls use versioned contracts, not shared mutable memory.
3. Every external module declares API version, capability set, health state, timeout, resource limits and shutdown behavior.
4. Events crossing process boundaries are versioned, bounded, authenticated and idempotent where retries are possible.
5. No module may write player/world persistence directly unless it owns that data domain and uses the approved storage contract.
6. Rust/C++ are introduced only after profiling identifies a CPU-bound or low-level bottleneck.
7. Lua is the preferred embedded gameplay scripting layer for spells, quests, bosses, pets, NPC behavior and events. Scripts receive capabilities through a sandboxed API and never receive unrestricted access to server internals.
8. Python and TypeScript belong in separate operator, analytics, build and tooling services; neither is placed on the per-tick gameplay hot path.
9. Do not add a second Bedrock listener or second RakNet/NetherNet stack to the production process without a separately designed proxy or migration.
10. Every adopted dependency requires license, maintenance, protocol-version, security and transitive-dependency review.

## Proposed target layout

- Java 21: authoritative Bedrock runtime, player/session lifecycle, world/chunk/entity/inventory ownership, persistence coordination and module host.
- Lua: sandboxed gameplay content and behavior scripts.
- Rust: optional native/sidecar workers for measured hot spots (pathfinding batches, procedural generation jobs, compression or other isolated compute). Begin with a narrow request/response contract; do not move authoritative state.
- C++: only for a demonstrated native integration need; avoid duplicating Rust's role.
- TypeScript: admin web panel and management API.
- Python: offline analytics, balancing tools, data migration and operational automation.
- SQL: durable relational records where appropriate; binary/world storage remains behind a dedicated storage API.

## Phased implementation

### Phase 0 — baseline and compatibility inventory
- Record the exact current main commit, build/test status and Bedrock protocol support.
- Inventory existing packages, module lifecycle, persistence boundaries and thread ownership.
- Produce a dependency/license register for every candidate component.
- Define performance baselines before introducing native or external runtimes.

### Phase 1 — module contracts (Java only)
- Define module manifest, API versioning, capability permissions, lifecycle, health and diagnostics.
- Define typed event envelopes and bounded command/query interfaces.
- Add contract tests and a no-op reference module.
- Keep the current Bedrock networking and session implementation unchanged.

### Phase 2 — Lua gameplay runtime
- Embed a maintained Lua runtime behind the module contract.
- Add script sandboxing, quotas, deterministic tick scheduling, reload rules and fault isolation.
- Prototype one non-critical gameplay feature before moving magic, quests, bosses, pets or NPC logic.

### Phase 3 — Rust worker proof of concept
- Benchmark a real hot spot and select exactly one isolated workload.
- Implement a Rust worker with a versioned protocol, bounded queues, deadlines, cancellation and restart behavior.
- Compare correctness and performance against the Java implementation; retain Java fallback.
- Promote only if the benchmark demonstrates a meaningful end-to-end gain.

### Phase 4 — management and analytics
- Expose a narrow authenticated management API.
- Add TypeScript admin UI and Python offline tools without granting them direct game-state ownership.

### Phase 5 — selective upstream component adoption
- For each candidate, record the exact upstream commit/artifact, license obligations, protocol support, patched files and regression tests.
- Prefer standalone libraries over copying large portions of another server.
- Never merge upstream code without preserving notices and checking license compatibility.

## Non-goals

- No simultaneous authoritative PowerNukkitX + Cloudburst + NEXORA world.
- No language collection for its own sake.
- No premature C++/Rust rewrite of stable Java networking.
- No unbounded scripting, arbitrary native plugin loading or direct external writes to world/player files.

## Candidate source links

- PowerNukkitX: https://github.com/PowerNukkitX/PowerNukkitX
- PowerNukkitX Protocol: https://github.com/PowerNukkitX/Protocol
- Cloudburst: https://github.com/CloudburstMC/Cloudburst
- Cloudburst Nukkit: https://github.com/CloudburstMC/Nukkit
- Dragonfly: https://github.com/df-mc/dragonfly
- Chorus (Rust): https://github.com/bedrock-crustaceans/Chorus
- Endstone: https://github.com/EndstoneMC/endstone
- LeviLamina: https://github.com/LiteLDev/LeviLamina
- Serenity: https://github.com/SerenityJS/serenity

This document is an architecture baseline, not evidence that any upstream code has been integrated or that a performance improvement has been measured.
