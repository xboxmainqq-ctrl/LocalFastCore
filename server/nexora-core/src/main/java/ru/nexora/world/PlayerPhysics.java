package ru.nexora.world;

/**
 * Deterministic server-side player movement simulation.
 *
 * Coordinates use the Bedrock convention: X/Z are horizontal and Y is the
 * player's feet. The simulation is deliberately independent from networking
 * so packet handling can reconcile client prediction against a tested model.
 */
public final class PlayerPhysics {
    public static final double WALK_SPEED = 4.3;
    public static final double SPRINT_SPEED = 5.6;
    public static final double GRAVITY = 32.0;
    public static final double JUMP_VELOCITY = 8.4;
    public static final double MAX_TICK_SECONDS = 0.1;
    private static final double AIR_CONTROL = 0.18;
    private static final double GROUND_FRICTION = 12.0;
    private static final double AIR_FRICTION = 1.5;
    private static final double COLLISION_EPSILON = 1.0e-5;

    private PlayerPhysics() {
    }

    public static State spawn(World world, double x, double y, double z) {
        if (world == null || !finite(x, y, z) || !WorldCollision.canOccupy(world, x, y, z)) {
            throw new IllegalArgumentException("Invalid or obstructed player spawn position");
        }
        return new State(x, y, z, 0, 0, 0, WorldCollision.isOnGround(world, x, y, z));
    }

    /**
     * Advances one bounded simulation step. Input axes are in [-1, 1], with
     * forward positive; yaw is in degrees. Invalid input is rejected.
     */
    public static State tick(World world, State current, Input input, double deltaSeconds) {
        if (world == null || current == null || input == null
                || !Double.isFinite(deltaSeconds) || deltaSeconds <= 0
                || deltaSeconds > MAX_TICK_SECONDS
                || !finite(input.strafe(), input.forward(), input.yaw())
                || Math.abs(input.strafe()) > 1 || Math.abs(input.forward()) > 1) {
            throw new IllegalArgumentException("Invalid player physics tick");
        }

        boolean grounded = WorldCollision.isOnGround(world, current.x(), current.y(), current.z());
        double vx = current.velocityX();
        double vy = current.velocityY();
        double vz = current.velocityZ();

        double strafe = input.strafe();
        double forward = input.forward();
        double inputLength = Math.hypot(strafe, forward);
        if (inputLength > 1) {
            strafe /= inputLength;
            forward /= inputLength;
        }
        double radians = Math.toRadians(input.yaw());
        double desiredX = (strafe * Math.cos(radians) - forward * Math.sin(radians))
                * (input.sprinting() ? SPRINT_SPEED : WALK_SPEED);
        double desiredZ = (strafe * Math.sin(radians) + forward * Math.cos(radians))
                * (input.sprinting() ? SPRINT_SPEED : WALK_SPEED);
        double control = grounded ? 1.0 : AIR_CONTROL;
        vx += (desiredX - vx) * Math.min(1.0, control * deltaSeconds * 20.0);
        vz += (desiredZ - vz) * Math.min(1.0, control * deltaSeconds * 20.0);

        double friction = grounded ? GROUND_FRICTION : AIR_FRICTION;
        if (inputLength == 0) {
            double factor = Math.max(0, 1.0 - friction * deltaSeconds);
            vx *= factor;
            vz *= factor;
        }

        if (grounded && input.jump()) {
            vy = JUMP_VELOCITY;
            grounded = false;
        } else {
            vy -= GRAVITY * deltaSeconds;
        }

        double nextX = current.x() + vx * deltaSeconds;
        double nextZ = current.z() + vz * deltaSeconds;
        double nextY = current.y() + vy * deltaSeconds;

        // Resolve horizontal axes separately so a player can slide along walls.
        if (WorldCollision.canMove(world, current.x(), current.y(), current.z(),
                nextX, current.y(), current.z())) {
            // X is clear.
        } else {
            nextX = current.x();
            vx = 0;
        }
        if (WorldCollision.canMove(world, nextX, current.y(), current.z(),
                nextX, current.y(), nextZ)) {
            // Z is clear.
        } else {
            nextZ = current.z();
            vz = 0;
        }

        if (!WorldCollision.canMove(world, nextX, current.y(), nextZ, nextX, nextY, nextZ)) {
            // Resolve the vertical contact to the block boundary instead of
            // stopping at the last coarse sweep sample (which causes hovering).
            double validY = current.y();
            double blockedY = nextY;
            for (int i = 0; i < 20; i++) {
                double middle = (validY + blockedY) * 0.5;
                if (WorldCollision.canOccupy(world, nextX, middle, nextZ)) {
                    validY = middle;
                } else {
                    blockedY = middle;
                }
            }
            nextY = validY;
            if (vy < 0) {
                grounded = true;
                double blockBoundary = Math.rint(nextY);
                if (Math.abs(nextY - blockBoundary) < 1.0e-3
                        && WorldCollision.canOccupy(world, nextX, blockBoundary, nextZ)) {
                    nextY = blockBoundary;
                }
            }
            vy = 0;
        } else {
            grounded = false;
        }

        if (!grounded && WorldCollision.isOnGround(world, nextX, nextY, nextZ) && vy <= 0) {
            grounded = true;
            vy = 0;
        }
        if (!WorldCollision.canOccupy(world, nextX, nextY, nextZ)) {
            // Never publish a state embedded in terrain, even after malformed geometry.
            return new State(current.x(), current.y(), current.z(), 0, 0, 0, grounded);
        }
        return new State(nextX, nextY, nextZ, vx, vy, vz, grounded);
    }

    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }

    public record State(
            double x, double y, double z,
            double velocityX, double velocityY, double velocityZ,
            boolean onGround
    ) {
        public State {
            if (!finite(x, y, z, velocityX, velocityY, velocityZ)
                    || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000
                    || y < WorldChunk.MIN_Y || y > WorldChunk.MAX_Y) {
                throw new IllegalArgumentException("Invalid player physics state");
            }
        }
    }

    public record Input(double strafe, double forward, double yaw, boolean sprinting, boolean jump) {
    }
}
