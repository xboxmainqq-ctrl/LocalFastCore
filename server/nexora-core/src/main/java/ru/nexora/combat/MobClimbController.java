package ru.nexora.combat;

import ru.nexora.world.BlockType;
import ru.nexora.world.World;
import ru.nexora.world.WorldCollision;
import ru.nexora.world.WorldLivingEntity;
import ru.nexora.world.WorldPathfinder;

/** Simple wall-climbing impulse for spiders when their direct ground path is blocked. */
public final class MobClimbController {
    public static final double CLIMB_VELOCITY = 0.25;

    private long climbs;
    private long rejected;

    public synchronized boolean tryClimb(World world, WorldLivingEntity mob, double nextX, double nextZ) {
        if (world == null || mob == null || !Double.isFinite(nextX) || !Double.isFinite(nextZ)) {
            throw new IllegalArgumentException("Invalid mob climbing observation");
        }
        if (!isClimber(mob.identifier())
                || !WorldPathfinder.isMobFootprintLoaded(world, mob.x(), mob.z())
                || !WorldPathfinder.isMobMovementAreaLoaded(world, mob.x(), mob.z(), nextX, nextZ)) {
            rejected++;
            return false;
        }
        int wallY = (int) Math.floor(mob.y());
        boolean wallAhead = false;
        double dx = nextX - mob.x();
        double dz = nextZ - mob.z();
        if (dx > 0) wallAhead |= isWall(world, (int) Math.floor(nextX + 0.3), wallY,
                (int) Math.floor(nextZ));
        else if (dx < 0) wallAhead |= isWall(world, (int) Math.floor(nextX - 0.3), wallY,
                (int) Math.floor(nextZ));
        if (dz > 0) wallAhead |= isWall(world, (int) Math.floor(nextX), wallY,
                (int) Math.floor(nextZ + 0.3));
        else if (dz < 0) wallAhead |= isWall(world, (int) Math.floor(nextX), wallY,
                (int) Math.floor(nextZ - 0.3));
        if (!wallAhead) {
            rejected++;
            return false;
        }
        double raisedY = mob.y() + CLIMB_VELOCITY;
        if (!WorldCollision.canMove(world, mob.x(), mob.y(), mob.z(), mob.x(), raisedY, mob.z())) {
            rejected++;
            return false;
        }
        boolean climbed = world.livingEntities().setVelocity(mob.runtimeId(),
                mob.velocityX(), CLIMB_VELOCITY, mob.velocityZ()).isPresent();
        if (climbed) climbs++;
        else rejected++;
        return climbed;
    }

    private static boolean isWall(World world, int x, int y, int z) {
        return world.getBlock(x, y, z) != BlockType.AIR
                || (y + 1 < ru.nexora.world.WorldChunk.MAX_Y
                && world.getBlock(x, y + 1, z) != BlockType.AIR);
    }

    public synchronized Stats stats() {
        return new Stats(climbs, rejected);
    }

    public record Stats(long climbs, long rejected) {
        public String summary() {
            return "climbs=" + climbs + ", rejected=" + rejected;
        }
    }

    public static boolean isClimber(String identifier) {
        return "minecraft:spider".equals(identifier) || "minecraft:cave_spider".equals(identifier);
    }
}
