package ru.nexora.world;

/**
 * Conservative swept-AABB collision checks for players and living mobs.
 * Positions are interpreted as the centre of the feet.
 */
public final class WorldCollision {
    private static final double PLAYER_HALF_WIDTH = 0.3;
    private static final double PLAYER_HEIGHT = 1.8;
    private static final double SAMPLE_SPACING = 0.2;
    private static final int MAX_SWEEP_SAMPLES = 512;
    private static final double EDGE_EPSILON = 1.0e-5;

    private WorldCollision() {
    }

    public static boolean canMove(World world, double fromX, double fromY, double fromZ,
                                  double toX, double toY, double toZ) {
        return canMove(world, fromX, fromY, fromZ, toX, toY, toZ,
                PLAYER_HALF_WIDTH, PLAYER_HEIGHT);
    }

    /** Sweeps the species-sized mob box through the movement segment. */
    public static boolean canMoveMob(World world, String identifier,
                                     double fromX, double fromY, double fromZ,
                                     double toX, double toY, double toZ) {
        Dimensions dimensions = dimensionsForMob(identifier);
        return canMove(world, fromX, fromY, fromZ, toX, toY, toZ,
                dimensions.width() / 2.0, dimensions.height());
    }

    /**
     * Validates a grounded navigation edge. One-block climbs are performed as lift-then-walk;
     * one-block drops as walk-then-drop, so mobs cannot clip through the side of a ledge.
     */
    public static boolean canStepMob(World world, String identifier,
                                     double fromX, double fromY, double fromZ,
                                     double toX, double toY, double toZ) {
        if (world == null || identifier == null || identifier.isBlank()
                || !finite(fromX, fromY, fromZ, toX, toY, toZ)) return false;
        double dy = toY - fromY;
        if (Math.abs(dy) > 1.001 || Math.hypot(toX - fromX, toZ - fromZ) > 1.5) return false;
        boolean traversable;
        if (dy > 0.05) {
            traversable = canMoveMob(world, identifier, fromX, fromY, fromZ,
                    fromX, toY, fromZ)
                    && canMoveMob(world, identifier, fromX, toY, fromZ, toX, toY, toZ);
        } else if (dy < -0.05) {
            traversable = canMoveMob(world, identifier, fromX, fromY, fromZ,
                    toX, fromY, toZ)
                    && canMoveMob(world, identifier, toX, fromY, toZ, toX, toY, toZ);
        } else {
            traversable = canMoveMob(world, identifier, fromX, fromY, fromZ,
                    toX, toY, toZ);
        }
        return traversable && isMobOnGround(world, identifier, toX, toY, toZ, 0.12);
    }

