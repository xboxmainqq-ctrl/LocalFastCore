package ru.nexora.world;

/**
 * Deterministic flat generator for tests and explicitly configured flat worlds.
 * Production NEXORA worlds use NexoraTerrainGenerator instead.
 */
public final class FlatWorldGenerator implements WorldGenerator {
    public static final int SURFACE_Y = 64;

    @Override
    public void generate(WorldChunk chunk, long seed) {
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                chunk.setBlock(localX, WorldChunk.MIN_Y, localZ, BlockType.BEDROCK);
                for (int y = WorldChunk.MIN_Y + 1; y < SURFACE_Y - 3; y++) {
                    chunk.setBlock(localX, y, localZ, BlockType.STONE);
                }
                for (int y = SURFACE_Y - 3; y < SURFACE_Y; y++) {
                    chunk.setBlock(localX, y, localZ, BlockType.DIRT);
                }
                chunk.setBlock(localX, SURFACE_Y, localZ, BlockType.GRASS_BLOCK);
            }
        }
    }

    /** Flat-world surface query retained for tests and callers that need a stable fixture. */
    public static int surfaceHeight(int worldX, int worldZ, long seed) {
        return SURFACE_Y;
    }
}
