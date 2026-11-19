package ru.nexora.world;

import java.util.Optional;

/** Bounded height-aware grid pathfinder for simple ground-based hostile mob navigation. */
public final class WorldPathfinder {
    private static final int MAX_VISITED = 768;
    private static final int MAX_DEPTH = 16;
    private static final int VISITED_CAPACITY = 2048;
    // Eight-way movement avoids the stair-step zigzag of cardinal-only routing.
    // Every diagonal edge still passes the full swept-AABB collision check.
    private static final int[][] DIRECTIONS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };
    private static final ThreadLocal<PathScratch> SCRATCH =
            ThreadLocal.withInitial(PathScratch::new);

    private WorldPathfinder() {
    }

    public static Optional<Step> findNextStep(
            World world, double startX, double feetY, double startZ,
            double targetX, double targetZ
    ) {
        return findNextStep(world, startX, feetY, startZ, targetX, targetZ, "minecraft:zombie");
    }

    /** Species-aware bounded path search with one-block steps and drops. */
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
        int startY = (int) Math.floor(feetY + 1.0e-5);
        if (startCellX == targetCellX && startCellZ == targetCellZ) return Optional.empty();
        if (!isMobFootprintLoaded(world, startX, startZ, identifier)
                || !WorldCollision.canOccupyMob(world, identifier, startX, feetY, startZ)
                || !WorldCollision.isMobOnGround(world, identifier, startX, feetY, startZ, 0.12)) {
            return Optional.empty();
        }

        PathScratch scratch = SCRATCH.get();
        scratch.clearVisited();
        scratch.x[0] = startCellX;
        scratch.y[0] = startY;
        scratch.z[0] = startCellZ;
        scratch.firstX[0] = startCellX;
        scratch.firstY[0] = startY;
        scratch.firstZ[0] = startCellZ;
        scratch.depth[0] = 0;
        scratch.addVisited(key(startCellX, startY, startCellZ));

        // Node indices are also queue positions: breadth-first insertion order is FIFO.
        int tail = 1;
        for (int head = 0; head < tail && head < MAX_VISITED; head++) {
            int currentX = scratch.x[head];
            int currentY = scratch.y[head];
            int currentZ = scratch.z[head];
            int currentDepth = scratch.depth[head];
            if (currentDepth >= MAX_DEPTH) continue;

            orderDirections(targetCellX - currentX, targetCellZ - currentZ, scratch);
            for (int orderIndex = 0; orderIndex < DIRECTIONS.length; orderIndex++) {
                int[] direction = DIRECTIONS[scratch.directionOrder[orderIndex]];
                int nextX = currentX + direction[0];
                int nextZ = currentZ + direction[1];
                if (Math.abs((long) nextX - startCellX) > MAX_DEPTH
                        || Math.abs((long) nextZ - startCellZ) > MAX_DEPTH) continue;

                for (int dy = 1; dy >= -1; dy--) {
                    int nextY = currentY + dy;
                    if (nextY <= WorldChunk.MIN_Y || nextY > WorldChunk.MAX_Y) continue;
                    double fromX = currentDepth == 0 ? startX : currentX + 0.5;
                    double fromZ = currentDepth == 0 ? startZ : currentZ + 0.5;
                    double fromY = currentDepth == 0 ? feetY : currentY + (feetY - startY);
                    double toX = nextX + 0.5;
                    double toZ = nextZ + 0.5;
                    double toY = nextY + (feetY - startY);
                    if (!isMobFootprintLoaded(world, toX, toZ, identifier)) continue;

                    long visitedKey = key(nextX, nextY, nextZ);
                    int visitedSlot = scratch.findVisitedSlot(visitedKey);
                    if (visitedSlot < 0) continue;
                    if (!WorldCollision.canStepMob(world, identifier,
                            fromX, fromY, fromZ, toX, toY, toZ)) continue;
                    scratch.addVisited(visitedKey, visitedSlot);

                    int firstX = currentDepth == 0 ? nextX : scratch.firstX[head];
                    int firstY = currentDepth == 0 ? nextY : scratch.firstY[head];
                    int firstZ = currentDepth == 0 ? nextZ : scratch.firstZ[head];
                    if (nextX == targetCellX && nextZ == targetCellZ) {
                        return Optional.of(new Step(firstX + 0.5,
                                firstY + (feetY - startY), firstZ + 0.5));
                    }

                    // Nodes beyond this bound would never be expanded by the old
                    // MAX_VISITED guard, so do not allocate or enqueue them.
                    if (tail >= MAX_VISITED) continue;
                    scratch.x[tail] = nextX;
                    scratch.y[tail] = nextY;
                    scratch.z[tail] = nextZ;
                    scratch.firstX[tail] = firstX;
                    scratch.firstY[tail] = firstY;
                    scratch.firstZ[tail] = firstZ;
                    scratch.depth[tail] = currentDepth + 1;
                    tail++;
                }
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

    /** Orders equally short routes toward the destination without allocating per expanded node. */
    private static void orderDirections(int targetDx, int targetDz, PathScratch scratch) {
        for (int i = 0; i < DIRECTIONS.length; i++) {
            scratch.directionOrder[i] = i;
            scratch.directionScores[i] =
                    DIRECTIONS[i][0] * targetDx + DIRECTIONS[i][1] * targetDz;
        }
        for (int i = 1; i < DIRECTIONS.length; i++) {
            int selected = scratch.directionOrder[i];
            int j = i - 1;
            while (j >= 0
                    && scratch.directionScores[scratch.directionOrder[j]]
                    < scratch.directionScores[selected]) {
                scratch.directionOrder[j + 1] = scratch.directionOrder[j];
                j--;
            }
            scratch.directionOrder[j + 1] = selected;
        }
    }

    /** Packs supported signed X/Z coordinates and the bounded world Y range into one key. */
    private static long key(int x, int y, int z) {
        return ((long) (x & 0x03ff_ffff) << 38)
                | ((long) (z & 0x03ff_ffff) << 12)
                | ((y - WorldChunk.MIN_Y) & 0xfffL);
    }

    private static final class PathScratch {
        private final int[] x = new int[MAX_VISITED];
        private final int[] y = new int[MAX_VISITED];
        private final int[] z = new int[MAX_VISITED];
        private final int[] firstX = new int[MAX_VISITED];
        private final int[] firstY = new int[MAX_VISITED];
        private final int[] firstZ = new int[MAX_VISITED];
        private final int[] depth = new int[MAX_VISITED];
        private final int[] directionOrder = new int[DIRECTIONS.length];
        private final int[] directionScores = new int[DIRECTIONS.length];
        private final long[] visitedKeys = new long[VISITED_CAPACITY];
        private final boolean[] visitedOccupied = new boolean[VISITED_CAPACITY];
        private final int[] touchedSlots = new int[MAX_VISITED];
        private int touchedCount;

        private void clearVisited() {
            for (int i = 0; i < touchedCount; i++) {
                visitedOccupied[touchedSlots[i]] = false;
            }
            touchedCount = 0;
        }

        private int findVisitedSlot(long value) {
            int slot = hash(value) & (VISITED_CAPACITY - 1);
            while (visitedOccupied[slot]) {
                if (visitedKeys[slot] == value) return -1;
                slot = (slot + 1) & (VISITED_CAPACITY - 1);
            }
            return slot;
        }

        private void addVisited(long value) {
            int slot = findVisitedSlot(value);
            if (slot >= 0) addVisited(value, slot);
        }

        private void addVisited(long value, int slot) {
            visitedOccupied[slot] = true;
            visitedKeys[slot] = value;
            touchedSlots[touchedCount++] = slot;
        }

        private static int hash(long value) {
            value ^= value >>> 33;
            value *= 0xff51afd7ed558ccdL;
            value ^= value >>> 33;
            value *= 0xc4ceb9fe1a85ec53L;
            value ^= value >>> 33;
            return (int) value;
        }
    }

    public record Step(double x, double y, double z) {
        public Step {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("Path step must be finite");
            }
        }

        /** Compatibility constructor for callers that only use horizontal waypoints. */
        public Step(double x, double z) {
            this(x, 0, z);
        }
    }
}
