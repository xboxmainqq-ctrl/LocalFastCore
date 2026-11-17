package ru.nexora.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Deterministic, server-authoritative perception and goal controller for configured mobs.
 *
 * <p>This first controller provides passive, neutral and hostile profiles,
 * nearest-player perception, provocation memory and collision-aware direct
 * pursuit. It intentionally does not perform combat damage; it emits an
 * attack intent for a later combat system.</p>
 */
public final class MobAiController {
    private static final double DEFAULT_DETECTION_RANGE = 16.0;
    private static final double DEFAULT_ATTACK_RANGE = 1.6;
    private static final double DEFAULT_MOVE_SPEED = 0.10;
    private static final int MAX_PATH_NODES = 512;
    private static final long TARGET_MEMORY_TICKS = 100;
    private static final int[][] PATH_NEIGHBORS = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};

    private final Map<UUID, Behavior> behaviors = new HashMap<>();
    private final Map<UUID, UUID> provokedTargets = new HashMap<>();
    private final Map<UUID, Decision> decisions = new HashMap<>();
    private final Map<UUID, NavigationState> navigation = new HashMap<>();
    private final Map<UUID, TargetMemory> targetMemory = new HashMap<>();
    private final double detectionRange;
    private final double attackRange;
    private final double moveSpeed;

    public MobAiController() {
        this(DEFAULT_DETECTION_RANGE, DEFAULT_ATTACK_RANGE, DEFAULT_MOVE_SPEED);
    }

    public MobAiController(double detectionRange, double attackRange, double moveSpeed) {
        if (!Double.isFinite(detectionRange) || detectionRange < 1 || detectionRange > 128
                || !Double.isFinite(attackRange) || attackRange < 0.5 || attackRange > detectionRange
                || !Double.isFinite(moveSpeed) || moveSpeed <= 0 || moveSpeed > 0.5) {
            throw new IllegalArgumentException("Invalid mob AI range or movement speed");
        }
        this.detectionRange = detectionRange;
        this.attackRange = attackRange;
        this.moveSpeed = moveSpeed;
    }

    /** Assigns an explicit behavior profile. Unconfigured mobs keep legacy passive wandering. */
    public synchronized void setBehavior(UUID mobId, Behavior behavior) {
        Objects.requireNonNull(mobId, "mobId");
        Objects.requireNonNull(behavior, "behavior");
        behaviors.put(mobId, behavior);
        decisions.remove(mobId);
        if (behavior != Behavior.NEUTRAL) {
            provokedTargets.remove(mobId);
        }
        if (behavior != Behavior.HOSTILE) {
            targetMemory.remove(mobId);
            navigation.remove(mobId);
        }
    }

    public synchronized boolean hasBehavior(UUID mobId) {
        return behaviors.containsKey(Objects.requireNonNull(mobId, "mobId"));
    }

    /** PASSIVE profiles use the Core's existing deterministic wander goal. */
    public synchronized boolean controlsBehavior(UUID mobId) {
        Behavior behavior = behaviors.get(Objects.requireNonNull(mobId, "mobId"));
        return behavior != null && behavior != Behavior.PASSIVE;
    }

    /** Records a valid player as the temporary target of a neutral mob. */
    public synchronized void provoke(UUID mobId, UUID playerId) {
        Objects.requireNonNull(mobId, "mobId");
        Objects.requireNonNull(playerId, "playerId");
        if (behaviors.get(mobId) != Behavior.NEUTRAL) {
            throw new IllegalStateException("Only neutral mobs can be provoked");
        }
        provokedTargets.put(mobId, playerId);
    }

    public synchronized Optional<Decision> lastDecision(UUID mobId) {
        return Optional.ofNullable(decisions.get(Objects.requireNonNull(mobId, "mobId")));
    }

    /** Called by the authoritative entity tick; never advances a separate clock or thread. */
    public synchronized Decision tick(WorldEntity mob, long tick, EntityRegistry entities, World world) {
        Objects.requireNonNull(mob, "mob");
        Objects.requireNonNull(entities, "entities");
        Objects.requireNonNull(world, "world");
        if (mob.type() != WorldEntity.Type.MOB || tick < 1) {
            throw new IllegalArgumentException("Mob AI requires a mob entity and a positive server tick");
        }

        Behavior behavior = behaviors.getOrDefault(mob.id(), Behavior.PASSIVE);
        if (behavior == Behavior.PASSIVE) {
            return remember(mob.id(), new Decision(State.IDLE, null, false, false));
        }

        boolean targetVisible = true;
        WorldEntity target;
        if (behavior == Behavior.HOSTILE) {
            target = nearestVisiblePlayer(mob, entities, world);
            if (target != null) {
                targetMemory.put(mob.id(), new TargetMemory(target, tick));
            } else {
                TargetMemory memory = targetMemory.get(mob.id());
                if (memory == null || tick - memory.lastSeenTick > TARGET_MEMORY_TICKS
                        || entities.find(memory.lastSeenTarget.id()).isEmpty()) {
                    targetMemory.remove(mob.id());
                    navigation.remove(mob.id());
                    return remember(mob.id(), new Decision(State.IDLE, null, false, false));
                }
                target = memory.lastSeenTarget;
                targetVisible = false;
            }
        } else {
            target = provokedPlayer(mob, entities);
        }

        if (target == null) {
            if (behavior == Behavior.NEUTRAL) {
                provokedTargets.remove(mob.id());
            }
            return remember(mob.id(), new Decision(State.IDLE, null, false, false));
        }

        double dx = target.x() - mob.x();
        double dy = target.y() - mob.y();
        double dz = target.z() - mob.z();
        double distanceSquared = dx * dx + dy * dy + dz * dz;
        if (distanceSquared <= attackRange * attackRange) {
            if (targetVisible) {
                return remember(mob.id(), new Decision(State.ATTACK, target.id(), false, true));
            }
            return remember(mob.id(), new Decision(State.SEARCH, target.id(), false, false));
        }

        return navigateToward(mob, target, entities, world);
    }

    /** Drops all controller state for entities that no longer exist. */
    public synchronized void retainEntities(EntityRegistry entities) {
        Objects.requireNonNull(entities, "entities");
        behaviors.keySet().removeIf(id -> entities.find(id).isEmpty());
        provokedTargets.keySet().removeIf(id -> entities.find(id).isEmpty());
        decisions.keySet().removeIf(id -> entities.find(id).isEmpty());
        navigation.keySet().removeIf(id -> entities.find(id).isEmpty());
        targetMemory.keySet().removeIf(id -> entities.find(id).isEmpty()
                || entities.find(targetMemory.get(id).lastSeenTarget.id()).isEmpty());
    }

    /**
     * Follows a bounded deterministic A* route on the loaded ground plane.
     * The path is cached per mob and rebuilt when its target enters another block.
     */
    private Decision navigateToward(WorldEntity mob, WorldEntity target,
                                    EntityRegistry entities, World world) {
        int targetX = floor(target.x());
        int targetZ = floor(target.z());
        NavigationState route = navigation.get(mob.id());
        if (route == null || route.targetX != targetX || route.targetZ != targetZ) {
            route = findPath(mob, target, world);
            if (route == null) {
                navigation.remove(mob.id());
                return remember(mob.id(), new Decision(State.STUCK, target.id(), false, false));
            }
            navigation.put(mob.id(), route);
        }

        while (!route.path.isEmpty()) {
            PathNode reached = route.path.peekFirst();
            double remainingX = reached.x + 0.5 - mob.x();
            double remainingZ = reached.z + 0.5 - mob.z();
            if (remainingX * remainingX + remainingZ * remainingZ > moveSpeed * moveSpeed * 0.25) {
                break;
            }
            route.path.removeFirst();
        }
        if (route.path.isEmpty()) {
            navigation.remove(mob.id());
            return remember(mob.id(), new Decision(State.STUCK, target.id(), false, false));
        }

        PathNode waypoint = route.path.peekFirst();
        double waypointX = waypoint.x + 0.5;
        double waypointZ = waypoint.z + 0.5;
        double stepX = waypointX - mob.x();
        double stepZ = waypointZ - mob.z();
        double distance = Math.sqrt(stepX * stepX + stepZ * stepZ);
        if (distance < 0.000001) {
            route.path.removeFirst();
            return remember(mob.id(), new Decision(State.CHASE, target.id(), false, false));
        }

        double scale = Math.min(moveSpeed, distance) / distance;
        double nextX = mob.x() + stepX * scale;
        double nextZ = mob.z() + stepZ * scale;
        if (!hasSafeFooting(world, nextX, mob.y(), nextZ)) {
            navigation.remove(mob.id());
            return remember(mob.id(), new Decision(State.STUCK, target.id(), false, false));
        }

        double yaw = Math.toDegrees(Math.atan2(-(nextX - mob.x()), nextZ - mob.z()));
        boolean moved = entities.updatePosition(mob.id(), nextX, mob.y(), nextZ, yaw);
        return remember(mob.id(), new Decision(State.CHASE, target.id(), moved, false));
    }

    private NavigationState findPath(WorldEntity mob, WorldEntity target, World world) {
        PathNode start = new PathNode(floor(mob.x()), floor(mob.z()));
        int targetX = floor(target.x());
        int targetZ = floor(target.z());
        PriorityQueue<SearchNode> open = new PriorityQueue<>(
                Comparator.comparingInt(SearchNode::score)
                        .thenComparingInt(SearchNode::heuristic)
                        .thenComparingInt(node -> node.position.x)
                        .thenComparingInt(node -> node.position.z));
        Map<PathNode, Integer> costs = new HashMap<>();
        Map<PathNode, PathNode> parents = new HashMap<>();
        Set<PathNode> closed = new HashSet<>();
        open.add(new SearchNode(start, 0, pathHeuristic(start, target.x(), target.z())));
        costs.put(start, 0);

        PathNode goal = null;
        int expanded = 0;
        int maxRadius = (int) Math.ceil(detectionRange);
        while (!open.isEmpty() && expanded < MAX_PATH_NODES) {
            SearchNode current = open.remove();
            if (!closed.add(current.position)) {
                continue;
            }
            expanded++;

            double centerDx = current.position.x + 0.5 - target.x();
            double centerDz = current.position.z + 0.5 - target.z();
            if (centerDx * centerDx + centerDz * centerDz <= attackRange * attackRange) {
                goal = current.position;
                break;
            }

            for (int[] offset : PATH_NEIGHBORS) {
                PathNode next = new PathNode(current.position.x + offset[0],
                        current.position.z + offset[1]);
                int fromStartX = next.x - start.x;
                int fromStartZ = next.z - start.z;
                if (fromStartX * fromStartX + fromStartZ * fromStartZ > maxRadius * maxRadius
                        || closed.contains(next)
                        || !hasSafeFooting(world, next.x + 0.5, mob.y(), next.z + 0.5)) {
                    continue;
                }

                int nextCost = current.cost + 1;
                Integer knownCost = costs.get(next);
                if (knownCost != null && knownCost <= nextCost) {
                    continue;
                }
                costs.put(next, nextCost);
                parents.put(next, current.position);
                open.add(new SearchNode(next, nextCost,
                        pathHeuristic(next, target.x(), target.z())));
            }
        }

        if (goal == null || goal.equals(start)) {
            return null;
        }
        List<PathNode> reversed = new ArrayList<>();
        PathNode cursor = goal;
        while (!cursor.equals(start)) {
            reversed.add(cursor);
            cursor = parents.get(cursor);
            if (cursor == null) {
                return null;
            }
        }
        Collections.reverse(reversed);
        return new NavigationState(targetX, targetZ, reversed);
    }

    private static int pathHeuristic(PathNode node, double targetX, double targetZ) {
        double dx = node.x + 0.5 - targetX;
        double dz = node.z + 0.5 - targetZ;
        return (int) Math.floor(Math.sqrt(dx * dx + dz * dz));
    }

    private WorldEntity nearestVisiblePlayer(WorldEntity mob, EntityRegistry entities, World world) {
        for (WorldEntity candidate : entities.within(mob.x(), mob.y(), mob.z(), detectionRange)) {
            if (candidate.type() == WorldEntity.Type.PLAYER && hasLineOfSight(mob, candidate, world)) {
                return candidate;
            }
        }
        return null;
    }

    private WorldEntity provokedPlayer(WorldEntity mob, EntityRegistry entities) {
        UUID targetId = provokedTargets.get(mob.id());
        if (targetId == null) {
            return null;
        }
        WorldEntity target = entities.find(targetId).orElse(null);
        if (target == null || target.type() != WorldEntity.Type.PLAYER) {
            return null;
        }
        double dx = target.x() - mob.x();
        double dy = target.y() - mob.y();
        double dz = target.z() - mob.z();
        return dx * dx + dy * dy + dz * dz <= detectionRange * detectionRange ? target : null;
    }

    private boolean hasLineOfSight(WorldEntity mob, WorldEntity target, World world) {
        double dx = target.x() - mob.x();
        double dy = target.y() + 1.0 - (mob.y() + 1.0);
        double dz = target.z() - mob.z();
        int samples = Math.max(1, (int) Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz) * 2));
        WorldHeight height = world.height();
        for (int i = 1; i < samples; i++) {
            double fraction = (double) i / samples;
            int x = floor(mob.x() + dx * fraction);
            int y = floor(mob.y() + 1.0 + dy * fraction);
            int z = floor(mob.z() + dz * fraction);
            if (!height.contains(y)) {
                return false;
            }
            ChunkPosition chunk = new ChunkPosition(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
            if (!world.isChunkLoaded(chunk)) {
                return false;
            }
            if (!world.blockAt(new BlockPosition(x, y, z)).isAir()) {
                return false;
            }
        }
        return true;
    }

    private boolean hasSafeFooting(World world, double x, double y, double z) {
        int blockX = floor(x);
        int feetY = floor(y);
        int blockZ = floor(z);
        WorldHeight height = world.height();
        if (!height.contains(feetY - 1) || !height.contains(feetY) || !height.contains(feetY + 1)) {
            return false;
        }
        if (!world.isChunkLoaded(new ChunkPosition(Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16)))) {
            return false;
        }
        return world.blockAt(new BlockPosition(blockX, feetY, blockZ)).isAir()
                && world.blockAt(new BlockPosition(blockX, feetY + 1, blockZ)).isAir()
                && !world.blockAt(new BlockPosition(blockX, feetY - 1, blockZ)).isAir();
    }

    private Decision remember(UUID mobId, Decision decision) {
        decisions.put(mobId, decision);
        return decision;
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private record TargetMemory(WorldEntity lastSeenTarget, long lastSeenTick) {
    }

    private record PathNode(int x, int z) {
    }

    private record SearchNode(PathNode position, int cost, int heuristic) {
        private int score() {
            return cost + heuristic;
        }
    }

    private static final class NavigationState {
        private final int targetX;
        private final int targetZ;
        private final java.util.ArrayDeque<PathNode> path;

        private NavigationState(int targetX, int targetZ, List<PathNode> path) {
            this.targetX = targetX;
            this.targetZ = targetZ;
            this.path = new java.util.ArrayDeque<>(path);
        }
    }

    public enum Behavior {
        PASSIVE,
        NEUTRAL,
        HOSTILE
    }

    public enum State {
        IDLE,
        CHASE,
        ATTACK,
        SEARCH,
        STUCK
    }

    public record Decision(State state, UUID targetId, boolean moved, boolean attackRequested) {
        public Decision {
            Objects.requireNonNull(state, "state");
            if (attackRequested && (state != State.ATTACK || targetId == null)) {
                throw new IllegalArgumentException("Attack intent requires an attack state and target");
            }
        }
    }
}
