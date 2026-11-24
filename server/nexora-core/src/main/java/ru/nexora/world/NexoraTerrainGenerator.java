package ru.nexora.world;

/**
 * NEXORA's custom deterministic terrain: giant mountain chains, high plateaus,
 * deep eroded valleys and large impact/volcanic craters.
 */
public final class NexoraTerrainGenerator implements WorldGenerator {
    public static final int SPAWN_SURFACE_Y = 64;
    private static final int SPAWN_RADIUS = 9;
    private static final int SPAWN_BLEND_RADIUS = 58;
    private static final int MIN_SURFACE = -30;
    private static final int MAX_SURFACE = 200;
    private static final int CRATER_GRID = 620;

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
                int soilDepth = surface > 145 ? 1 : surface > 95 ? 2
                        : 3 + (int) (moisture(worldX, worldZ, seed) * 2.0);
                boolean rocky = surface > 125 || slope >= 5;
                boolean gravelBank = river > 0.62 && surface < 76;
                boolean volcanicFloor = surface <= -18
                        && fractal(worldX, worldZ, seed ^ 0x6a09e667f3bcc909L, 21) > 0.05;

                for (int y = WorldChunk.MIN_Y + 1; y < surface; y++) {
                    int depth = surface - y;
                    BlockType material;
                    if (depth <= soilDepth) {
                        material = gravelBank ? BlockType.GRAVEL
                                : rocky || volcanicFloor ? BlockType.STONE : BlockType.DIRT;
                    } else if (depth <= soilDepth + 3 && gravelBank) {
                        material = BlockType.GRAVEL;
                    } else {
                        material = BlockType.STONE;
                    }
                    chunk.setBlock(x, y, z, material);
                }

