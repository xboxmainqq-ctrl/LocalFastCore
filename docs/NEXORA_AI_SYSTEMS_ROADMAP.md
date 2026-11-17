# NEXORA AI Systems Roadmap

Status: user-confirmed product direction, 2026-11-17. This roadmap separates implemented foundations from incomplete capabilities.

## Product boundaries

NEXORA is a standalone general-purpose block-world Core and dedicated server. There is no Android client and no separate RPG server/product in the current plan. Do not revive AI mods: the user cancelled that direction.

Three AI capabilities remain in scope:

1. Native Mob AI in the Core.
2. An optional Core AI Assistant for server operators/developers.
3. A separate optional Moderation AI Assistant.

## 1. NEXORA Mob AI — native simulation subsystem

The Core owns authoritative mob decisions and state. Initial capabilities, delivered incrementally:

- Perception: nearby players/entities, visibility/line of sight, sound or damage stimuli where supported.
- Decision making: target selection, threat/retreat, idle, patrol, follow, defend and attack goals.
- Navigation: bounded pathfinding over loaded, traversable world cells; avoid unloaded-world stalls.
- Movement integration: decisions feed the same authoritative movement/collision simulation as other entities.
- Environment reactions: obstacles, hazards, water/lava and day/night only when those world systems exist.
- Extensibility: data-driven attributes/goals and a versioned behavior interface; trusted code extensions must be permissioned and resource-bounded.
- Performance: deterministic tick integration where practical, per-tick work budgets, bounded path queues, cancellation and metrics.

Acceptance stages:
1. Entity state/goal model and deterministic tests.
2. Perception and target selection tests.
3. Navigation/pathfinding tests including unreachable targets and chunk boundaries.
4. Collision-aware movement and basic idle/follow/hostile behavior.
5. Load tests proving bounded CPU, memory and path-request queues.

Initial implementation now exists: `MobAiController` is connected to the authoritative entity tick and supports explicit PASSIVE/NEUTRAL/HOSTILE profiles, nearest-player perception with line-of-sight checks, neutral provocation, collision-aware direct pursuit and attack intents. Regression tests cover pursuit, provocation and solid-wall visibility. This is an early behavior controller, not a complete mob AI: bounded pathfinding, combat/damage, richer goals, vertical navigation, persistence of profiles and load budgets remain incomplete.

## 2. NEXORA Core AI Assistant — operator/developer helper

A separately permissioned server module, not an unrestricted autonomous agent.

Planned capabilities:
- Explain server status, configuration, logs, errors and tick/chunk/entity metrics.
- Help diagnose startup, persistence, networking and extension failures.
- Answer questions using versioned NEXORA documentation and live read-only diagnostic tools.
- Suggest configuration changes and produce a reviewable change proposal.
- Execute only explicitly approved, narrowly scoped operations through audited server APIs.

Safety requirements:
- Explicit operator permissions and per-action authorization.
- Read-only by default; confirmation for every state-changing operation.
- No arbitrary shell, filesystem traversal, code execution or secret disclosure.
- Redact credentials, tokens, IP-sensitive data and private player information from prompts/logs.
- Timeouts, rate limits, bounded context and audit trail.
- If no model/provider is configured, the server Core must work normally without AI.

## 3. NEXORA Moderation AI Assistant — moderation support

A separate optional module with human moderation authority retained.

Planned capabilities:
- Summarize player reports and relevant server-side evidence.
- Detect suspicious patterns from permitted signals (chat, movement/combat telemetry and repeated reports) without treating a model score as proof.
- Prioritize cases and explain which evidence triggered a recommendation.
- Suggest warn/mute/kick/ban actions according to server policy.
- Track moderator decisions and outcomes to support review and policy tuning.

Safety and privacy requirements:
- Human moderator approval is required for punishments by default.
- No punishment based solely on an opaque model score.
- Configurable data minimization, retention and access controls.
- Audit every recommendation and moderator decision.
- Protect private messages and sensitive player data; inspect only data the server policy and permissions allow.
- Rate limits, false-positive review and appeal/evidence workflow.
- AI provider failure must never block ordinary moderation or server operation.

## Delivery order

1. Stabilize Core lifecycle, persistence, chunks, entities, physics, networking and extension API.
2. Implement Mob AI inside the authoritative entity simulation.
3. Establish a stable permissioned diagnostic/tool API.
4. Implement the Core AI Assistant as an optional module.
5. Implement the Moderation AI Assistant as an optional module with human review and auditability.
6. Add integration, load, privacy and failure-mode tests for all AI modules.

## Current implementation status

### Implemented foundations
- **Mob AI:** initial profile-based controller is wired into `EntityTickEngine`. PASSIVE mobs retain the existing deterministic wander behavior; configured NEUTRAL mobs wait for provocation; configured HOSTILE mobs detect visible nearby players and pursue them on authoritative server ticks. Attack-range decisions emit intent only; no damage is applied yet.
- **Core AI Assistant:** `AiToolRegistry` and `CoreAiAssistant` provide a provider-neutral tool boundary with per-tool permissions, explicit approval for mutating tools, and audit events that omit arguments. `AiModelProvider` is an optional provider SPI; prompts and context are bounded, sensitive context keys are rejected, and Core remains usable without a provider. A production provider and full operator conversation/tool-planning workflow are not yet included.
- **Moderation AI Assistant:** `ModerationSignalAnalyzer` and `ModerationAiAssistant` provide bounded chat-rate/repetition signals, retain only SHA-256 fingerprints rather than raw chat, support data deletion, and always mark results for human review. An optional model summary receives only risk/score/reason metadata, never raw chat or player IDs. This is a deterministic signal foundation, not a trained/model-based classifier and it does not punish players.

### Not yet implemented
- Mob pathfinding around obstacles, combat/damage, additional goals, vertical navigation, persistent profiles and load testing.
- Production AI provider adapters, operator-facing conversation workflow, live diagnostics catalogue, provider lifecycle/rate limits and a broader secure context redaction pipeline.
- Moderation case management, report ingestion, policy-aware recommendations, durable audit storage, appeals and model-based review.

The current engineering priority remains Core reliability and confirmed CI verification. Do not describe these foundations as complete AI assistants or complete mob behavior until the missing capabilities and integration tests are delivered.
