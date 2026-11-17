# NEXORA — permanent project brief

This file is the canonical architecture hand-off. It supersedes historical product descriptions elsewhere in the repository where they conflict with the current user-confirmed direction.

## Product goal

Build NEXORA as an original, standalone block-world engine and dedicated server. The active scope is the server/Core, its world and entity simulation, networking, persistence, extensibility and three native/optional AI capabilities.

## Confirmed scope

- Standalone, general-purpose block-world Core and dedicated server.
- Native Mob AI integrated into the existing authoritative entity simulation.
- Optional Core AI Assistant for server operators/developers.
- Optional Moderation AI Assistant; human moderators retain punishment authority.
- No Android client product or Android build pipeline in the active scope.
- No separate RPG server/product or RPG gameplay expansion in the active scope.
- No AI mods, no Luanti integration.
- Historical Android/RPG experiments may remain in the repository as inactive reference material.
- Do not copy Nukkit/PowerNukkitX or proprietary Minecraft source, assets, or implementation. Public documentation and observable behavior may inform clean-room design.

## Architecture principles

1. **One authoritative server simulation.** The world tick owns entity movement and state changes. Mob AI must integrate with existing entity simulation; never add a parallel mob population, tick loop, or competing controller architecture.
2. **World and persistence first.** World/chunk/block state, generation, loading, save/recovery and lifecycle must remain bounded and deterministic where practical.
3. **Server-authoritative entities.** Entity registry, movement, collision, inventory and interactions are validated by the Core, not trusted to clients.
4. **Safe networking.** Keep transport DTOs separate from domain objects; validate authentication, protocol versions, payload sizes and request rates.
5. **Versioned extensions.** Content and extension APIs need validation, permissions, bounded resources, compatibility rules and rollback. Java extensions are trusted code unless a real sandbox is implemented.
6. **Optional AI.** Core operation must not depend on an external model provider. AI tools require permission checks, audit records and explicit approval for mutating operations. Moderation recommendations require human review by default.
7. **No false compatibility claims.** Experimental Bedrock-facing codecs do not prove compatibility. Do not promise unmodified client support without pinned-version real-client integration tests.

## Existing implementation foundations

- Java/Maven server runtime, lifecycle, runtime modules and deterministic game loop.
- World registry, sparse chunk/block storage, generation and bounded asynchronous chunk pipeline.
- Versioned NEXORA .nxw persistence with validation/checksums and atomic replacement.
- Entity registry and authoritative entity tick simulation.
- Existing MobAiController called by EntityTickEngine; PASSIVE/NEUTRAL/HOSTILE profiles, perception/line-of-sight, neutral provocation, deterministic passive wandering and bounded ground-plane A* pursuit and short-lived last-known-target memory are implemented foundations.
- AiToolRegistry, optional CoreAiAssistant provider boundary and lifecycle module.
- ModerationSignalAnalyzer, optional ModerationAiAssistant and lifecycle module.
- Experimental Bedrock and NEXORA protocol/network adapters; not proof of production readiness.

## Current development priorities

1. Restore reliable CI execution and obtain a confirmed Maven clean verify.
2. Harden world persistence and backup/recovery behavior.
3. Finish world lifecycle and chunk-state transitions, cancellation, retries and backpressure.
4. Complete authoritative entity/player movement, collision, sessions and interactions.
5. Harden networking, authentication, protocol versioning and abuse limits.
6. Stabilize versioned extension/content APIs and server packaging.
7. Continue native Mob AI inside the existing entity simulation: vertical navigation, hazards, combat integration, persistent profiles and aggregate tick budgets.
8. Complete optional operator and moderation assistants with privacy, audit, permission and failure-mode tests.

## Mob AI contract

- Mob decisions run only when the existing authoritative entity tick advances.
- MobAiController may own bounded per-mob decision/navigation state, but must not own a thread, wall clock, world tick or duplicate entity registry.
- Preserve current passive wander behavior and configured PASSIVE/NEUTRAL/HOSTILE semantics.
- Navigation must use loaded world data only, have hard node/time budgets, and fail safely when no route exists.
- Combat decisions may emit intents, but damage must be applied by the authoritative combat/gameplay service once integrated.
- Do not add another MobAiController or separate AI subsystem; extend the existing implementation.

## AI assistant safety contract

- AI modules are optional and can be disabled without affecting ordinary server operation.
- Tool calls are permission-checked; mutating actions require explicit operator approval.
- Audit records must not leak secrets or unnecessary player data.
- Moderation AI provides evidence-linked recommendations; humans decide punishments.
- Provider absence, timeout or failure must fail closed for AI actions without blocking the server.

## Working rules

- Read this file, README.md, docs/CORE_DEVELOPMENT_LOG.md and docs/NEXORA_AI_SYSTEMS_ROADMAP.md before changes.
- Work on the active branch; inspect code/tests/CI before modifying architecture.
- Make coherent commits, add regression tests and run the narrowest relevant tests plus Maven clean verify when CI is usable.
- Keep docs synchronized with code. Clearly distinguish implemented, tested, device-tested and planned.
- Never claim tests pass without actual successful test output.
- The user primarily works from an Android phone; prefer GitHub Actions artifacts when a successful build is available.