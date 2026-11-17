package ru.nexora.world;

/**
 * Voxel line-of-sight helper for server-authoritative block interactions.
 * The target voxel itself is excluded from obstruction checks.
 */
public final class WorldRaycast {
    private static final int MAX_STEPS = 256;

    private WorldRaycast() {
    }

    public static boolean hasLineOfSight(
            World world, double startX, double startY, double startZ,
            int targetX, int targetY, int targetZ
    ) {
        return hasLineOfSight(world, startX, startY, startZ, targetX, targetY, targetZ, false);
    }

    /** Mob-only variant that never causes an unloaded chunk to be generated. */
    public static boolean hasLineOfSightInLoadedChunks(
            World world, double startX, double startY, double startZ,
            int targetX, int targetY, int targetZ
    ) {
        return hasLineOfSight(world, startX, startY, startZ, targetX, targetY, targetZ, true);
    }

    private static boolean hasLineOfSight(
            World world, double startX, double startY, double startZ,
            int targetX, int targetY, int targetZ, boolean requireLoadedChunks
    ) {
        if (world == null || !Double.isFinite(startX) || !Double.isFinite(startY)
                || !Double.isFinite(startZ)
                || targetY < WorldChunk.MIN_Y || targetY > WorldChunk.MAX_Y) {
            return false;
        }

        double endX = targetX + 0.5;
        double endY = targetY + 0.5;
        double endZ = targetZ + 0.5;
        double dx = endX - startX;
        double dy = endY - startY;
        double dz = endZ - startZ;

        int x = floorToInt(startX);
        int y = floorToInt(startY);
        int z = floorToInt(startZ);
        if (x == targetX && y == targetY && z == targetZ) return true;

        int stepX = Double.compare(dx, 0.0);
        int stepY = Double.compare(dy, 0.0);
        int stepZ = Double.compare(dz, 0.0);
        double deltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dx);
        double deltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dy);
        double deltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dz);
        double maxX = initialBoundary(startX, dx, x, stepX);
        double maxY = initialBoundary(startY, dy, y, stepY);
        double maxZ = initialBoundary(startZ, dz, z, stepZ);

        for (int count = 0; count < MAX_STEPS; count++) {
            double next = Math.min(maxX, Math.min(maxY, maxZ));
            if (!Double.isFinite(next) || next > 1.0) return false;
            double epsilon = 1.0e-12;
            if (maxX <= next + epsilon) {
                x += stepX;
                maxX += deltaX;
            }
            if (maxY <= next + epsilon) {
                y += stepY;
                maxY += deltaY;
            }
            if (maxZ <= next + epsilon) {
                z += stepZ;
                maxZ += deltaZ;
            }

            if (x == targetX && y == targetY && z == targetZ) return true;
            if (y < WorldChunk.MIN_Y || y > WorldChunk.MAX_Y) return false;
            if (requireLoadedChunks && !world.isChunkLoadedAt(x, z)) return false;
            if (world.getBlock(x, y, z) != BlockType.AIR) return false;
        }
        return false;
    }

    private static double initialBoundary(double start, double delta, int cell, int step) {
        if (step > 0) return (cell + 1.0 - start) / delta;
        if (step < 0) return (start - cell) / -delta;
        return Double.POSITIVE_INFINITY;
    }

    private static int floorToInt(double value) {
        return (int) Math.floor(value);
    }
}
