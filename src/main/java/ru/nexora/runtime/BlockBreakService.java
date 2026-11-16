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
        BROKEN, AIR, UNLOADED_CHUNK, OUT_OF_REACH, OUT_OF_WORLD, UNBREAKABLE
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
}
