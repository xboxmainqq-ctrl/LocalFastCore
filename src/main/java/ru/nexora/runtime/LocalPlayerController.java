package ru.nexora.runtime;

import java.util.Objects;

/**
 * Deterministic first-person player physics for a local world.
 *
 * <p>Coordinates describe the player's feet. Call {@link #tick(Input)} once
 * per game tick (normally 20 times per second). Collision checks treat
 * unloaded chunks as solid, preventing movement into terrain that has not
 * arrived from the asynchronous chunk pipeline.</p>
 */
public final class LocalPlayerController {
    public static final double WIDTH = 0.6;
    public static final double HEIGHT = 1.8;
    private static final double WALK_SPEED = 4.3 / 20.0;
    private static final double SPRINT_SPEED = 5.6 / 20.0;
    private static final double GRAVITY = 0.08;
    private static final double MAX_FALL_SPEED = 0.78;
    private static final double JUMP_VELOCITY = 0.42;
    private static final double EPSILON = 1.0e-7;

    private final World world;
    private State state;

    public LocalPlayerController(World world, double x, double y, double z, float yaw) {
        this.world = Objects.requireNonNull(world, "world");
        validatePosition(x, y, z);
        validateYaw(yaw);
        this.state = new State(x, y, z, yaw, 0.0, false, 0L);
    }

    public synchronized State state() {
        return state;
    }

    /** Advances physics by one fixed game tick using the supplied input snapshot. */
    public synchronized State tick(Input input) {
        Objects.requireNonNull(input, "input");
        State current = state;
        float yaw = normalizeYaw(current.yaw() + input.lookYaw());
        double forward = clamp(input.forward(), -1.0, 1.0);
        double strafe = clamp(input.strafe(), -1.0, 1.0);
        double inputLength = Math.sqrt(forward * forward + strafe * strafe);
        if (inputLength > 1.0) {
            forward /= inputLength;
            strafe /= inputLength;
        }

        double speed = input.sprint() ? SPRINT_SPEED : WALK_SPEED;
        double radians = Math.toRadians(yaw);
        double dx = (strafe * Math.cos(radians) - forward * Math.sin(radians)) * speed;
        double dz = (forward * Math.cos(radians) + strafe * Math.sin(radians)) * speed;

        double x = current.x();
        double y = current.y();
        double z = current.z();
        boolean grounded = current.onGround();

        if (dx != 0.0 && canOccupy(x + dx, y, z)) {
            x += dx;
        }
        if (dz != 0.0 && canOccupy(x, y, z + dz)) {
            z += dz;
        }

        double velocityY = current.velocityY();
        if (grounded && input.jump()) {
            velocityY = JUMP_VELOCITY;
            grounded = false;
        } else if (grounded) {
            velocityY = 0.0;
        } else {
            velocityY = Math.max(-MAX_FALL_SPEED, velocityY - GRAVITY);
        }

        double nextY = y + velocityY;
        if (canOccupy(x, nextY, z)) {
            y = nextY;
        } else if (velocityY > 0.0) {
            y = Math.ceil(y + HEIGHT - EPSILON) - HEIGHT;
            velocityY = 0.0;
        } else {
            y = Math.floor(y + EPSILON);
            velocityY = 0.0;
            grounded = true;
        }

        if (!grounded && velocityY <= 0.0 && hasSupport(x, y, z)) {
            grounded = true;
            velocityY = 0.0;
        }

        state = new State(x, y, z, yaw, velocityY, grounded,
                Math.incrementExact(current.tick()));
        return state;
    }

    private boolean hasSupport(double x, double y, double z) {
        double half = WIDTH / 2.0 - EPSILON;
        int blockY = floor(y - EPSILON);
        return solidAt(floor(x - half), blockY, floor(z - half))
                || solidAt(floor(x + half), blockY, floor(z - half))
                || solidAt(floor(x - half), blockY, floor(z + half))
                || solidAt(floor(x + half), blockY, floor(z + half));
    }

    private boolean canOccupy(double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000
                || y < world.height().minY() || y + HEIGHT >= world.height().maxYExclusive()) {
            return false;
        }
        double half = WIDTH / 2.0 - EPSILON;
        int minX = floor(x - half);
        int maxX = floor(x + half);
        int minY = floor(y + EPSILON);
        int maxY = floor(y + HEIGHT - EPSILON);
        int minZ = floor(z - half);
        int maxZ = floor(z + half);
        for (int blockX = minX; blockX <= maxX; blockX++) {
            for (int blockY = minY; blockY <= maxY; blockY++) {
                for (int blockZ = minZ; blockZ <= maxZ; blockZ++) {
                    if (solidAt(blockX, blockY, blockZ)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean solidAt(int x, int y, int z) {
        if (!world.height().contains(y)) {
            return true;
        }
        ChunkPosition chunk = ChunkPosition.fromBlock(x, z);
        if (!world.isChunkLoaded(chunk)) {
            return true;
        }
        return !world.blockAt(new BlockPosition(x, y, z)).isAir();
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float normalizeYaw(double yaw) {
        double normalized = yaw % 360.0;
        if (normalized > 180.0) normalized -= 360.0;
        if (normalized < -180.0) normalized += 360.0;
        return (float) normalized;
    }

    private static void validatePosition(double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000) {
            throw new IllegalArgumentException("Player position is outside supported world coordinates");
        }
    }

    private static void validateYaw(float yaw) {
        if (!Float.isFinite(yaw)) {
            throw new IllegalArgumentException("Player yaw must be finite");
        }
    }

    public record Input(double forward, double strafe, boolean jump, boolean sprint, float lookYaw) {
        public Input {
            if (!Double.isFinite(forward) || !Double.isFinite(strafe) || !Float.isFinite(lookYaw)) {
                throw new IllegalArgumentException("Player input must be finite");
            }
        }

        public static Input idle() {
            return new Input(0.0, 0.0, false, false, 0.0f);
        }
    }

    public record State(
            double x, double y, double z, float yaw,
            double velocityY, boolean onGround, long tick
    ) {
        public State {
            validatePosition(x, y, z);
            validateYaw(yaw);
            if (!Double.isFinite(velocityY) || tick < 0) {
                throw new IllegalArgumentException("Invalid player physics state");
            }
        }

        public ChunkPosition chunkPosition() {
            return ChunkPosition.fromBlock(floor(x), floor(z));
        }
    }
}
