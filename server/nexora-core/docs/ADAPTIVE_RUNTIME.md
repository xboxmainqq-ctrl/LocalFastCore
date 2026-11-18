# NEXORA Adaptive Runtime

NEXORA does not execute every subsystem as if it had equal importance. The Adaptive Runtime treats each tick as a scarce execution window and admits work through priority lanes.

Lanes:
- PLAYER — latency-sensitive player work.
- WORLD — world simulation.
- ENTITY — entity lifecycle and updates.
- AI — decision making and navigation.
- BACKGROUND — non-critical maintenance.

The runtime reserves capacity for important lanes and keeps an emergency reserve. Non-critical work is deferred when admitting it would consume the protected budget.

This is an execution policy layer, not a replacement for the world model. Gameplay systems remain responsible for their own state and behavior.

The first implementation is intentionally deterministic and bounded. Future iterations can feed it measured hot-path cost and historical pressure without allowing background work to starve player-critical work.
