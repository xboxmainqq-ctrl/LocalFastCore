package ru.nexora.runtime;

import java.util.HashMap;
import java.util.Map;
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

    private final Map<UUID, Behavior> behaviors = new HashMap<>();
    private final Map<UUID, UUID> provokedTargets = new HashMap<>();
    private final Map<UUID, Decision> decisions = new HashMap<>();
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
    }

    public synchronized boolean hasBehavior(UUID mobId) {
        return behaviors.containsKey(Objects.requireNonNull(mobId, "mobId"));
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

        WorldEntity target = behavior == Behavior.HOSTILE
                ? nearestVisiblePlayer(mob, entities, world)
                : provokedPlayer(mob, entities);
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
            return remember(mob.id(), new Decision(State.ATTACK, target.id(), false, true));
        }

        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDistance < 0.000001) {
            return remember(mob.id(), new Decision(State.CHASE, target.id(), false, false));
        }

        double stepX = dx / horizontalDistance * Math.min(moveSpeed, horizontalDistance);
        double stepZ = dz / horizontalDistance * Math.min(moveSpeed, horizontalDistance);
        double nextX = mob.x() + stepX;
        double nextZ = mob.z() + stepZ;
        if (!hasSafeFooting(world, nextX, mob.y(), nextZ)) {
            return remember(mob.id(), new Decision(State.STUCK, target.id(), false, false));
        }

        double yaw = Math.toDegrees(Math.atan2(-stepX, stepZ));
        boolean moved = entities.updatePosition(mob.id(), nextX, mob.y(), nextZ, yaw);
        return remember(mob.id(), new Decision(State.CHASE, target.id(), moved, false));
    }

    /** Drops all controller state for entities that no longer exist. */
    public synchronized void retainEntities(EntityRegistry entities) {
        Objects.requireNonNull(entities, "entities");
        behaviors.keySet().removeIf(id -> entities.find(id).isEmpty());
        provokedTargets.keySet().removeIf(id -> entities.find(id).isEmpty());
        decisions.keySet().removeIf(id -> entities.find(id).isEmpty());
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

    public enum Behavior {
        PASSIVE,
        NEUTRAL,
        HOSTILE
    }

    public enum State {
        IDLE,
        CHASE,
        ATTACK,
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
