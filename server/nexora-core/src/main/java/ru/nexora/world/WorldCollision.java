package ru.nexora.world;

/**
 * Conservative swept-AABB collision checks for a standard Bedrock player.
 * Player positions are interpreted as the centre of the feet, matching the
 * position supplied by PlayerAuthInput and used by StartGame.
 */
public final class WorldCollision {
    private static final double PLAYER_HALF_WIDTH = 0.3;
    private static final double PLAYER_HEIGHT = 1.8;
    private static final double SAMPLE_SPACING = 0.2;
    private static final int MAX_SWEEP_SAMPLES = 512;
    private static final double EDGE_EPSILON = 1.0e-5;

    private WorldCollision() {
    }

    /**
     * Returns false if any point along the movement segment intersects a solid
     * block. Sampling is bounded by movement distance, preventing a fast packet
     * from tunnelling through a one-block wall.
     */
    public static boolean canMove(
            World world,
            double fromX, double fromY, double fromZ,
            double toX, double toY, double toZ
    ) {
        if (world == null || !finite(fromX, fromY, fromZ, toX, toY, toZ)) return false;
        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!Double.isFinite(distance)) return false;
        double requiredSamples = Math.ceil(distance / SAMPLE_SPACING);
        if (requiredSamples > MAX_SWEEP_SAMPLES) return false;
        int samples = Math.max(1, (int) requiredSamples);
        for (int step = 1; step <= samples; step++) {
            double fraction = (double) step / samples;
            if (!canOccupy(world,
                    fromX + dx * fraction,
                    fromY + dy * fraction,
                    fromZ + dz * fraction)) return false;
        }
        return true;
    }

    public static boolean canOccupy(World world, double x, double feetY, double z) {
        if (world == null || !finite(x, feetY, z)) return false;
        double minX = x - PLAYER_HALF_WIDTH + EDGE_EPSILON;
        double maxX = x + PLAYER_HALF_WIDTH - EDGE_EPSILON;
        double minZ = z - PLAYER_HALF_WIDTH + EDGE_EPSILON;
        double maxZ = z + PLAYER_HALF_WIDTH - EDGE_EPSILON;
        double headY = feetY + PLAYER_HEIGHT - EDGE_EPSILON;
        if (minX < -30_000_000 || maxX > 30_000_000
                || minZ < -30_000_000 || maxZ > 30_000_000
                || feetY < WorldChunk.MIN_Y || headY > WorldChunk.MAX_Y) return false;

        int firstX = (int) Math.floor(minX);
        int lastX = (int) Math.floor(maxX);
        int firstY = (int) Math.floor(feetY + EDGE_EPSILON);
        int lastY = (int) Math.floor(headY);
        int firstZ = (int) Math.floor(minZ);
        int lastZ = (int) Math.floor(maxZ);
        for (int blockX = firstX; blockX <= lastX; blockX++) {
            for (int blockY = firstY; blockY <= lastY; blockY++) {
                for (int blockZ = firstZ; blockZ <= lastZ; blockZ++) {
                    if (world.getBlock(blockX, blockY, blockZ) != BlockType.AIR) return false;
                }
            }
        }
        return true;
    }

    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }
}
