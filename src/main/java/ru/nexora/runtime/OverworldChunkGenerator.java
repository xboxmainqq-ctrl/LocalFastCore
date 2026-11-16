package ru.nexora.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Seeded, protocol-independent overworld terrain generator.
 *
 * <p>Uses seeded macro-landforms, climate-selected biome surfaces and a
 * world-aligned watershed field for river channels and closed-basin lakes.
 * Drainage tiles are shared by all chunks within a fixed macro region, so
 * independently generated neighboring chunks agree on the same watercourse.</p>
 */
public final class OverworldChunkGenerator implements ChunkGenerator {
    public static final int DEFAULT_SEA_LEVEL = 62;

    private static final BlockState BEDROCK = new BlockState("minecraft:bedrock");
    private static final BlockState STONE = new BlockState("minecraft:stone");
    private static final BlockState DIRT = new BlockState("minecraft:dirt");
    private static final BlockState GRASS = new BlockState("minecraft:grass_block");
    private static final BlockState WATER = new BlockState("minecraft:water");
    private static final BlockState SAND = new BlockState("minecraft:sand");
    private static final BlockState GRAVEL = new BlockState("minecraft:gravel");
    private static final BlockState SNOW_BLOCK = new BlockState("minecraft:snow_block");
    private static final BlockState MOSS_BLOCK = new BlockState("minecraft:moss_block");
    private static final BlockState MOSS_CARPET = new BlockState("minecraft:moss_carpet");
    private static final BlockState GLOW_LICHEN = new BlockState("minecraft:glow_lichen");
    private static final BlockState SPORE_BLOSSOM = new BlockState("minecraft:spore_blossom");
    private static final BlockState HANGING_ROOTS = new BlockState("minecraft:hanging_roots");
    private static final BlockState CALCITE = new BlockState("minecraft:calcite");
    private static final BlockState AMETHYST_BLOCK = new BlockState("minecraft:amethyst_block");
    private static final BlockState AMETHYST_CLUSTER = new BlockState("minecraft:amethyst_cluster", Map.of("facing", "up", "waterlogged", "false"));
    private static final OreVein[] ORE_VEINS = {
            new OreVein("minecraft:coal_ore", -16, 192, 3, 4, 0x1a2b3c4dL),
            new OreVein("minecraft:iron_ore", -56, 72, 3, 3, 0x2b3c4d5eL),
            new OreVein("minecraft:copper_ore", -16, 112, 4, 3, 0x3c4d5e6fL),
            new OreVein("minecraft:gold_ore", -56, 32, 2, 2, 0x4d5e6f70L),
            new OreVein("minecraft:redstone_ore", -64, 16, 2, 2, 0x5e6f7081L),
            new OreVein("minecraft:diamond_ore", -64, 16, 2, 2, 0x6f708192L),
            new OreVein("minecraft:lapis_ore", -32, 64, 2, 2, 0x708192a3L)
    };
    private static final BlockState LOG = new BlockState("minecraft:oak_log");
    private static final BlockState LEAVES = new BlockState(
            "minecraft:oak_leaves", Map.of("persistent", "false", "update_bit", "false"));

