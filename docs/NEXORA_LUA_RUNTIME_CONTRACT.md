# NEXORA Lua Runtime Contract

Status: implementation specification; no Lua engine is enabled yet.
Target: Java 21, NEXORA Module API v1.

## 1. Decision

Lua is the content and behavior layer for spells, quests, bosses, pets, NPC behavior and scripted events. Java remains authoritative for player sessions, world/chunk state, entities, inventory, combat resolution and persistence.

The Lua runtime must be an adapter behind the Java module contract. A script is never a Java module and never receives a raw server, player, world, inventory, entity, logger implementation or filesystem object.

## 2. Trust boundary

The initial runtime is for operator-installed server content, not arbitrary untrusted player uploads. Removing standard libraries is useful hardening but is not, by itself, a security boundary against hostile code. If untrusted scripts are ever required, run them in a separately constrained process/container with OS-level limits and an authenticated, narrow IPC protocol.

Never expose Java reflection, class loading, JNI, luajava, package loading or dynamic Java bindings; io, os, debug, unrestricted package, process execution or network clients; server internals, mutable Java collections, filesystem paths or database connections; direct writes to world, player, entity, inventory or economy persistence.

Expose only explicit, versioned host functions. Each host function validates arguments, checks the module capability, enforces a deadline/size bound and returns immutable primitive data or a documented result value.

## 3. Runtime adapter boundary

The implementation should introduce these concepts without coupling the module API to one Lua vendor:
- ScriptRuntimeFactory: creates an isolated runtime instance for one script/module.
- ScriptRuntime: loads a source unit, invokes named callbacks, reports health and closes resources.
- ScriptLimits: maximum source bytes, memory budget, instruction budget, callback wall-time budget, scheduled callbacks and emitted events.
- ScriptHostApi: the only bridge from script to Java-owned gameplay services.
- ScriptDiagnostic: script ID, callback, source location where available, duration, outcome and bounded error text.

The adapter must support a runtime implementation/version identifier so the engine can be changed without changing gameplay script contracts.

## 4. Script lifecycle

1. Discover scripts only in the configured scripts directory.
2. Validate manifest ID, API version, dependencies and requested capabilities.
3. Reject duplicate IDs, missing dependencies, dependency cycles and undeclared capabilities before executing source.
4. Create a fresh isolated environment for each script.
5. Load and validate every script before enabling any script.
6. Enable scripts in deterministic dependency order.
7. On failure, disable already-enabled scripts in reverse order and report the failing script.
8. On shutdown, disable in reverse dependency order.
9. Reload is transactional: prepare and validate a replacement first; swap only at a safe tick boundary; retain the previous version if preparation fails.

A script failure must not terminate the Bedrock networking loop or leave partially applied state.

## 5. Capabilities

Capabilities are granted by server configuration, never self-approved by a script. Initial gameplay capabilities should be narrow and split by domain:
- GAMEPLAY_EVENTS_READ
- SPELLS_REGISTER
- QUESTS_REGISTER
- NPC_BEHAVIOR_REGISTER
- PETS_REGISTER
- PLAYER_EFFECTS_REQUEST
- WORLD_QUERIES_READ

Do not grant generic world mutation, inventory mutation, network access, operator APIs or storage access to gameplay scripts. A requested capability that is not granted is a startup error.

Host methods such as applying damage or granting an item must be requests to Java's authoritative gameplay services, not direct state mutation. Java validates permissions, target state, cooldowns, anti-cheat constraints and persistence.

## 6. Determinism and tick integration

- Scripts do not create their own threads.
- Scripts do not sleep or block waiting for I/O.
- Timers are scheduled by the Java tick/task scheduler and are bounded.
- Script callbacks execute on an explicitly selected server-owned executor; world mutations are marshalled to the authoritative game thread.
- Every callback has an instruction budget and wall-time deadline. Exceeding either aborts that callback, records diagnostics and applies a configurable disable/quarantine policy.
- Event queues are bounded; overflow is observable and follows an explicit drop/reject policy.
- Randomness, if exposed, is supplied through a host API with documented deterministic or non-deterministic semantics.

## 7. API compatibility

- Script API versions are independent from the Lua engine version.
- The manifest declares apiVersion; incompatible versions fail closed.
- Public host API changes require compatibility tests and a migration note.
- Scripts use stable IDs for spells, quests, NPC behaviors and pets; display names are localizable data, not identifiers.
- No script can register duplicate IDs or override another module without an explicit priority/ownership rule.

## 8. Dependency evaluation gate

Before selecting an engine, verify exact artifact coordinates and immutable version; supported Lua language version and syntax; maintenance and security history; license and transitive dependency tree; ability to create isolated environments without Java bridge/reflection; enforceable instruction and memory limits; reproducible behavior under Java 21; startup, reload and shutdown behavior under load.

A JVM interpreter may be a useful first adapter, but an interpreter that exposes Java bindings by default or cannot enforce resource limits must not be described as a hostile-code sandbox. Lua 5.2-compatible engines must not silently be advertised as Lua 5.4/5.5.

## 9. Acceptance tests

The first production-ready prototype must demonstrate:
- script cannot access Java classes, reflection, files, environment variables, processes or sockets;
- script cannot exceed configured instruction/time/memory/queue limits without being stopped or quarantined;
- one broken script does not stop other scripts or the server;
- failed reload leaves the previous active script intact;
- dependency order and reverse shutdown are deterministic;
- undeclared and ungranted capabilities are rejected;
- world/player/inventory mutations pass through Java validation;
- script API compatibility and runtime version are visible in diagnostics.

## 10. First gameplay proof of concept

Start with a non-critical spell definition and cooldown/effect request:
- Lua declares spell ID, display key, mana cost, cooldown and target mode.
- Java validates the definition and registers it.
- On cast, Java validates player state, mana, cooldown, target and permissions.
- Lua may choose among bounded, predeclared effect requests; Java applies the authoritative result.
- If the script fails or times out, the cast is cancelled safely and no partial cost/effect is committed.

Do not migrate combat, movement, inventory, persistence or world simulation into Lua as part of the first prototype.