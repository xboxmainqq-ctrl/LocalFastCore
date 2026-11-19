# NEXORA: independent Nukkit-oriented architecture

## Project rule

NEXORA is an independent Bedrock server implementation. Nukkit is a behavioral
and compatibility reference, not a source-code dependency. No Nukkit source
files, copied implementations, or translated blocks of Nukkit code are to be
introduced into this repository.

## What "based on Nukkit" means

- Preserve familiar server concepts where they improve plugin and operator
  compatibility: worlds, chunks, players, entities, events, commands,
  permissions, inventories, and scheduled tasks.
- Define NEXORA-owned interfaces and data types under `ru.nexora`.
- Implement protocol handling against documented Bedrock/RakNet/NetherNet
  specifications and independently maintained codec/transport libraries.
- Build compatibility as an explicit adapter layer. Translate supported
  plugin calls and events into NEXORA services; never make the runtime inherit
  from Nukkit classes or require Nukkit at runtime.
- Keep unsupported or semantically different API calls explicit rather than
  silently pretending full compatibility.

## Runtime boundaries

1. **Transport** owns sockets, sessions, reliability, encryption, and packet IO.
2. **Protocol** owns Bedrock packet codecs and version negotiation.
3. **Gameplay** owns authoritative player, entity, inventory, and world rules.
4. **Simulation** owns the single-threaded tick and deterministic state changes.
5. **Storage** owns persistence and asynchronous IO boundaries.
6. **Compatibility API** owns Nukkit-shaped facades, events, and plugin adapters.
7. **Extensions** run behind stable NEXORA interfaces and cannot mutate world
   state from arbitrary worker threads.

## Performance constraints

- Network and disk workers must not block the simulation thread.
- Mutable world state has one authoritative owner: the simulation thread.
- Cross-thread work is submitted through bounded queues with explicit
  back-pressure and cancellation.
- Tick scheduling uses a monotonic clock, bounded catch-up, and measurable
  tick budgets.
- Hot-path snapshots must avoid unnecessary sorting and repeated registry
  lookups; public deterministic snapshots remain available where ordering is
  part of the API contract.
- Performance claims require repeatable benchmarks and correctness tests.

## Licensing and provenance

Every new subsystem must be authored independently or consumed through a
properly declared external dependency with its license preserved. Before
adding a dependency, verify its license, maintenance status, protocol
compatibility, and transitive dependency footprint.