    @Override
    public GeneratedChunk generate(ChunkPosition position, WorldHeight height, long seed) {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(height, "height");
        if (height.height() < 8) {
            throw new IllegalArgumentException("Overworld generation requires at least 8 vertical blocks");
        }

        final int baseX;
        final int baseZ;
        try {
            baseX = Math.multiplyExact(position.x(), 16);
            baseZ = Math.multiplyExact(position.z(), 16);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Chunk coordinates exceed supported block coordinates", exception);
        }

        int seaLevel = clamp(DEFAULT_SEA_LEVEL, height.minY() + 3, height.maxYExclusive() - 2);
        Map<BlockPosition, BlockState> blocks = new HashMap<>(12_000);
        Map<BlockPosition, BlockState> fluids = new HashMap<>(2_000);
        Map<BiomeColumnPosition, Integer> biomeColumns = new HashMap<>(256);
        BiomeDefinition[][] columnBiomes = new BiomeDefinition[16][16];
        BiomeRegistry biomeRegistry = BiomeRegistry.builtIn();
        HydrologyField hydrology = HydrologyField.forChunk(position, height, seed);
        boolean[][] waterColumns = new boolean[16][16];

        int[][] surfaceHeights = new int[16][16];
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int x = Math.addExact(baseX, localX);
                int z = Math.addExact(baseZ, localZ);
                int surfaceY = terrainHeight(x, z, seed, height);
                ClimateSample climate = BiomeClimateSampler.sample(x, z, seed, surfaceY);
                BiomeDefinition biome = biomeRegistry.select(climate);
                surfaceY = terrainHeight(x, z, seed, height,
                        biomeRegistry.blendedReliefMultiplier(climate));
                HydrologyField.Sample drainage = hydrology.sample(x, z, surfaceY);
                int waterSurfaceY = Integer.MIN_VALUE;
                if (drainage.kind() == HydrologyField.Kind.RIVER) {
                    waterSurfaceY = drainage.waterSurfaceY();
                    surfaceY = Math.max(height.minY() + 2, surfaceY - drainage.depth());
                    waterColumns[localX][localZ] = true;
                } else if (drainage.kind() == HydrologyField.Kind.LAKE) {
                    waterSurfaceY = drainage.waterSurfaceY();
                    waterColumns[localX][localZ] = true;
                }
                columnBiomes[localX][localZ] = biome;
                surfaceHeights[localX][localZ] = surfaceY;
                BlockPosition surfacePosition = new BlockPosition(x, surfaceY, z);
                biomeColumns.put(new BiomeColumnPosition(x, z), biome.id());

                BlockState topMaterial = surfaceMaterial(biome, climate, surfaceY, seaLevel, seed, x, z);
                BlockState underMaterial = topMaterial == SAND || topMaterial == GRAVEL || topMaterial == STONE
                        ? topMaterial : biome.subsurfaceBlock();
                blocks.put(new BlockPosition(x, height.minY(), z), BEDROCK);
                for (int y = height.minY() + 1; y < surfaceY - 3; y++) {
                    blocks.put(new BlockPosition(x, y, z), STONE);
                }
                for (int y = Math.max(height.minY() + 1, surfaceY - 3); y < surfaceY; y++) {
                    blocks.put(new BlockPosition(x, y, z), underMaterial);
                }
                blocks.put(surfacePosition, topMaterial);

                int fluidTop = Math.max(seaLevel, waterSurfaceY);
                for (int y = surfaceY + 1; y <= fluidTop && y < height.maxYExclusive(); y++) {
                    fluids.put(new BlockPosition(x, y, z), WATER);
                }
            }
        }

        carveCaves(baseX, baseZ, height, seed, surfaceHeights, fluids, blocks);
        decorateLushCaves(baseX, baseZ, height, seed, blocks, fluids);
        generateUndergroundWater(baseX, baseZ, height, seed, blocks, fluids);
        decorateCrystalGeodes(baseX, baseZ, height, seed, blocks, fluids);
        placeTrees(position, baseX, baseZ, height, seed, surfaceHeights, columnBiomes, waterColumns, blocks);
        return new GeneratedChunk(position, blocks, Map.of(), fluids, biomeColumns, height);
    }

    /**
     * Applies natural alpine, exposed-rock and shoreline transitions on top of
     * the climate biome. The same world-coordinate hash is used on every chunk.
     */
    private static BlockState surfaceMaterial(
            BiomeDefinition biome, ClimateSample climate, int surfaceY,
            int seaLevel, long seed, int x, int z) {
        double snowLine = 142.0 - climate.temperature() * 42.0;
        if (surfaceY >= snowLine) {
            return SNOW_BLOCK;
        }
        if (surfaceY >= 104 && biome.reliefMultiplier() >= 1.05) {
            return STONE;
        }
        if (surfaceY >= seaLevel - 4 && surfaceY <= seaLevel + 4) {
            long shore = coordinateHash(seed ^ 0x3f84d5b5b5470917L, x, z);
            return Long.remainderUnsigned(shore, 6) == 0 ? GRAVEL : SAND;
        }
        return biome.surfaceBlock();
    }

    private static int terrainHeight(int x, int z, long seed, WorldHeight worldHeight) {
        return TerrainHeightSampler.sample(x, z, seed, worldHeight, 1.0);
    }

    private static int terrainHeight(
            int x, int z, long seed, WorldHeight worldHeight, double reliefMultiplier) {
        return TerrainHeightSampler.sample(x, z, seed, worldHeight, reliefMultiplier);
    }

    /**
     * Carves connected, seed-stable cave systems in the deep stone layers.
     * Samples absolute coordinates so caves continue cleanly across chunk edges.
     */
    private static void carveCaves(
            int baseX, int baseZ, WorldHeight height, long seed,
            int[][] surfaceHeights, Map<BlockPosition, BlockState> fluids,
            Map<BlockPosition, BlockState> blocks) {
        int caveMinY = Math.max(height.minY() + 2, -54);
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int x = baseX + localX;
                int z = baseZ + localZ;
                int caveMaxY = Math.min(Math.min(surfaceHeights[localX][localZ] - 5, 48),
                        height.maxYExclusive() - 2);
                for (int y = caveMinY; y <= caveMaxY; y++) {
                    BlockPosition position = new BlockPosition(x, y, z);
                    if (!fluids.containsKey(position) && isCave(x, y, z, seed)) {
                        BlockState existing = blocks.get(position);
                        if (existing != null && existing.identifier().equals("minecraft:stone")) {
                            blocks.remove(position);
                        }
                    }
                }
            }
        }
    }

    /**
     * Places compact, irregular ore veins from world-aligned 3D cells.
     * A vein has the same center and shape regardless of which chunk is generated.
     */
    private static void placeOreVeins(
            int baseX, int baseZ, WorldHeight height, long seed,
            Map<BlockPosition, BlockState> blocks) {
        for (OreVein ore : ORE_VEINS) {
            int minY = Math.max(height.minY() + 1, ore.minY());
            int maxY = Math.min(height.maxYExclusive() - 1, ore.maxY());
            if (minY > maxY) continue;
            int minCellX = Math.floorDiv(baseX - ore.radius(), 16);
            int maxCellX = Math.floorDiv(baseX + 15 + ore.radius(), 16);
            int minCellZ = Math.floorDiv(baseZ - ore.radius(), 16);
            int maxCellZ = Math.floorDiv(baseZ + 15 + ore.radius(), 16);
            int minCellY = Math.floorDiv(minY - ore.radiusY(), 12);
            int maxCellY = Math.floorDiv(maxY + ore.radiusY(), 12);
            BlockState oreState = new BlockState(ore.identifier());
            for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
                for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                    for (int cellY = minCellY; cellY <= maxCellY; cellY++) {
                        long hash = featureHash(seed ^ ore.salt(), cellX, cellY, cellZ);
                        int centerX = cellX * 16 + 3 + (int) Long.remainderUnsigned(hash, 10);
                        int centerY = cellY * 12 + 2 + (int) Long.remainderUnsigned(hash >>> 9, 8);
                        int centerZ = cellZ * 16 + 3 + (int) Long.remainderUnsigned(hash >>> 17, 10);
                        int radius = Math.max(1, ore.radius()
                                - (int) Long.remainderUnsigned(hash >>> 25, 2));
                        for (int dx = -radius; dx <= radius; dx++) {
                            for (int dy = -ore.radiusY(); dy <= ore.radiusY(); dy++) {
                                for (int dz = -radius; dz <= radius; dz++) {
                                    double shape = (double) (dx * dx + dz * dz) / (radius * radius)
                                            + (double) (dy * dy) / (ore.radiusY() * ore.radiusY());
                                    if (shape > 1.0) continue;
                                    int x = centerX + dx;
                                    int y = centerY + dy;
                                    int z = centerZ + dz;
                                    if (x < baseX || x >= baseX + 16 || z < baseZ || z >= baseZ + 16
                                            || y < minY || y > maxY) continue;
                                    BlockPosition position = new BlockPosition(x, y, z);
                                    BlockState existing = blocks.get(position);
                                    if (existing != null && existing.identifier().equals("minecraft:stone")) {
                                        blocks.put(position, oreState);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static long featureHash(long seed, int x, int y, int z) {
        long value = seed ^ ((long) x * 0x9e3779b97f4a7c15L)
                ^ ((long) y * 0xc2b2ae3d27d4eb4fL)
                ^ ((long) z * 0x165667b19e3779f9L);
        value ^= value >>> 30;
        value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27;
        value *= 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private record OreVein(
            String identifier, int minY, int maxY, int radius, int radiusY, long salt) { }

    static boolean isCave(int x, int y, int z, long seed) {
        // Broad chambers intersect thin, winding tunnels to form connected systems.
        double chambers = noise3d(x, y, z, seed, 38, 0x51a7c3d9L);
        double tunnels = noise3d(x, y, z, seed, 19, 0x2c6e8b41L);
        double vertical = noise3d(x, y, z, seed, 57, 0x7d319fa5L);
        double giantRooms = noise3d(x, y, z, seed, 76, 0x3e91b6d2L);
        boolean chamber = Math.abs(chambers) < 0.115 && Math.abs(vertical) < 0.72;
        boolean giantChamber = Math.abs(giantRooms) < 0.075 && Math.abs(vertical) < 0.84;
        boolean tunnel = Math.abs(tunnels) < 0.052 && Math.abs(chambers) < 0.34;
        return chamber || giantChamber || tunnel;
    }

    /**
     * Adds vegetation only inside naturally carved cave air and only in
     * world-aligned lush pockets. All placement decisions use absolute
     * coordinates so neighboring chunks produce matching cave biomes.
     */
    private static void decorateLushCaves(
            int baseX, int baseZ, WorldHeight height, long seed,
            Map<BlockPosition, BlockState> blocks, Map<BlockPosition, BlockState> fluids) {
        int minY = Math.max(height.minY() + 2, -52);
        int maxY = Math.min(height.maxYExclusive() - 2, 44);
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int x = baseX + localX;
                int z = baseZ + localZ;
                for (int y = minY; y <= maxY; y++) {
                    BlockPosition air = new BlockPosition(x, y, z);
                    if (blocks.containsKey(air) || fluids.containsKey(air)
                            || !isCave(x, y, z, seed) || !isLushCave(x, y, z, seed)) {
                        continue;
                    }

                    BlockPosition floor = new BlockPosition(x, y - 1, z);
                    BlockPosition ceiling = new BlockPosition(x, y + 1, z);
                    BlockState floorState = blocks.get(floor);
                    BlockState ceilingState = blocks.get(ceiling);
                    long roll = coordinateHash(seed ^ 0x6a09e667f3bcc909L, x, z)
                            ^ ((long) y * 0x9e3779b97f4a7c15L);

                    if (floorState != null && isSolidNaturalCaveSurface(floorState)) {
                        if (Long.remainderUnsigned(roll, 5) == 0) {
                            blocks.put(floor, MOSS_BLOCK);
                        } else if (Long.remainderUnsigned(roll >>> 3, 3) == 0) {
                            blocks.put(air, MOSS_CARPET);
                        }
                    }

                    if (ceilingState != null && isSolidNaturalCaveSurface(ceilingState)) {
                        if (Long.remainderUnsigned(roll >>> 7, 83) == 0) {
                            blocks.put(air, SPORE_BLOSSOM);
                        } else if (Long.remainderUnsigned(roll >>> 11, 13) == 0) {
                            blocks.put(air, HANGING_ROOTS);
                        }
                    }

                    boolean besideWall = isSolidNaturalCaveSurface(
                            blocks.get(new BlockPosition(x - 1, y, z)))
                            || isSolidNaturalCaveSurface(blocks.get(new BlockPosition(x + 1, y, z)))
                            || isSolidNaturalCaveSurface(blocks.get(new BlockPosition(x, y, z - 1)))
                            || isSolidNaturalCaveSurface(blocks.get(new BlockPosition(x, y, z + 1)));
                    if (besideWall && Long.remainderUnsigned(roll >>> 17, 4) == 0) {
                        blocks.put(air, GLOW_LICHEN);
                    }
                }
            }
        }
    }


    private static void generateUndergroundWater(
            int baseX, int baseZ, WorldHeight height, long seed,
            Map<BlockPosition, BlockState> blocks, Map<BlockPosition, BlockState> fluids) {
        int minY = Math.max(height.minY() + 2, -50);
        int maxY = Math.min(height.maxYExclusive() - 2, -8);
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int x = baseX + localX;
                int z = baseZ + localZ;
                double basin = valueNoise(x, z, seed, 88, 0x7a4d93c1L);
                if (basin > -0.22) continue;
                int waterline = -30 + (int) Math.round(valueNoise(
                        x, z, seed, 42, 0x19c6e2a7L) * 4.0);
                for (int y = minY; y <= Math.min(maxY, waterline); y++) {
                    BlockPosition position = new BlockPosition(x, y, z);
                    if (!blocks.containsKey(position) && !fluids.containsKey(position)
                            && isCave(x, y, z, seed)) {
                        fluids.put(position, WATER);
                    }
                }
            }
        }
    }

    private static void decorateCrystalGeodes(
            int baseX, int baseZ, WorldHeight height, long seed,
            Map<BlockPosition, BlockState> blocks, Map<BlockPosition, BlockState> fluids) {
        int minY = Math.max(height.minY() + 4, -48);
        int maxY = Math.min(height.maxYExclusive() - 4, 24);
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int x = baseX + localX;
                int z = baseZ + localZ;
                for (int y = minY; y <= maxY; y++) {
                    BlockPosition air = new BlockPosition(x, y, z);
                    if (blocks.containsKey(air) || fluids.containsKey(air)
                            || !isCave(x, y, z, seed)) continue;
                    long hash = featureHash(seed ^ 0x5be0cd19137e2179L,
                            Math.floorDiv(x, 24), Math.floorDiv(y, 20), Math.floorDiv(z, 24));
                    if (Long.remainderUnsigned(hash, 97) != 0) continue;
                    BlockPosition[] neighbors = {
                            new BlockPosition(x - 1, y, z), new BlockPosition(x + 1, y, z),
                            new BlockPosition(x, y - 1, z), new BlockPosition(x, y + 1, z),
                            new BlockPosition(x, y, z - 1), new BlockPosition(x, y, z + 1)
                    };
                    for (BlockPosition neighbor : neighbors) {
                        BlockState wall = blocks.get(neighbor);
                        if (wall != null && wall.identifier().equals("minecraft:stone")) {
                            blocks.put(neighbor, Long.remainderUnsigned(hash >>> 9, 3) == 0
                                    ? AMETHYST_BLOCK : CALCITE);
                        }
                    }
                    if (Long.remainderUnsigned(hash >>> 17, 3) == 0) {
                        blocks.put(air, AMETHYST_CLUSTER);
                    }
                }
            }
        }
    }

    private static boolean isLushCave(int x, int y, int z, long seed) {
        double pocket = noise3d(x, y, z, seed, 64, 0x4f1bbcdcL);
        double moisture = noise3d(x, y, z, seed, 31, 0x91e10da5L);
        return pocket < -0.12 && moisture > -0.25;
    }

    private static boolean isSolidNaturalCaveSurface(BlockState state) {
        if (state == null) return false;
        String id = state.identifier();
        return id.equals("minecraft:stone") || id.equals("minecraft:dirt")
                || id.equals("minecraft:clay") || id.equals("minecraft:deepslate");
    }

    private static double noise3d(int x, int y, int z, long seed, int scale, long salt) {
        double sx = (double) x / scale;
        double sy = (double) y / scale;
        double sz = (double) z / scale;
        int x0 = (int) Math.floor(sx);
        int y0 = (int) Math.floor(sy);
        int z0 = (int) Math.floor(sz);
        double tx = smooth(sx - x0);
        double ty = smooth(sy - y0);
        double tz = smooth(sz - z0);
        double c00 = lerp(lattice3d(seed, salt, x0, y0, z0),
                lattice3d(seed, salt, x0 + 1, y0, z0), tx);
        double c10 = lerp(lattice3d(seed, salt, x0, y0 + 1, z0),
                lattice3d(seed, salt, x0 + 1, y0 + 1, z0), tx);
        double c01 = lerp(lattice3d(seed, salt, x0, y0, z0 + 1),
                lattice3d(seed, salt, x0 + 1, y0, z0 + 1), tx);
        double c11 = lerp(lattice3d(seed, salt, x0, y0 + 1, z0 + 1),
                lattice3d(seed, salt, x0 + 1, y0 + 1, z0 + 1), tx);
        return lerp(lerp(c00, c10, ty), lerp(c01, c11, ty), tz);
    }

    private static double lattice3d(long seed, long salt, int x, int y, int z) {
        long value = seed ^ salt
                ^ ((long) x * 0x9e3779b97f4a7c15L)
                ^ ((long) y * 0xc2b2ae3d27d4eb4fL)
                ^ ((long) z * 0x165667b19e3779f9L);
        value ^= value >>> 30;
        value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27;
        value *= 0x94d049bb133111ebL;
        value ^= value >>> 31;
        return ((value >>> 11) * 0x1.0p-52) - 1.0;
    }

    private static void placeTrees(
            ChunkPosition chunk,
            int baseX,
            int baseZ,
            WorldHeight height,
            long seed,
            int[][] surfaceHeights,
            BiomeDefinition[][] columnBiomes,
            boolean[][] waterColumns,
            Map<BlockPosition, BlockState> blocks
    ) {
        for (int localX = 2; localX <= 13; localX++) {
            for (int localZ = 2; localZ <= 13; localZ++) {
                int x = baseX + localX;
                int z = baseZ + localZ;
                BiomeDefinition biome = columnBiomes[localX][localZ];
                if (waterColumns[localX][localZ] || biome.treeChance() <= 0.0) {
                    continue;
                }
                long featureHash = coordinateHash(seed ^ 0x2d358dccaa6c78a5L, x, z);
                double roll = Long.remainderUnsigned(featureHash, 1_000_000L) / 1_000_000.0;
                if (roll >= biome.treeChance()) {
                    continue;
                }

                int groundY = surfaceHeights[localX][localZ];
                int treeRange = biome.maxTreeHeight() - biome.minTreeHeight() + 1;
                int trunkHeight = biome.minTreeHeight()
                        + (int) Long.remainderUnsigned(featureHash >>> 8, treeRange);
                if (groundY + trunkHeight + 2 >= height.maxYExclusive()) {
                    continue;
                }

                for (int y = 1; y <= trunkHeight; y++) {
                    blocks.put(new BlockPosition(x, groundY + y, z), biome.trunkBlock());
                }
                for (int dy = -2; dy <= 2; dy++) {
                    int radius = dy == 2 ? 1 : 2;
                    for (int dx = -radius; dx <= radius; dx++) {
                        for (int dz = -radius; dz <= radius; dz++) {
                            if (Math.abs(dx) == radius && Math.abs(dz) == radius
                                    && (featureHash & 1L) == 0L) {
                                continue;
                            }
                            BlockPosition leaf = new BlockPosition(
                                    x + dx, groundY + trunkHeight - 1 + dy, z + dz);
                            blocks.putIfAbsent(leaf, biome.foliageBlock());
                        }
                    }
                }
            }
        }
    }

    private static double valueNoise(int x, int z, long seed, int scale, long salt) {
        double sampleX = (double) x / scale;
        double sampleZ = (double) z / scale;
        int x0 = (int) Math.floor(sampleX);
        int z0 = (int) Math.floor(sampleZ);
        double tx = smooth(sampleX - x0);
        double tz = smooth(sampleZ - z0);

        double a = lattice(seed, salt, x0, z0);
        double b = lattice(seed, salt, x0 + 1, z0);
        double c = lattice(seed, salt, x0, z0 + 1);
        double d = lattice(seed, salt, x0 + 1, z0 + 1);
        return lerp(lerp(a, b, tx), lerp(c, d, tx), tz);
    }

    private static double lattice(long seed, long salt, int x, int z) {
        long value = coordinateHash(seed ^ salt, x, z);
        return ((value >>> 11) * 0x1.0p-52) - 1.0;
    }

    private static long coordinateHash(long seed, int x, int z) {
        long value = seed ^ ((long) x * 0x9e3779b97f4a7c15L)
                ^ ((long) z * 0xc2b2ae3d27d4eb4fL);
        value ^= value >>> 30;
        value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27;
        value *= 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private static double smooth(double value) {
        return value * value * (3.0 - 2.0 * value);
    }

    private static double lerp(double start, double end, double amount) {
        return start + (end - start) * amount;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
