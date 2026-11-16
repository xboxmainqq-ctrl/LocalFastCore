package ru.nexora.runtime;

import java.util.Objects;

/**
 * Validates and applies one server-authoritative block break request.
 * Drops and tool durability are intentionally handled by later inventory work.
 */
public final class BlockBreakService {
    public static final double MAX_REACH = 6.0d;
    private static final double EYE_HEIGHT = 1.62d;

    public enum Result {
        BROKEN, AIR, UNLOADED_CHUNK, OUT_OF_REACH, OUT_OF_WORLD, UNBREAKABLE, OCCLUDED
    }

    public record Outcome(Result result, BlockState previousState) {
        public Outcome {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(previousState, "previousState");
        }

        public boolean broken() {
            return result == Result.BROKEN;
        }
    }

    private BlockBreakService() {
    }

    public static Outcome breakBlock(
            World world, BlockPosition position,
            double playerX, double playerY, double playerZ
    ) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(position, "position");
        if (!Double.isFinite(playerX) || !Double.isFinite(playerY) || !Double.isFinite(playerZ)) {
            return new Outcome(Result.OUT_OF_REACH, BlockState.AIR);
        }
        if (position.y() < world.height().minY()
                || position.y() >= world.height().maxYExclusive()) {
            return new Outcome(Result.OUT_OF_WORLD, BlockState.AIR);
        }
        if (!world.isChunkLoaded(position.chunkPosition())) {
            return new Outcome(Result.UNLOADED_CHUNK, BlockState.AIR);
        }

        double dx = position.x() + 0.5d - playerX;
        double dy = position.y() + 0.5d - (playerY + EYE_HEIGHT);
        double dz = position.z() + 0.5d - playerZ;
        if (dx * dx + dy * dy + dz * dz > MAX_REACH * MAX_REACH) {
            return new Outcome(Result.OUT_OF_REACH, BlockState.AIR);
        }

        if (!hasLineOfSight(world, playerX, playerY + EYE_HEIGHT, playerZ, position)) {
            return new Outcome(Result.OCCLUDED, BlockState.AIR);
        }

        BlockState previous = world.blockAt(position);
        if (previous.isAir()) {
            return new Outcome(Result.AIR, previous);
        }
        if (previous.identifier().equals("minecraft:bedrock")) {
            return new Outcome(Result.UNBREAKABLE, previous);
        }

        world.setBlock(position, BlockState.AIR);
        return new Outcome(Result.BROKEN, previous);
    }

    private static boolean hasLineOfSight(
            World world, double startX, double startY, double startZ, BlockPosition target
    ) {
        double endX = target.x() + 0.5d;
        double endY = target.y() + 0.5d;
        double endZ = target.z() + 0.5d;
        double dx = endX - startX;
        double dy = endY - startY;
        double dz = endZ - startZ;
        int steps = Math.max(1, (int) Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz) * 12.0d));
        for (int step = 1; step < steps; step++) {
            double fraction = (double) step / steps;
            int x = (int) Math.floor(startX + dx * fraction);
            int y = (int) Math.floor(startY + dy * fraction);
            int z = (int) Math.floor(startZ + dz * fraction);
            if (x == target.x() && y == target.y() && z == target.z()) continue;
            if (y < world.height().minY() || y >= world.height().maxYExclusive()
                    || !world.isChunkLoaded(new BlockPosition(x, y, z).chunkPosition())
                    || !world.blockAt(new BlockPosition(x, y, z)).isAir()) {
                return false;
            }
        }
        return true;
    }
}
