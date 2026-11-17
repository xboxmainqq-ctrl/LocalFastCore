package ru.nexora.combat;

import ru.nexora.world.BlockType;
import ru.nexora.world.World;
import ru.nexora.world.WorldCollision;
import ru.nexora.world.WorldLivingEntity;
import ru.nexora.world.WorldPathfinder;

/** Simple wall-climbing impulse for spiders when their direct ground path is blocked. */
public final class MobClimbController {
    public static final double CLIMB_VELOCITY = 0.25;

    public boolean tryClimb(World world, WorldLivingEntity mob, double nextX, double nextZ) {
        if (world == null || mob == null || !Double.isFinite(nextX) || !Double.isFinite(nextZ)) {
            throw new IllegalArgumentException("Invalid mob climbing observation");
        }
        if (!isClimber(mob.identifier())
                || !WorldPathfinder.isMobFootprintLoaded(world, mob.x(), mob.z())
                || !WorldPathfinder.isMobMovementAreaLoaded(world, mob.x(), mob.z(), nextX, nextZ)) {
            return false;
        }
        int wallX = (int) Math.floor(nextX);
        int wallY = (int) Math.floor(mob.y());
        int wallZ = (int) Math.floor(nextZ);
        if (world.getBlock(wallX, wallY, wallZ) == BlockType.AIR) return false;
        double raisedY = mob.y() + CLIMB_VELOCITY;
        if (!WorldCollision.canMove(world, mob.x(), mob.y(), mob.z(), mob.x(), raisedY, mob.z())) {
            return false;
        }
        return world.livingEntities().setVelocity(mob.runtimeId(),
                mob.velocityX(), CLIMB_VELOCITY, mob.velocityZ()).isPresent();
    }

    public static boolean isClimber(String identifier) {
        return "minecraft:spider".equals(identifier) || "minecraft:cave_spider".equals(identifier);
    }
}