    private static boolean canMove(World world, double fromX, double fromY, double fromZ,
                                   double toX, double toY, double toZ,
                                   double halfWidth, double height) {
        if (world == null || !finite(fromX, fromY, fromZ, toX, toY, toZ)) return false;
        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!Double.isFinite(distance)) return false;
        double requiredSamples = Math.ceil(distance / SAMPLE_SPACING);
        if (requiredSamples > MAX_SWEEP_SAMPLES) return false;
        int samples = Math.max(1, (int) requiredSamples);
        for (int step = 1; step <= samples; step++) {
            double fraction = (double) step / samples;
            if (!canOccupy(world, fromX + dx * fraction, fromY + dy * fraction,
                    fromZ + dz * fraction, halfWidth, height)) return false;
        }
        return true;
    }

    public static boolean isOnGround(World world, double x, double feetY, double z) {
        return isOnGround(world, x, feetY, z, 0.05, PLAYER_HALF_WIDTH);
    }

    public static boolean isOnGround(World world, double x, double feetY, double z,
                                     double contactTolerance) {
        return isOnGround(world, x, feetY, z, contactTolerance, PLAYER_HALF_WIDTH);
    }

    /** Ground support query using the actual footprint of the mob species. */
    public static boolean isMobOnGround(World world, String identifier,
                                        double x, double feetY, double z,
                                        double contactTolerance) {
        return isOnGround(world, x, feetY, z, contactTolerance,
                dimensionsForMob(identifier).width() / 2.0);
    }

    private static boolean isOnGround(World world, double x, double feetY, double z,
                                      double contactTolerance, double halfWidth) {
        if (world == null || !finite(x, feetY, z, contactTolerance)
                || contactTolerance <= 0.0 || contactTolerance > 1.0
                || feetY <= WorldChunk.MIN_Y || feetY > WorldChunk.MAX_Y + 1.0) return false;
        double probeY = feetY - contactTolerance;
        int supportY = (int) Math.floor(probeY);
        if (supportY < WorldChunk.MIN_Y || supportY > WorldChunk.MAX_Y) return false;
        double minX = x - halfWidth + EDGE_EPSILON;
        double maxX = x + halfWidth - EDGE_EPSILON;
        double minZ = z - halfWidth + EDGE_EPSILON;
        double maxZ = z + halfWidth - EDGE_EPSILON;
        if (!withinWorldBorder(minX, maxX, minZ, maxZ)) return false;
        for (int blockX = (int) Math.floor(minX); blockX <= (int) Math.floor(maxX); blockX++) {
            for (int blockZ = (int) Math.floor(minZ); blockZ <= (int) Math.floor(maxZ); blockZ++) {
                if (!world.isChunkLoadedAt(blockX, blockZ)) return false;
                if (world.getBlock(blockX, supportY, blockZ) != BlockType.AIR) return true;
            }
        }
        return false;
    }

    public static boolean canOccupy(World world, double x, double feetY, double z) {
        return canOccupy(world, x, feetY, z, PLAYER_HALF_WIDTH, PLAYER_HEIGHT);
    }

    /** Checks the complete species-sized mob bounding box against loaded blocks only. */
    public static boolean canOccupyMob(World world, String identifier,
                                       double x, double feetY, double z) {
        Dimensions dimensions = dimensionsForMob(identifier);
        return canOccupy(world, x, feetY, z, dimensions.width() / 2.0, dimensions.height());
    }

    private static boolean canOccupy(World world, double x, double feetY, double z,
                                     double halfWidth, double height) {
        if (world == null || !finite(x, feetY, z)) return false;
        double minX = x - halfWidth + EDGE_EPSILON;
        double maxX = x + halfWidth - EDGE_EPSILON;
        double minZ = z - halfWidth + EDGE_EPSILON;
        double maxZ = z + halfWidth - EDGE_EPSILON;
        double headY = feetY + height - EDGE_EPSILON;
        if (!withinWorldBorder(minX, maxX, minZ, maxZ)
                || feetY < WorldChunk.MIN_Y || headY > WorldChunk.MAX_Y) return false;
        int firstX = (int) Math.floor(minX);
        int lastX = (int) Math.floor(maxX);
        int firstY = (int) Math.floor(feetY + EDGE_EPSILON);
        int lastY = (int) Math.floor(headY);
        int firstZ = (int) Math.floor(minZ);
        int lastZ = (int) Math.floor(maxZ);
        for (int blockX = firstX; blockX <= lastX; blockX++) {
            for (int blockY = firstY; blockY <= lastY; blockY++) {
                for (int blockZ = firstZ; blockZ <= lastZ; blockZ++) {
                    if (!world.isChunkLoadedAt(blockX, blockZ)
                            || world.getBlock(blockX, blockY, blockZ) != BlockType.AIR) return false;
                }
            }
        }
        return true;
    }

    /** Approximate standing dimensions in blocks; slime size variants are not yet modeled. */
    public static Dimensions dimensionsForMob(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("Mob identifier must not be blank");
        }
        return switch (identifier) {
            case "minecraft:ghast" -> new Dimensions(4.0, 4.0);
            case "minecraft:ender_dragon" -> new Dimensions(8.0, 4.0);
            case "minecraft:ravager" -> new Dimensions(1.95, 2.2);
            case "minecraft:spider", "minecraft:cave_spider" -> new Dimensions(1.4, 0.9);
            case "minecraft:enderman", "minecraft:warden", "minecraft:wither" ->
                    new Dimensions(0.9, 2.9);
            case "minecraft:horse", "minecraft:donkey", "minecraft:mule", "minecraft:camel" ->
                    new Dimensions(1.4, 1.6);
            case "minecraft:iron_golem", "minecraft:polar_bear" -> new Dimensions(1.4, 2.7);
            case "minecraft:guardian", "minecraft:elder_guardian" -> new Dimensions(0.85, 0.85);
            case "minecraft:bat", "minecraft:allay", "minecraft:bee", "minecraft:parrot" ->
                    new Dimensions(0.6, 0.9);
            case "minecraft:silverfish", "minecraft:endermite" -> new Dimensions(0.4, 0.3);
            case "minecraft:chicken", "minecraft:rabbit", "minecraft:ocelot", "minecraft:cat",
                    "minecraft:fox", "minecraft:frog", "minecraft:turtle", "minecraft:armadillo" ->
                    new Dimensions(0.7, 0.7);
            default -> new Dimensions(0.6, 1.8);
        };
    }

    private static boolean withinWorldBorder(double minX, double maxX, double minZ, double maxZ) {
        return minX >= -30_000_000 && maxX <= 30_000_000
                && minZ >= -30_000_000 && maxZ <= 30_000_000;
    }

    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }

    public record Dimensions(double width, double height) {
        public Dimensions {
            if (!Double.isFinite(width) || !Double.isFinite(height)
                    || width <= 0 || height <= 0 || width > 16 || height > 16) {
                throw new IllegalArgumentException("Invalid entity dimensions");
            }
        }
    }
}
