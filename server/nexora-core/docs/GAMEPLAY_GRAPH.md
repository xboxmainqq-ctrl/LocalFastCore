# NEXORA Gameplay Graph

Gameplay Graph is the high-level composition layer above the runtime Work Graph.

It allows independent gameplay systems to describe one action as a dependency chain:

Class / RPG state -> Magic -> Combat -> Effects

The graph does not implement damage, spells, classes or effects. It only describes their execution relationship and passes their contracts to the runtime.

This keeps gameplay modules independent while allowing NEXORA to schedule the combined action as one coherent graph.

Example:

class-check
  -> spell-cast
    -> damage
      -> effect

A future implementation can add inventory, cooldowns, quests, elemental reactions, boss phases and world events to the same graph without making those systems depend directly on each other.
