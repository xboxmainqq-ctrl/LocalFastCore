package ru.nexora.runtime;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Seeded, tile-based drainage field. A fixed world-aligned tile plus a wide
 * sampling halo makes every chunk in that tile use the same watershed graph.
 * Flow accumulation is propagated downhill on a coarse grid; only the final
 * river/lake surface is refined to block coordinates.
 */
final class HydrologyField {
    private static final int GRID_STEP = 8;
    private static final int TILE_BLOCKS = 2_048;
    private static final int CORE_CELLS = TILE_BLOCKS / GRID_STEP;
    private static final int HALO_CELLS = 64;
    private static final int SIZE = CORE_CELLS + HALO_CELLS * 2;
    private static final int RIVER_THRESHOLD = 48;
    private static final int LAKE_THRESHOLD = 128;
    private static final int MAX_ACCUMULATION = 1_000_000;

    private static final Map<Key, Tile> CACHE = new LinkedHashMap<>(4, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Tile> eldest) {
            return size() > 2;
        }
    };

    private final Tile tile;

    private HydrologyField(Tile tile) {
        this.tile = tile;
    }

    static HydrologyField forChunk(ChunkPosition chunk, WorldHeight height, long seed) {
        int blockX = Math.multiplyExact(chunk.x(), 16);
        int blockZ = Math.multiplyExact(chunk.z(), 16);
        int tileX = Math.floorDiv(blockX, TILE_BLOCKS);
        int tileZ = Math.floorDiv(blockZ, TILE_BLOCKS);
        Key key = new Key(seed, tileX, tileZ, height);
        synchronized (CACHE) {
            Tile tile = CACHE.get(key);
            if (tile == null) {
                tile = build(key);
                CACHE.put(key, tile);
            }
            return new HydrologyField(tile);
        }
    }

    Sample sample(int x, int z, int terrainY) {
        int gx = Math.floorDiv(x - tile.originX, GRID_STEP);
        int gz = Math.floorDiv(z - tile.originZ, GRID_STEP);
        if (gx < 0 || gz < 0 || gx >= SIZE || gz >= SIZE) {
            return Sample.NONE;
        }
        int index = gz * SIZE + gx;
        int centerX = tile.originX + gx * GRID_STEP + GRID_STEP / 2;
        int centerZ = tile.originZ + gz * GRID_STEP + GRID_STEP / 2;
        double dx = x - centerX;
        double dz = z - centerZ;
        double distance = Math.sqrt(dx * dx + dz * dz);

        int accumulation = tile.accumulation[index];
        if (tile.flowTo[index] >= 0 && accumulation >= RIVER_THRESHOLD) {
            double width = 5.0 + Math.min(5.0,
                    Math.log((double) accumulation / RIVER_THRESHOLD) / Math.log(2.0) * 1.35);
            if (distance <= width) {
                int depth = 2 + Math.min(3, accumulation / 128);
                return new Sample(Kind.RIVER, depth, terrainY - depth + 2);
            }
        }

        int lakeSurface = tile.lakeSurface[index];
        if (lakeSurface > terrainY + 1) {
            return new Sample(Kind.LAKE, 0, lakeSurface);
        }
        return Sample.NONE;
    }

    private static Tile build(Key key) {
        int tileOriginX = Math.multiplyExact(key.tileX, TILE_BLOCKS);
        int tileOriginZ = Math.multiplyExact(key.tileZ, TILE_BLOCKS);
        int originX = tileOriginX - HALO_CELLS * GRID_STEP;
        int originZ = tileOriginZ - HALO_CELLS * GRID_STEP;
        int count = SIZE * SIZE;
        int[] heights = new int[count];
        int[] flowTo = new int[count];
        int[] accumulation = new int[count];
        int[] lakeSurface = new int[count];
        Arrays.fill(flowTo, -1);
        Arrays.fill(lakeSurface, Integer.MIN_VALUE);
        BiomeRegistry biomes = BiomeRegistry.builtIn();

        for (int gz = 0; gz < SIZE; gz++) {
            for (int gx = 0; gx < SIZE; gx++) {
                int x = originX + gx * GRID_STEP + GRID_STEP / 2;
                int z = originZ + gz * GRID_STEP + GRID_STEP / 2;
                int baseHeight = TerrainHeightSampler.sample(x, z, key.seed, key.height, 1.0);
                ClimateSample climate = BiomeClimateSampler.sample(x, z, key.seed, baseHeight);
                double relief = biomes.blendedReliefMultiplier(climate);
                heights[gz * SIZE + gx] =
                        TerrainHeightSampler.sample(x, z, key.seed, key.height, relief);
            }
        }

        for (int z = 1; z < SIZE - 1; z++) {
            for (int x = 1; x < SIZE - 1; x++) {
                int index = z * SIZE + x;
                int current = heights[index];
                double bestSlope = 0.0;
                int best = -1;
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dz == 0) continue;
                        int neighbor = (z + dz) * SIZE + x + dx;
                        int drop = current - heights[neighbor];
                        if (drop <= 0) continue;
                        double slope = drop / ((dx != 0 && dz != 0) ? 1.41421356237 : 1.0);
                        if (slope > bestSlope) {
                            bestSlope = slope;
                            best = neighbor;
                        }
                    }
                }
                flowTo[index] = best;
                accumulation[index] = 1;
            }
        }

        int minY = key.height.minY();
        int[] heads = new int[key.height.height()];
        int[] next = new int[count];
        Arrays.fill(heads, -1);
        for (int i = 0; i < count; i++) {
            int bucket = heights[i] - minY;
            bucket = Math.max(0, Math.min(heads.length - 1, bucket));
            next[i] = heads[bucket];
            heads[bucket] = i;
        }
        for (int bucket = heads.length - 1; bucket >= 0; bucket--) {
            for (int index = heads[bucket]; index >= 0; index = next[index]) {
                int downstream = flowTo[index];
                if (downstream >= 0) {
                    accumulation[downstream] = Math.min(MAX_ACCUMULATION,
                            accumulation[downstream] + accumulation[index]);
                }
            }
        }

        // Closed, well-fed depressions become small lakes. Ignore the outer
        // halo edge so a clipped tile boundary cannot create artificial ponds.
        for (int z = HALO_CELLS + 2; z < SIZE - HALO_CELLS - 2; z++) {
            for (int x = HALO_CELLS + 2; x < SIZE - HALO_CELLS - 2; x++) {
                int index = z * SIZE + x;
                if (flowTo[index] >= 0 || accumulation[index] < LAKE_THRESHOLD) continue;
                int spill = Integer.MAX_VALUE;
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dz == 0) continue;
                        spill = Math.min(spill, heights[(z + dz) * SIZE + x + dx]);
                    }
                }
                int depth = spill - heights[index];
                if (depth < 2 || depth > 12) continue;
                int radius = 1 + Math.min(3, accumulation[index] / 256);
                int waterY = spill - 1;
                for (int dz = -radius; dz <= radius; dz++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        if (dx * dx + dz * dz > radius * radius) continue;
                        int target = (z + dz) * SIZE + x + dx;
                        if (heights[target] < waterY && waterY - heights[target] <= 12) {
                            lakeSurface[target] = Math.max(lakeSurface[target], waterY);
                        }
                    }
                }
            }
        }
        return new Tile(originX, originZ, heights, flowTo, accumulation, lakeSurface);
    }

    enum Kind { NONE, RIVER, LAKE }

    record Sample(Kind kind, int depth, int waterSurfaceY) {
        static final Sample NONE = new Sample(Kind.NONE, 0, Integer.MIN_VALUE);
        boolean isWater() { return kind != Kind.NONE; }
    }

    private record Key(long seed, int tileX, int tileZ, WorldHeight height) { }

    private record Tile(
            int originX,
            int originZ,
            int[] heights,
            int[] flowTo,
            int[] accumulation,
            int[] lakeSurface
    ) { }
}
