package ru.nexora.world;

/**
 * Deterministic, chunk-order-independent Overworld terrain generator.
 *
 * The spawn area is kept level at Y=64 so existing spawn coordinates remain safe;
 * terrain outside that protected radius rolls into low hills and shallow valleys.
 */
public final class FlatWorldGenerator implements WorldGenerator {
    public static final int SURFACE_Y = 64;
    private static final int SPAWN_FLAT_RADIUS = 8;
    private static final int MIN_SURFACE_Y = 52;
    private static final int MAX_SURFACE_Y = 78;

    @Override
    public void generate(WorldChunk chunk, long seed) {
        int originX = chunk.chunkX() * 16;
        int originZ = chunk.chunkZ() * 16;
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldX = originX + localX;
                int worldZ = originZ + localZ;
                int surface = surfaceHeight(worldX, worldZ, seed);
                chunk.setBlock(localX, WorldChunk.MIN_Y, localZ, BlockType.BEDROCK);
                for (int y = WorldChunk.MIN_Y + 1; y < surface - 3; y++) {
                    chunk.setBlock(localX, y, localZ, BlockType.STONE);
                }
                for (int y = Math.max(WorldChunk.MIN_Y + 1, surface - 3); y < surface; y++) {
                    chunk.setBlock(localX, y, localZ, BlockType.DIRT);
                }
                chunk.setBlock(localX, surface, localZ, BlockType.GRASS_BLOCK);
            }
        }
    }

    /** Public for deterministic generator tests and spawn safety checks. */
    public static int surfaceHeight(int worldX, int worldZ, long seed) {
        if (Math.abs((long) worldX) <= SPAWN_FLAT_RADIUS
                && Math.abs((long) worldZ) <= SPAWN_FLAT_RADIUS) {
            return SURFACE_Y;
        }

        double broad = valueNoise(worldX, worldZ, seed, 72);
        double detail = valueNoise(worldX, worldZ, seed ^ 0x6a09e667f3bcc909L, 29);
        double ridge = 1.0 - Math.abs(valueNoise(worldX, worldZ, seed ^ 0xbb67ae8584caa73bL, 43));
        int height = SURFACE_Y + (int) Math.round(broad * 8.0 + detail * 3.0 + (ridge - 0.5) * 3.0);
        return Math.max(MIN_SURFACE_Y, Math.min(MAX_SURFACE_Y, height));
    }

    private static double valueNoise(int x, int z, long seed, int scale) {
        int cellX = Math.floorDiv(x, scale);
        int cellZ = Math.floorDiv(z, scale);
        double localX = smooth(Math.floorMod(x, scale) / (double) scale);
        double localZ = smooth(Math.floorMod(z, scale) / (double) scale);
        double a = lattice(cellX, cellZ, seed);
        double b = lattice(cellX + 1, cellZ, seed);
        double c = lattice(cellX, cellZ + 1, seed);
        double d = lattice(cellX + 1, cellZ + 1, seed);
        return lerp(lerp(a, b, localX), lerp(c, d, localX), localZ);
    }

    private static double lattice(int x, int z, long seed) {
        long value = seed ^ (long) x * 0x9e3779b97f4a7c15L ^ (long) z * 0xc2b2ae3d27d4eb4fL;
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        value ^= value >>> 31;
        return ((value >>> 11) * 0x1.0p-52) - 1.0;
    }

    private static double smooth(double value) {
        return value * value * (3.0 - 2.0 * value);
    }

    private static double lerp(double from, double to, double amount) {
        return from + (to - from) * amount;
    }
}
