package ru.nexora.world;

/** Small deterministic starter platform generator used for protocol bring-up. */
public final class FlatWorldGenerator implements WorldGenerator {
    public static final int SURFACE_Y = 64;

    @Override
    public void generate(WorldChunk chunk, long seed) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                chunk.setBlock(x, SURFACE_Y - 4, z, BlockType.BEDROCK);
                chunk.setBlock(x, SURFACE_Y - 3, z, BlockType.STONE);
                chunk.setBlock(x, SURFACE_Y - 2, z, BlockType.DIRT);
                chunk.setBlock(x, SURFACE_Y - 1, z, BlockType.DIRT);
                chunk.setBlock(x, SURFACE_Y, z, BlockType.GRASS_BLOCK);
            }
        }
    }
}
