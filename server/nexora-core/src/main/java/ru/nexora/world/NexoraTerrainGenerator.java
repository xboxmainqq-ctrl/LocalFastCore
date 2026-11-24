package ru.nexora.world;

/**
 * NEXORA's custom deterministic terrain: broad continental forms, warped mountain
 * chains, rolling foothills, eroded valleys and material changes driven by slope.
 * It is deliberately independent of chunk generation order and external libraries.
 */
public final class NexoraTerrainGenerator implements WorldGenerator {
    public static final int SPAWN_SURFACE_Y = 64;
    private static final int SPAWN_RADIUS = 9;
    private static final int SPAWN_BLEND_RADIUS = 58;
    private static final int MIN_SURFACE = 48;
    private static final int MAX_SURFACE = 150;

    @Override
    public void generate(WorldChunk chunk, long seed) {
        int originX = chunk.chunkX() * 16;
        int originZ = chunk.chunkZ() * 16;
        int[][] heights = new int[18][18];

        // One-block border lets the surface material react to real cross-chunk slopes.
        for (int x = -1; x <= 16; x++) {
            for (int z = -1; z <= 16; z++) {
                heights[x + 1][z + 1] = surfaceHeight(originX + x, originZ + z, seed);
            }
        }

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int worldX = originX + x;
                int worldZ = originZ + z;
                int surface = heights[x + 1][z + 1];
                int slope = Math.max(
                        Math.max(Math.abs(surface - heights[x][z + 1]),
                                Math.abs(surface - heights[x + 2][z + 1])),
                        Math.max(Math.abs(surface - heights[x + 1][z]),
                                Math.abs(surface - heights[x + 1][z + 2])));
                double river = riverStrength(worldX, worldZ, seed);

                chunk.setBlock(x, WorldChunk.MIN_Y, z, BlockType.BEDROCK);
                int soilDepth = surface > 112 ? 1 : surface > 88 ? 2 : 3 + (int) (moisture(worldX, worldZ, seed) * 2.0);
                boolean rocky = surface > 112 || slope >= 5;
                boolean gravelBank = river > 0.62 && surface < 76;

                for (int y = WorldChunk.MIN_Y + 1; y < surface; y++) {
                    int depth = surface - y;
                    BlockType material;
                    if (depth <= soilDepth) {
                        if (depth == 1) {
                            material = gravelBank ? BlockType.GRAVEL
                                    : rocky ? BlockType.STONE : BlockType.GRASS_BLOCK;
                        } else {
                            material = gravelBank ? BlockType.GRAVEL
                                    : rocky ? BlockType.STONE : BlockType.DIRT;
                        }
                    } else if (depth <= soilDepth + 3 && gravelBank) {
                        material = BlockType.GRAVEL;
                    } else {
                        material = BlockType.STONE;
                    }
                    chunk.setBlock(x, y, z, material);
                }
                // Surface is written separately so every column has one clear top block.
                chunk.setBlock(x, surface, z,
                        gravelBank ? BlockType.GRAVEL : rocky ? BlockType.STONE : BlockType.GRASS_BLOCK);
            }
        }
    }

    /** Stable world-space height query, shared by neighboring chunks. */
    public static int surfaceHeight(int x, int z, long seed) {
        double distance = Math.max(Math.abs((double) x), Math.abs((double) z));
        if (distance <= SPAWN_RADIUS) return SPAWN_SURFACE_Y;

        double warpX = fractal(x, z, seed ^ 0x243f6a8885a308d3L, 190) * 34.0;
        double warpZ = fractal(x, z, seed ^ 0x13198a2e03707344L, 190) * 34.0;
        double wx = x + warpX;
        double wz = z + warpZ;

        double continents = fractal(wx, wz, seed ^ 0xa4093822299f31d0L, 310);
        double foothills = fractal(wx, wz, seed ^ 0x082efa98ec4e6c89L, 112);
        double detail = fractal(wx, wz, seed ^ 0x452821e638d01377L, 38);
        double ridges = 1.0 - Math.abs(fractal(wx, wz, seed ^ 0xbe5466cf34e90c6cL, 145));
        double mountainRegion = clamp((fractal(wx, wz, seed ^ 0xc0ac29b7c97c50ddL, 430) + 0.18) * 1.15, 0.0, 1.0);
        double mountainPeaks = Math.pow(Math.max(0.0, ridges), 2.2) * mountainRegion;

        double river = riverStrength(x, z, seed);
        double raw = 68.0 + continents * 13.0 + foothills * 11.0
                + detail * 2.8 + mountainPeaks * 57.0 - river * 9.0;

        // Blend the natural terrain into the protected spawn plateau; no abrupt cliff.
        double blend = smoothstep(SPAWN_RADIUS, SPAWN_BLEND_RADIUS, distance);
        double height = SPAWN_SURFACE_Y + (raw - SPAWN_SURFACE_Y) * blend;
        return (int) Math.round(clamp(height, MIN_SURFACE, MAX_SURFACE));
    }

    private static double riverStrength(double x, double z, long seed) {
        double warpX = fractal(x, z, seed ^ 0x3c6ef372fe94f82bL, 230) * 26.0;
        double warpZ = fractal(x, z, seed ^ 0xa54ff53a5f1d36f1L, 230) * 26.0;
        double channel = Math.abs(valueNoise(x + warpX, z + warpZ,
                seed ^ 0x510e527fade682d1L, 175));
        return 1.0 - smoothstep(0.025, 0.105, channel);
    }

    private static double moisture(int x, int z, long seed) {
        return (fractal(x, z, seed ^ 0x9b05688c2b3e6c1fL, 270) + 1.0) * 0.5;
    }

    private static double fractal(double x, double z, long seed, double scale) {
        double sum = 0.0;
        double amplitude = 1.0;
        double normalization = 0.0;
        for (int octave = 0; octave < 4; octave++) {
            sum += valueNoise(x, z, seed + octave * 0x9e3779b97f4a7c15L, Math.max(2.0, scale));
            normalization += amplitude;
            amplitude *= 0.5;
            scale *= 0.5;
        }
        return sum / normalization;
    }

    private static double valueNoise(double x, double z, long seed, double scale) {
        double gx = x / scale;
        double gz = z / scale;
        int ix = (int) Math.floor(gx);
        int iz = (int) Math.floor(gz);
        double fx = fade(gx - ix);
        double fz = fade(gz - iz);
        double a = lattice(ix, iz, seed);
        double b = lattice(ix + 1, iz, seed);
        double c = lattice(ix, iz + 1, seed);
        double d = lattice(ix + 1, iz + 1, seed);
        return lerp(lerp(a, b, fx), lerp(c, d, fx), fz);
    }

    private static double lattice(int x, int z, long seed) {
        long value = seed ^ (long) x * 0x9e3779b97f4a7c15L ^ (long) z * 0xc2b2ae3d27d4eb4fL;
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        value ^= value >>> 31;
        return ((value >>> 11) * 0x1.0p-52) * 2.0 - 1.0;
    }

    private static double fade(double value) {
        return value * value * value * (value * (value * 6.0 - 15.0) + 10.0);
    }

    private static double smoothstep(double edge0, double edge1, double value) {
        double t = clamp((value - edge0) / (edge1 - edge0), 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
