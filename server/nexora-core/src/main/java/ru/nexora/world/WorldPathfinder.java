package ru.nexora.world;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** Bounded four-way grid pathfinder for simple ground-based hostile mob navigation. */
public final class WorldPathfinder {
    private static final int MAX_VISITED = 512;
    private static final int MAX_DEPTH = 12;
    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private WorldPathfinder() {
    }

    public static Optional<Step> findNextStep(
            World world, double startX, double feetY, double startZ,
            double targetX, double targetZ
    ) {
        return findNextStep(world, startX, feetY, startZ, targetX, targetZ, "minecraft:zombie");
    }

    /** Species-aware bounded path search using the mob's real collision footprint. */
    public static Optional<Step> findNextStep(
            World world, double startX, double feetY, double startZ,
            double targetX, double targetZ, String identifier
    ) {
        if (world == null || identifier == null || identifier.isBlank()
                || !Double.isFinite(startX) || !Double.isFinite(feetY)
                || !Double.isFinite(startZ) || !Double.isFinite(targetX)
                || !Double.isFinite(targetZ) || feetY < WorldChunk.MIN_Y
                || feetY > WorldChunk.MAX_Y || Math.abs(startX) > 30_000_000
                || Math.abs(startZ) > 30_000_000 || Math.abs(targetX) > 30_000_000
                || Math.abs(targetZ) > 30_000_000) return Optional.empty();

        int startCellX = (int) Math.floor(startX);
        int startCellZ = (int) Math.floor(startZ);
        int targetCellX = (int) Math.floor(targetX);
        int targetCellZ = (int) Math.floor(targetZ);
        if (startCellX == targetCellX && startCellZ == targetCellZ) return Optional.empty();
        if (!isMobFootprintLoaded(world, startX, startZ, identifier)) return Optional.empty();

        ArrayDeque<Node> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        queue.add(new Node(startCellX, startCellZ, startCellX, startCellZ, 0));
        visited.add(key(startCellX, startCellZ));

        int expanded = 0;
        while (!queue.isEmpty() && expanded < MAX_VISITED) {
            Node current = queue.removeFirst();
            expanded++;
            if (current.depth() >= MAX_DEPTH) continue;
            for (int[] direction : DIRECTIONS) {
                int nextX = current.x() + direction[0];
                int nextZ = current.z() + direction[1];
                if (Math.abs((long) nextX - startCellX) > MAX_DEPTH
                        || Math.abs((long) nextZ - startCellZ) > MAX_DEPTH) continue;
                if (!isMobFootprintLoaded(world, nextX + 0.5, nextZ + 0.5, identifier)) continue;
                long key = key(nextX, nextZ);
                if (!visited.add(key)) continue;

                double fromX = current.depth() == 0 ? startX : current.x() + 0.5;
                double fromZ = current.depth() == 0 ? startZ : current.z() + 0.5;
                double toX = nextX + 0.5;
                double toZ = nextZ + 0.5;
                if (!WorldCollision.canMoveMob(world, identifier, fromX, feetY, fromZ, toX, feetY, toZ)) continue;

                int firstX = current.depth() == 0 ? nextX : current.firstX();
                int firstZ = current.depth() == 0 ? nextZ : current.firstZ();
                if (nextX == targetCellX && nextZ == targetCellZ) {
                    return Optional.of(new Step(firstX + 0.5, firstZ + 0.5));
                }
                queue.addLast(new Node(nextX, nextZ, firstX, firstZ, current.depth() + 1));
            }
        }
        return Optional.empty();
    }

    /** Returns whether the bounded 25x25-block path-search area is already resident. */
    public static boolean isSearchAreaLoaded(World world, double x, double z) {
        if (world == null || !Double.isFinite(x) || !Double.isFinite(z)) return false;
        int minChunkX = Math.floorDiv((int) Math.floor(x - MAX_DEPTH), 16);
        int maxChunkX = Math.floorDiv((int) Math.floor(x + MAX_DEPTH), 16);
        int minChunkZ = Math.floorDiv((int) Math.floor(z - MAX_DEPTH), 16);
        int maxChunkZ = Math.floorDiv((int) Math.floor(z + MAX_DEPTH), 16);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!world.isChunkLoadedAt(chunkX * 16, chunkZ * 16)) return false;
            }
        }
        return true;
    }

    /** Checks a bounded movement segment without loading any chunk along the way. */
    public static boolean isMobMovementAreaLoaded(
            World world, double fromX, double fromZ, double toX, double toZ
    ) {
        if (world == null || !Double.isFinite(fromX) || !Double.isFinite(fromZ)
                || !Double.isFinite(toX) || !Double.isFinite(toZ)) return false;
        double distance = Math.hypot(toX - fromX, toZ - fromZ);
        if (!Double.isFinite(distance) || distance > 256.0) return false;
        int samples = Math.max(1, (int) Math.ceil(distance / 4.0));
        for (int i = 0; i <= samples; i++) {
            double fraction = (double) i / samples;
            if (!isMobFootprintLoaded(world,
                    fromX + (toX - fromX) * fraction,
                    fromZ + (toZ - fromZ) * fraction)) return false;
        }
        return true;
    }

    /** Checks all chunks touched by a mob's horizontal footprint without loading any. */
    public static boolean isMobFootprintLoaded(World world, double x, double z) {
        return isMobFootprintLoaded(world, x, z, "minecraft:zombie");
    }

    public static boolean isMobFootprintLoaded(World world, double x, double z, String identifier) {
        if (world == null || identifier == null || identifier.isBlank()
                || !Double.isFinite(x) || !Double.isFinite(z)) return false;
        double halfWidth = WorldCollision.dimensionsForMob(identifier).width() / 2.0;
        int minX = (int) Math.floor(x - halfWidth + 1.0e-5);
        int maxX = (int) Math.floor(x + halfWidth - 1.0e-5);
        int minZ = (int) Math.floor(z - halfWidth + 1.0e-5);
        int maxZ = (int) Math.floor(z + halfWidth - 1.0e-5);
        for (int blockX = minX; blockX <= maxX; blockX++) {
            for (int blockZ = minZ; blockZ <= maxZ; blockZ++) {
                if (!world.isChunkLoadedAt(blockX, blockZ)) return false;
            }
        }
        return true;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffff_ffffL);
    }

    private record Node(int x, int z, int firstX, int firstZ, int depth) {
    }

    public record Step(double x, double z) {
        public Step {
            if (!Double.isFinite(x) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("Path step must be finite");
            }
        }
    }
}
