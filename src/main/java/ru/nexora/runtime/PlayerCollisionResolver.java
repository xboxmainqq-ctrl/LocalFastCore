package ru.nexora.runtime;

/**
 * Small swept-AABB collision resolver for a standing player.
 *
 * <p>All non-air blocks are currently treated as full solid cubes. Fluids are
 * stored in a separate world layer and therefore remain traversable. Unloaded
 * chunks are treated as solid boundaries so a player cannot walk or fall into
 * terrain that has not been generated yet.</p>
 */
public final class PlayerCollisionResolver {
    private static final double HALF_WIDTH = 0.30d;
    private static final double HEIGHT = 1.80d;
    private static final double EPSILON = 1.0e-5d;
    private static final double MAX_SWEEP_STEP = 0.20d;

    private PlayerCollisionResolver() {
    }

    public static Resolution resolve(
            World world,
            double startX, double startY, double startZ,
            double targetX, double targetY, double targetZ
    ) {
        if (world == null) throw new NullPointerException("world");
        requireFinite(startX, "startX");
        requireFinite(startY, "startY");
        requireFinite(startZ, "startZ");
        requireFinite(targetX, "targetX");
        requireFinite(targetY, "targetY");
        requireFinite(targetZ, "targetZ");

        AxisResult x = sweep(world, startX, startY, startZ, targetX, Axis.X);
        AxisResult y = sweep(world, x.position, startY, startZ, targetY, Axis.Y);
        AxisResult z = sweep(world, x.position, y.position, startZ, targetZ, Axis.Z);

        boolean supported = y.blocked && targetY < startY
                || collides(world, x.position, y.position - 0.05d, z.position);
        return new Resolution(x.position, y.position, z.position,
                x.blocked, y.blocked, z.blocked, supported);
    }

    private static AxisResult sweep(
            World world, double x, double y, double z, double target, Axis axis) {
        double current = axis == Axis.X ? x : axis == Axis.Y ? y : z;
        double delta = target - current;
        if (Math.abs(delta) < EPSILON) return new AxisResult(current, false);

        int steps = Math.max(1, (int) Math.ceil(Math.abs(delta) / MAX_SWEEP_STEP));
        double previous = current;
        for (int step = 1; step <= steps; step++) {
            double candidate = current + delta * ((double) step / steps);
            double candidateX = axis == Axis.X ? candidate : x;
            double candidateY = axis == Axis.Y ? candidate : y;
            double candidateZ = axis == Axis.Z ? candidate : z;
            if (collides(world, candidateX, candidateY, candidateZ)) {
                return new AxisResult(previous, true);
            }
            previous = candidate;
        }
        return new AxisResult(target, false);
    }

    private static boolean collides(World world, double x, double feetY, double z) {
        double minX = x - HALF_WIDTH + EPSILON;
        double maxX = x + HALF_WIDTH - EPSILON;
        double minY = feetY + EPSILON;
        double maxY = feetY + HEIGHT - EPSILON;
        double minZ = z - HALF_WIDTH + EPSILON;
        double maxZ = z + HALF_WIDTH - EPSILON;

        if (minY < world.height().minY() || maxY >= world.height().maxYExclusive()) {
            return true;
        }

        int blockMinX = floor(minX);
        int blockMaxX = floor(maxX);
        int blockMinY = floor(minY);
        int blockMaxY = floor(maxY);
        int blockMinZ = floor(minZ);
        int blockMaxZ = floor(maxZ);

        for (int blockX = blockMinX; blockX <= blockMaxX; blockX++) {
            for (int blockZ = blockMinZ; blockZ <= blockMaxZ; blockZ++) {
                if (!world.isChunkLoaded(new ChunkPosition(
                        Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16)))) {
                    return true;
                }
                for (int blockY = blockMinY; blockY <= blockMaxY; blockY++) {
                    if (!world.blockAt(new BlockPosition(blockX, blockY, blockZ)).isAir()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value) || Math.abs(value) > 30_000_000d) {
            throw new IllegalArgumentException(name + " must be finite and within world bounds");
        }
    }

    private enum Axis { X, Y, Z }

    private record AxisResult(double position, boolean blocked) {
    }

    public record Resolution(
            double x, double y, double z,
            boolean collidedX, boolean collidedY, boolean collidedZ,
            boolean onGround
    ) {
        public boolean collided() {
            return collidedX || collidedY || collidedZ;
        }
    }
}
