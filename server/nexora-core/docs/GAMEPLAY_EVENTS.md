# NEXORA Gameplay Events

Gameplay modules communicate through typed events instead of direct dependencies.

Example flow:

SPELL_CAST
  -> ELEMENT_REACTION
  -> DAMAGE
  -> STATUS_EFFECT
  -> QUEST_PROGRESS

Handlers are deterministic and receive events in registration order.

The event bus is intentionally not a scheduler. If a handler needs expensive work, it should create or submit a Work Graph task so Adaptive Runtime can decide when that work executes.

This keeps event propagation predictable while allowing expensive consequences to remain adaptive.
