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
                columnBiomes[localX][localZ] = biome;
                surfaceHeights[localX][localZ] = surfaceY;
                BlockPosition surfacePosition = new BlockPosition(x, surfaceY, z);
                biomeColumns.put(new BiomeColumnPosition(x, z), biome.id());

                blocks.put(new BlockPosition(x, height.minY(), z), BEDROCK);
                for (int y = height.minY() + 1; y < surfaceY - 3; y++) {
                    blocks.put(new BlockPosition(x, y, z), STONE);
                }
                for (int y = Math.max(height.minY() + 1, surfaceY - 3); y < surfaceY; y++) {
                    blocks.put(new BlockPosition(x, y, z), biome.subsurfaceBlock());
                }
                blocks.put(surfacePosition, biome.surfaceBlock());

                for (int y = surfaceY + 1; y <= seaLevel; y++) {
                    fluids.put(new BlockPosition(x, y, z), WATER);
                }
            }
        }

        placeTrees(position, baseX, baseZ, height, seed, surfaceHeights, columnBiomes, blocks);
        return new GeneratedChunk(position, blocks, Map.of(), fluids, biomeColumns, height);
    }

    private static int terrainHeight(int x, int z, long seed, WorldHeight worldHeight) {
        return TerrainHeightSampler.sample(x, z, seed, worldHeight, 1.0);
    }

    private static int terrainHeight(
            int x, int z, long seed, WorldHeight worldHeight, double reliefMultiplier) {
        return TerrainHeightSampler.sample(x, z, seed, worldHeight, reliefMultiplier);
    }

    private static void placeTrees(
            ChunkPosition chunk,
            int baseX,
            int baseZ,
            WorldHeight height,
            long seed,
            int[][] surfaceHeights,
            BiomeDefinition[][] columnBiomes,
            Map<BlockPosition, BlockState> blocks
    ) {
        for (int localX = 2; localX <= 13; localX++) {
            for (int localZ = 2; localZ <= 13; localZ++) {
                int x = baseX + localX;
                int z = baseZ + localZ;
                BiomeDefinition biome = columnBiomes[localX][localZ];
                if (biome.treeChance() <= 0.0) {
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
