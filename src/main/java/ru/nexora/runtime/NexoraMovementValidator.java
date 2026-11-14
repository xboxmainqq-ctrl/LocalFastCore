package ru.nexora.runtime;

import java.util.Objects;

/**
 * Server-side guard for client-reported player positions.
 *
 * <p>This validator is deliberately independent of transport/session state.
 * A protocol session must call it on the game thread and only commit an
 * accepted position. Client coordinates are never trusted as authoritative.</p>
 */
public final class NexoraMovementValidator {
    private static final double PLAYER_HALF_WIDTH = 0.30;
    private static final double PLAYER_HEIGHT = 1.80;
    private static final double MAX_HORIZONTAL_PER_TICK = 0.80;
    private static final double MAX_VERTICAL_PER_TICK = 0.80;

    public Result validate(World world, Position current, Position requested) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(requested, "requested");

        if (!finite(requested.x()) || !finite(requested.y()) || !finite(requested.z())) {
            return Result.rejected(Reason.NON_FINITE);
        }
        WorldHeight height = world.height();
        if (requested.y() < height.minY() || requested.y() + PLAYER_HEIGHT >= height.maxYExclusive()) {
            return Result.rejected(Reason.OUT_OF_WORLD);
        }

        double dx = requested.x() - current.x();
        double dy = requested.y() - current.y();
        double dz = requested.z() - current.z();
        if (Math.hypot(dx, dz) > MAX_HORIZONTAL_PER_TICK || Math.abs(dy) > MAX_VERTICAL_PER_TICK) {
            return Result.rejected(Reason.MOVEMENT_TOO_FAST);
        }
        if (!canOccupy(world, requested)) {
            return Result.rejected(Reason.BLOCKED_OR_UNLOADED);
        }
        return Result.accepted(requested);
    }

    private static boolean canOccupy(World world, Position position) {
        int minX = floor(position.x() - PLAYER_HALF_WIDTH);
        int maxX = floor(position.x() + PLAYER_HALF_WIDTH);
        int minY = floor(position.y() + 0.001);
        int maxY = floor(position.y() + PLAYER_HEIGHT - 0.001);
        int minZ = floor(position.z() - PLAYER_HALF_WIDTH);
        int maxZ = floor(position.z() + PLAYER_HALF_WIDTH);

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!world.isChunkLoaded(new BlockPosition(x, minY, z).chunkPosition())) {
                    return false;
                }
                for (int y = minY; y <= maxY; y++) {
                    BlockState block = world.blockAt(new BlockPosition(x, y, z));
                    if (!block.isAir()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private static boolean finite(double value) {
        return Double.isFinite(value) && Math.abs(value) <= 30_000_000d;
    }

    public record Position(double x, double y, double z) {
    }

    public enum Reason {
        NON_FINITE,
        OUT_OF_WORLD,
        MOVEMENT_TOO_FAST,
        BLOCKED_OR_UNLOADED
    }

    public record Result(boolean accepted, Position position, Reason reason) {
        private static Result accepted(Position position) {
            return new Result(true, position, null);
        }

        private static Result rejected(Reason reason) {
            return new Result(false, null, reason);
        }
    }
}
