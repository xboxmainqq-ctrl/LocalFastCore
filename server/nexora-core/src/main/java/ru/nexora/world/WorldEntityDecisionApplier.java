package ru.nexora.world;

import ru.nexora.combat.LivingEntityAiService;

import java.util.Objects;

/**
 * Applies server-authoritative AI decisions to the living-entity registry.
 *
 * <p>The AI service never mutates world state. This class is the explicit mutation
 * boundary: movement is validated by WorldLivingEntityManager and attacks are routed
 * through the same damage API used by other server systems.</p>
 */
public final class WorldEntityDecisionApplier {
    private final WorldLivingEntityManager entities;
    private final World world;
    private final MobPathCache pathCache;

    public WorldEntityDecisionApplier(WorldLivingEntityManager entities) {
        this(null, entities, new MobPathCache());
    }

    /** World-aware applier enables collision-safe mob movement and obstacle detours. */
    public WorldEntityDecisionApplier(World world, WorldLivingEntityManager entities) {
        this(Objects.requireNonNull(world, "world"), entities, new MobPathCache());
    }

    public WorldEntityDecisionApplier(World world, WorldLivingEntityManager entities,
                                      MobPathCache pathCache) {
        this.world = world;
        this.entities = Objects.requireNonNull(entities, "entities");
        this.pathCache = Objects.requireNonNull(pathCache, "pathCache");
    }

    public ApplyResult apply(LivingEntityAiService.Decision decision) {
        return apply(decision, System.currentTimeMillis());
    }

    /** Deterministic-time entry point for world ticks and tests. */
    public ApplyResult apply(LivingEntityAiService.Decision decision, long nowMillis) {
        Objects.requireNonNull(decision, "decision");
        if (nowMillis < 0) throw new IllegalArgumentException("nowMillis must be non-negative");
        var entity = entities.find(decision.mobRuntimeId());
        if (entity.isEmpty() || entity.get().health() <= 0) {
            return new ApplyResult(decision.mobRuntimeId(), false, false, false, 0);
        }

        boolean moved = false;
        if (Math.abs(decision.moveX()) > 0.00001
                || Math.abs(decision.moveY()) > 0.00001
                || Math.abs(decision.moveZ()) > 0.00001) {
            var current = entity.get();
            double moveX = decision.moveX();
            double moveY = decision.moveY();
            double moveZ = decision.moveZ();
            if (world != null && !LivingEntityAiService.isFlyingMob(current.identifier())) {
                double nextX = current.x() + moveX;
                double nextZ = current.z() + moveZ;
                if (!WorldCollision.canMoveMob(world, current.identifier(),
                        current.x(), current.y(), current.z(), nextX, current.y(), nextZ)) {
                    double horizontalStep = Math.hypot(moveX, moveZ);
                    double directionLength = Math.hypot(moveX, moveZ);
                    if (horizontalStep <= 1.0e-6 || directionLength <= 1.0e-6) {
                        moveX = 0;
                        moveZ = 0;
                    } else {
                        // Search a short distance in the intended direction; the actual
                        // movement remains capped to this tick's original AI speed.
                        double goalX = current.x() + moveX / directionLength * 8.0;
                        double goalZ = current.z() + moveZ / directionLength * 8.0;
                        var waypoint = pathCache.findNextStep(current.runtimeId(), world,
                                current.x(), current.y(), current.z(), goalX, goalZ,
                                nowMillis, current.identifier());
                        if (waypoint.isPresent()) {
                            double dx = waypoint.orElseThrow().x() - current.x();
                            double dz = waypoint.orElseThrow().z() - current.z();
                            double distance = Math.hypot(dx, dz);
                            double step = Math.min(horizontalStep, distance);
                            moveX = distance <= 1.0e-6 ? 0 : dx / distance * step;
                            moveZ = distance <= 1.0e-6 ? 0 : dz / distance * step;
                        } else {
                            moveX = 0;
                            moveZ = 0;
                        }
                    }
                }
                if (!WorldCollision.canMoveMob(world, current.identifier(),
                        current.x(), current.y(), current.z(),
                        current.x() + moveX, current.y() + moveY, current.z() + moveZ)) {
                    moveX = 0;
                    moveY = 0;
                    moveZ = 0;
                }
            }
            if (Math.abs(moveX) > 1.0e-6 || Math.abs(moveY) > 1.0e-6
                    || Math.abs(moveZ) > 1.0e-6) {
                entities.move(current.runtimeId(), current.x() + moveX,
                        current.y() + moveY, current.z() + moveZ);
                moved = true;
            }
        }

        boolean attacked = false;
        float damage = 0;
        if (decision.attack() && decision.attackDamage() > 0 && decision.targetNetworkId() != null) {
            // Player damage is intentionally represented as an output event here.
            // The player subsystem owns actual player health/inventory mutation.
            attacked = true;
            damage = decision.attackDamage();
        }

        return new ApplyResult(decision.mobRuntimeId(), moved, attacked, false, damage);
    }

    public record ApplyResult(long mobRuntimeId, boolean moved, boolean attacked,
                              boolean targetKilled, float attackDamage) {
        public ApplyResult {
            if (mobRuntimeId <= 0 || attackDamage < 0) {
                throw new IllegalArgumentException("Invalid decision application result");
            }
        }
    }
}
