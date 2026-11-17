package ru.nexora.combat;

import ru.nexora.world.World;
import ru.nexora.world.WorldCollision;
import ru.nexora.world.WorldLivingEntity;
import ru.nexora.world.WorldPathfinder;

/**
 * Small bounded per-tick actor physics for gravity and knockback.
 * Flying species do not receive ground gravity; all movement remains server-authoritative.
 */
public final class MobPhysicsService {
    public static final double GRAVITY_PER_TICK = 0.08;
    public static final double TERMINAL_FALL_SPEED = -3.92;
    public static final double GROUND_DRAG = 0.60;
    public static final double AIR_DRAG = 0.91;
    public static final double VERTICAL_DRAG = 0.98;
    private static final double STOP_EPSILON = 0.003;

    public Result tick(World world, WorldLivingEntity entity) {
        if (world == null || entity == null) throw new NullPointerException();
        if (entity.health() <= 0) return new Result(entity, false, false);

        boolean flying = LivingEntityAiService.isFlyingMob(entity.identifier());
        boolean groundedBeforeMove = !flying && WorldCollision.isOnGround(
                world, entity.x(), entity.y(), entity.z());

        double vx = entity.velocityX();
        double vy = entity.velocityY();
        double vz = entity.velocityZ();
        if (!flying) {
            if (groundedBeforeMove && vy < 0) {
                vy = isBouncyMob(entity.identifier())
                        ? Math.min(1.2, Math.max(0.2, -vy * 0.8)) : 0;
            } else if (!groundedBeforeMove) {
                vy = Math.max(TERMINAL_FALL_SPEED, vy - GRAVITY_PER_TICK);
            }
        }

        double x = entity.x();
        double y = entity.y();
        double z = entity.z();
        if (Math.abs(vx) > STOP_EPSILON) {
            if (WorldPathfinder.isMobMovementAreaLoaded(world, x, z, x + vx, z)
                    && WorldCollision.canMove(world, x, y, z, x + vx, y, z)) x += vx;
            else vx = 0;
        } else vx = 0;

        if (Math.abs(vz) > STOP_EPSILON) {
            if (WorldPathfinder.isMobMovementAreaLoaded(world, x, z, x, z + vz)
                    && WorldCollision.canMove(world, x, y, z, x, y, z + vz)) z += vz;
            else vz = 0;
        } else vz = 0;

        if (Math.abs(vy) > STOP_EPSILON) {
            if (WorldPathfinder.isMobFootprintLoaded(world, x, z)
                    && WorldCollision.canMove(world, x, y, z, x, y + vy, z)) {
                y += vy;
            } else if (vy < 0 && isBouncyMob(entity.identifier())) {
                vy = Math.min(1.2, Math.max(0.2, -vy * 0.8));
            } else {
                vy = 0;
            }
        } else vy = 0;

        double drag = groundedBeforeMove ? GROUND_DRAG : AIR_DRAG;
        vx = damp(vx * drag);
        vz = damp(vz * drag);
        vy = flying ? damp(vy * AIR_DRAG) : damp(vy * VERTICAL_DRAG);

        boolean moved = x != entity.x() || y != entity.y() || z != entity.z();
        boolean velocityChanged = vx != entity.velocityX()
                || vy != entity.velocityY() || vz != entity.velocityZ();
        WorldLivingEntity updated = entity;
        if (velocityChanged) {
            updated = world.livingEntities().setVelocity(entity.runtimeId(), vx, vy, vz)
                    .orElse(updated);
        }
        if (moved) {
            updated = world.livingEntities().move(entity.runtimeId(), x, y, z).orElse(updated);
        }
        boolean groundedAfterMove = !flying && WorldCollision.isOnGround(world, x, y, z);
        return new Result(updated, moved, groundedAfterMove);
    }

    private static boolean isBouncyMob(String identifier) {
        return "minecraft:slime".equals(identifier) || "minecraft:magma_cube".equals(identifier);
    }

    private static double damp(double value) {
        return Math.abs(value) < STOP_EPSILON ? 0 : value;
    }

    public record Result(WorldLivingEntity entity, boolean moved, boolean grounded) { }
}