                BlockType top = volcanicFloor ? BlockType.OBSIDIAN
                        : gravelBank ? BlockType.GRAVEL
                        : rocky ? BlockType.STONE : BlockType.GRASS_BLOCK;
                chunk.setBlock(x, surface, z, top);
            }
        }
        generateAncientRuins(chunk, seed);
    }

    /**
     * Places deterministic, partially collapsed stone watchtowers and perimeter walls.
     * Candidate coordinates are world-based, so a ruin crossing a chunk edge is
     * assembled identically regardless of chunk generation order.
     */
    private static void generateAncientRuins(WorldChunk chunk, long seed) {
        final int grid = 512;
        int minX = chunk.chunkX() * 16;
        int minZ = chunk.chunkZ() * 16;
        int cellMinX = Math.floorDiv(minX - 24, grid);
        int cellMaxX = Math.floorDiv(minX + 15 + 24, grid);
        int cellMinZ = Math.floorDiv(minZ - 24, grid);
        int cellMaxZ = Math.floorDiv(minZ + 15 + 24, grid);

        for (int cellX = cellMinX; cellX <= cellMaxX; cellX++) {
            for (int cellZ = cellMinZ; cellZ <= cellMaxZ; cellZ++) {
                long key = seed ^ ((long) cellX * 0x9e3779b97f4a7c15L)
                        ^ ((long) cellZ * 0xc2b2ae3d27d4eb4fL) ^ 0x3f84d5b5b5470917L;
                if (unitHash(key) > 0.38) continue;
                int centerX = cellX * grid + grid / 2
                        + (int) ((unitHash(key ^ 0x9216d5d98979fb1bL) - 0.5) * 150.0);
                int centerZ = cellZ * grid + grid / 2
                        + (int) ((unitHash(key ^ 0xd1310ba698dfb5acL) - 0.5) * 150.0);
                if (Math.max(Math.abs(centerX), Math.abs(centerZ)) < 520) continue;
                // Only build candidates whose footprint can touch this chunk. Without
                // this cheap world-space rejection every chunk in a 512-block cell
                // would repeatedly evaluate the same remote structure.
                if (centerX + 24 < minX || centerX - 24 > minX + 15
                        || centerZ + 24 < minZ || centerZ - 24 > minZ + 15) continue;

                int baseY = surfaceHeight(centerX, centerZ, seed);
                if (baseY < 48 || baseY > 132) continue;
                int minHeight = baseY;
                int maxHeight = baseY;
                for (int sx : new int[]{-10, 10}) {
                    for (int sz : new int[]{-10, 10}) {
                        int h = surfaceHeight(centerX + sx, centerZ + sz, seed);
                        minHeight = Math.min(minHeight, h);
                        maxHeight = Math.max(maxHeight, h);
                    }
                }
                if (maxHeight - minHeight > 7) continue;

                int towerHeight = 13 + (int) (unitHash(key ^ 0x2ffd72dbd01adfb7L) * 11.0);
                buildRuinTower(chunk, centerX, centerZ, baseY, towerHeight, key, seed);
                buildRuinPerimeter(chunk, centerX, centerZ, key, seed);
            }
        }
    }

    private static void buildRuinTower(WorldChunk chunk, int cx, int cz, int baseY,
                                       int height, long key, long seed) {
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                int distance = Math.max(Math.abs(dx), Math.abs(dz));
                if (distance != 4) continue;
                int wx = cx + dx;
                int wz = cz + dz;
                int ground = surfaceHeight(wx, wz, seed);
                for (int y = ground + 1; y <= baseY + height; y++) {
                    int level = y - baseY;
                    boolean corner = Math.abs(dx) == 4 && Math.abs(dz) == 4;
                    boolean window = !corner && (level == 5 || level == 9)
                            && ((dx + dz) & 1) == 0;
                    if (window) {
                        placeRuinBlock(chunk, wx, y, wz, BlockType.GLASS, seed);
                    } else if (level == height && ((dx * 3 + dz * 5) & 3) == 0) {
                        // Missing roof blocks make the tower look weathered rather than pristine.
                        continue;
                    } else {
                        placeRuinBlock(chunk, wx, y, wz,
                                corner ? BlockType.COBBLESTONE : BlockType.STONE, seed);
                    }
                }
            }
        }

        for (int dx : new int[]{-4, 4}) {
            for (int dz : new int[]{-4, 4}) {
                int wx = cx + dx;
                int wz = cz + dz;
                int ground = surfaceHeight(wx, wz, seed);
                for (int y = ground + 1; y <= baseY + height + 2; y++) {
                    placeRuinBlock(chunk, wx, y, wz, BlockType.COBBLESTONE, seed);
                }
                placeRuinBlock(chunk, wx, baseY + height + 3, wz, BlockType.OBSIDIAN, seed);
            }
        }

        // Broken interior floors and a surviving central support beam.
        for (int level : new int[]{5, 10}) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    if (((dx * 7 + dz * 11 + level) & 3) == 0) continue;
                    placeRuinBlock(chunk, cx + dx, baseY + level, cz + dz,
                            ((dx + dz) & 3) == 0 ? BlockType.OAK_PLANKS : BlockType.COBBLESTONE, seed);
                }
            }
        }
        for (int y = baseY + 1; y <= baseY + height - 2; y++) {
            placeRuinBlock(chunk, cx, y, cz, BlockType.OAK_LOG, seed);
        }
    }

    private static void buildRuinPerimeter(WorldChunk chunk, int cx, int cz,
                                           long key, long seed) {
        for (int side = 0; side < 4; side++) {
            for (int offset = -10; offset <= 10; offset++) {
                int wx = side < 2 ? cx + offset : cx + (side == 2 ? -10 : 10);
                int wz = side < 2 ? cz + (side == 0 ? -10 : 10) : cz + offset;
                long segment = key ^ ((long) side << 32) ^ offset * 0x9e3779b9L;
                if (unitHash(segment) < 0.22) continue;
                int ground = surfaceHeight(wx, wz, seed);
                int wallHeight = 2 + (int) (unitHash(segment ^ 0x5be0cd19137e2179L) * 3.0);
                for (int y = ground + 1; y <= ground + wallHeight; y++) {
                    placeRuinBlock(chunk, wx, y, wz,
                            y == ground + wallHeight && (offset & 1) == 0
                                    ? BlockType.COBBLESTONE : BlockType.STONE, seed);
                }
            }
        }

        // Fallen masonry and old road stones around the entrance.
        for (int i = 0; i < 13; i++) {
            long rubble = key ^ (i * 0x94d049bb133111ebL);
            int dx = (int) ((unitHash(rubble) - 0.5) * 27.0);
            int dz = (int) ((unitHash(rubble ^ 0x428a2f98d728ae22L) - 0.5) * 27.0);
            if (Math.max(Math.abs(dx), Math.abs(dz)) < 6) continue;
            int wx = cx + dx;
            int wz = cz + dz;
            int ground = surfaceHeight(wx, wz, seed);
            placeRuinBlock(chunk, wx, ground + 1, wz,
                    unitHash(rubble ^ 0x7137449123ef65cdL) > 0.5
                            ? BlockType.COBBLESTONE : BlockType.STONE, seed);
        }
    }

    private static void placeRuinBlock(WorldChunk chunk, int worldX, int y, int worldZ,
                                        BlockType type, long seed) {
        if (chunk.chunkX() != Math.floorDiv(worldX, 16)
                || chunk.chunkZ() != Math.floorDiv(worldZ, 16)
                || y < WorldChunk.MIN_Y + 1 || y > WorldChunk.MAX_Y
                || y <= surfaceHeight(worldX, worldZ, seed)) return;
        chunk.setBlock(Math.floorMod(worldX, 16), y, Math.floorMod(worldZ, 16), type);
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
        double mountainRegion = clamp((fractal(wx, wz, seed ^ 0xc0ac29b7c97c50ddL, 430) + 0.08) * 1.35, 0.0, 1.0);
        double mountainPeaks = Math.pow(Math.max(0.0, ridges), 1.65) * mountainRegion;

        double river = riverStrength(x, z, seed);
        double raw = 72.0 + continents * 17.0 + foothills * 14.0
                + detail * 3.5 + mountainPeaks * 112.0 - river * 15.0;

        // Carve deterministic giant basins, with irregular walls and raised crater rims.
        double cratered = applyCraterBasins(x, z, seed, raw);

        // Blend the natural terrain into the protected spawn plateau; no abrupt cliff.
        double blend = smoothstep(SPAWN_RADIUS, SPAWN_BLEND_RADIUS, distance);
        double height = SPAWN_SURFACE_Y + (cratered - SPAWN_SURFACE_Y) * blend;
        return (int) Math.round(clamp(height, MIN_SURFACE, MAX_SURFACE));
    }

    private static double applyCraterBasins(int x, int z, long seed, double naturalHeight) {
        int cellX = floorDiv(x, CRATER_GRID);
        int cellZ = floorDiv(z, CRATER_GRID);
        double result = naturalHeight;

        // Check neighboring cells so craters remain continuous across chunk borders.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int cx = cellX + dx;
                int cz = cellZ + dz;
                long craterSeed = seed ^ ((long) cx * 0x9e3779b97f4a7c15L)
                        ^ ((long) cz * 0xc2b2ae3d27d4eb4fL) ^ 0xbb67ae8584caa73bL;
                if (unitHash(craterSeed) > 0.72) continue;

                double centerX = cx * (double) CRATER_GRID + CRATER_GRID * 0.5
                        + (unitHash(craterSeed ^ 0x3c6ef372fe94f82bL) - 0.5) * 250.0;
                double centerZ = cz * (double) CRATER_GRID + CRATER_GRID * 0.5
                        + (unitHash(craterSeed ^ 0xa54ff53a5f1d36f1L) - 0.5) * 250.0;
                // Keep the starter area free of giant holes.
                if (Math.max(Math.abs(centerX), Math.abs(centerZ)) < 430.0) continue;

                double radius = 112.0 + unitHash(craterSeed ^ 0x510e527fade682d1L) * 72.0;
                double ox = x - centerX;
                double oz = z - centerZ;
                double radialNoise = fractal(x, z, craterSeed ^ 0x1f83d9abfb41bd6bL, 31) * 0.055;
                double radial = Math.sqrt(ox * ox + oz * oz) / radius + radialNoise;
                if (radial >= 1.20) continue;

                double bowl = -30.0 + (naturalHeight + 30.0) * smoothstep(0.0, 0.91, radial);
                double rimDistance = (radial - 0.94) / 0.105;
                double rim = 27.0 * Math.exp(-rimDistance * rimDistance);
                double craterShape = bowl + rim;
                double influence = 1.0 - smoothstep(0.91, 1.20, radial);
                double carved = lerp(naturalHeight, craterShape, influence);
                result = Math.min(result, carved);
            }
        }
        return result;
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

    private static double unitHash(long value) {
        value ^= value >>> 30;
        value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27;
        value *= 0x94d049bb133111ebL;
        value ^= value >>> 31;
        return (value >>> 11) * 0x1.0p-53;
    }

    private static int floorDiv(int value, int divisor) {
        return Math.floorDiv(value, divisor);
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
