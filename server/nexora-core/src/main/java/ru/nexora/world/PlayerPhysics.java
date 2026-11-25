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
        return new State(x, y, z, 0, 0, 0, WorldCollision.isOnGround(world, x, y, z), 0, 0);
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
        boolean wasGrounded = grounded;
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
        boolean blockedX = !WorldCollision.canMove(world, current.x(), current.y(), current.z(),
                nextX, current.y(), current.z());
        boolean blockedZ = !WorldCollision.canMove(world, nextX, current.y(), current.z(),
                nextX, current.y(), nextZ);
        if ((blockedX || blockedZ) && wasGrounded && inputLength > 0) {
            State stepped = tryStepUp(world, current, nextX, nextZ);
            if (stepped != null) {
                nextX = stepped.x();
                nextY = stepped.y();
                nextZ = stepped.z();
                vx = stepped.velocityX();
                vz = stepped.velocityZ();
                vy = 0;
                grounded = true;
                blockedX = false;
                blockedZ = false;
            }
        }
        if (blockedX) {
            nextX = current.x();
            vx = 0;
        }
        if (blockedZ) {
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
            return new State(current.x(), current.y(), current.z(), 0, 0, 0,
                    grounded, current.fallDistance(), 0);
        }

        double fallDistance = current.fallDistance();
        boolean descending = nextY < current.y();
        if (!wasGrounded && descending) fallDistance += current.y() - nextY;
        double landedDistance = !wasGrounded && grounded && descending ? fallDistance : 0;
        if (grounded) fallDistance = 0;
        return new State(nextX, nextY, nextZ, vx, vy, vz, grounded, fallDistance, landedDistance);
    }

    /**
     * Attempts the Bedrock-style one-block step assist. The raised player box
     * must clear the obstacle and the destination must have real ground support;
     * taller walls and unsupported ledges remain blocking.
     */
    private static State tryStepUp(World world, State current, double targetX, double targetZ) {
        final double stepHeight = 1.001;
        double raisedY = current.y() + stepHeight;
        if (!WorldCollision.canMove(world, current.x(), current.y(), current.z(),
                current.x(), raisedY, current.z())) return null;
        if (!WorldCollision.canMove(world, current.x(), raisedY, current.z(),
                targetX, raisedY, targetZ)) return null;

        double landingY = raisedY;
        boolean foundGround = false;
        // Check the fully raised position first. Starting one sample lower can
        // already intersect the side of the step, causing a valid landing to be
        // rejected before its support surface is examined.
        if (WorldCollision.canOccupy(world, targetX, landingY, targetZ)
                && WorldCollision.isOnGround(world, targetX, landingY, targetZ, 0.12)) {
            foundGround = true;
        }
        for (int i = 1; !foundGround && i <= 24; i++) {
            double candidateY = raisedY - i * 0.05;
            if (!WorldCollision.canOccupy(world, targetX, candidateY, targetZ)) break;
            landingY = candidateY;
            if (WorldCollision.isOnGround(world, targetX, candidateY, targetZ, 0.12)) {
                foundGround = true;
                break;
            }
        }
        if (!foundGround || landingY - current.y() < 0.05
                || landingY - current.y() > 1.01) return null;
        return new State(targetX, landingY, targetZ, 0, 0, 0,
                true, current.fallDistance(), 0);
    }

    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }

    public record State(
            double x, double y, double z,
            double velocityX, double velocityY, double velocityZ,
            boolean onGround, double fallDistance, double landedDistance
    ) {
        public State {
            if (!finite(x, y, z, velocityX, velocityY, velocityZ, fallDistance, landedDistance)
                    || fallDistance < 0 || landedDistance < 0
                    || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000
                    || y < WorldChunk.MIN_Y || y > WorldChunk.MAX_Y) {
                throw new IllegalArgumentException("Invalid player physics state");
            }
        }
    }

    public record Input(double strafe, double forward, double yaw, boolean sprinting, boolean jump) {
    }
}
