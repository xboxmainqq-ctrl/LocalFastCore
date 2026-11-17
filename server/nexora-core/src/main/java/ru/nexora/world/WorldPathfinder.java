package ru.nexora.world;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** Bounded four-way grid pathfinder for simple ground-based hostile mob navigation. */
public final class WorldPathfinder {
    private static final int MAX_VISITED = 256;
    private static final int MAX_DEPTH = 12;
    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private WorldPathfinder() {
    }

    public static Optional<Step> findNextStep(
            World world, double startX, double feetY, double startZ,
            double targetX, double targetZ
    ) {
        if (world == null || !Double.isFinite(startX) || !Double.isFinite(feetY)
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
        if (!world.isChunkLoadedAt(startCellX, startCellZ)) return Optional.empty();

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
                if (!world.isChunkLoadedAt(nextX, nextZ)) continue;
                long key = key(nextX, nextZ);
                if (!visited.add(key)) continue;

                double fromX = current.depth() == 0 ? startX : current.x() + 0.5;
                double fromZ = current.depth() == 0 ? startZ : current.z() + 0.5;
                double toX = nextX + 0.5;
                double toZ = nextZ + 0.5;
                if (!WorldCollision.canMove(world, fromX, feetY, fromZ, toX, feetY, toZ)) continue;

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
