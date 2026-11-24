package ru.nexora.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FlatWorldGeneratorTest {
    @Test
    void keepsExistingSpawnAreaLevel() {
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                assertEquals(64, FlatWorldGenerator.surfaceHeight(x, z, 0L));
            }
        }
    }

    @Test
    void remainsFlatAndDeterministicOutsideSpawnArea() {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int x = -160; x <= 160; x += 4) {
            for (int z = -160; z <= 160; z += 4) {
                int height = FlatWorldGenerator.surfaceHeight(x, z, 0L);
                assertEquals(height, FlatWorldGenerator.surfaceHeight(x, z, 0L));
                assertEquals(64, height);
                min = Math.min(min, height);
                max = Math.max(max, height);
            }
        }
        assertEquals(0, max - min, "the explicit flat-world generator must stay flat");
    }

    @Test
    void generatesGrassDirtAndStoneLayers() {
        WorldChunk chunk = new WorldChunk(2, -3);
        FlatWorldGenerator generator = new FlatWorldGenerator();
        generator.generate(chunk, 0L);

        int x = 4;
        int z = 7;
        int worldX = chunk.chunkX() * 16 + x;
        int worldZ = chunk.chunkZ() * 16 + z;
        int surface = FlatWorldGenerator.surfaceHeight(worldX, worldZ, 0L);
        assertEquals(BlockType.GRASS_BLOCK, chunk.getBlock(x, surface, z));
        assertEquals(BlockType.DIRT, chunk.getBlock(x, surface - 1, z));
        assertEquals(BlockType.DIRT, chunk.getBlock(x, surface - 2, z));
        assertEquals(BlockType.DIRT, chunk.getBlock(x, surface - 3, z));
        assertEquals(BlockType.STONE, chunk.getBlock(x, surface - 4, z));
        assertEquals(BlockType.BEDROCK, chunk.getBlock(x, WorldChunk.MIN_Y, z));
    }

    @Test
    void recognizesOnlyUntouchedLegacyStarterPlatformsForSafeMigration() {
        WorldChunk legacy = new WorldChunk(0, 0);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                legacy.setBlock(x, 60, z, BlockType.BEDROCK);
                legacy.setBlock(x, 61, z, BlockType.STONE);
                legacy.setBlock(x, 62, z, BlockType.DIRT);
                legacy.setBlock(x, 63, z, BlockType.DIRT);
                legacy.setBlock(x, 64, z, BlockType.GRASS_BLOCK);
            }
        }
        assertTrue(legacy.isLegacyStarterPlatform());

        legacy.setBlock(1, 65, 1, BlockType.COBBLESTONE);
        assertFalse(legacy.isLegacyStarterPlatform(),
                "player-modified chunks must never be automatically regenerated");
    }
}
