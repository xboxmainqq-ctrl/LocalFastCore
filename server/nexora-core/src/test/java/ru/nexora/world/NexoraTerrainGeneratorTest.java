package ru.nexora.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NexoraTerrainGeneratorTest {
    @Test
    void preservesSafeSpawnAndBlendsIntoSurroundingTerrain() {
        for (int x = -9; x <= 9; x++) {
            for (int z = -9; z <= 9; z++) {
                assertEquals(64, NexoraTerrainGenerator.surfaceHeight(x, z, 0L));
            }
        }
        assertTrue(Math.abs(NexoraTerrainGenerator.surfaceHeight(10, 0, 0L) - 64) <= 2);
    }

    @Test
    void createsDeterministicMountainsAndDeepCraterBasins() {
        int minimum = Integer.MAX_VALUE;
        int maximum = Integer.MIN_VALUE;
        int plains = 0;
        int mountainColumns = 0;
        int samples = 0;
        for (int x = -2048; x <= 2048; x += 16) {
            for (int z = -2048; z <= 2048; z += 16) {
                int height = NexoraTerrainGenerator.surfaceHeight(x, z, 0L);
                assertEquals(height, NexoraTerrainGenerator.surfaceHeight(x, z, 0L));
                assertTrue(height >= 12 && height <= 200);
                if (height <= 100) plains++;
                if (height >= 135) mountainColumns++;
                samples++;
                minimum = Math.min(minimum, height);
                maximum = Math.max(maximum, height);
            }
        }
        assertTrue(minimum >= 12,
                "ordinary terrain and rare basins must remain above the deep underground");
        assertTrue(maximum >= 190,
                "rare large mountain regions should reach close to Y=200");
        assertTrue(plains > samples / 2,
                "most of the sampled world should be plains and gentle hills");
        assertTrue(mountainColumns < samples / 4,
                "high mountain terrain must be uncommon rather than everywhere");
    }

    @Test
    void neighboringChunksUseIdenticalBorderHeightsAndNaturalLayers() {
        NexoraTerrainGenerator generator = new NexoraTerrainGenerator();
        WorldChunk left = new WorldChunk(0, 0);
        WorldChunk right = new WorldChunk(1, 0);
        generator.generate(left, 0L);
        generator.generate(right, 0L);

        for (int z = 0; z < 16; z++) {
            int leftHeight = NexoraTerrainGenerator.surfaceHeight(15, z, 0L);
            int rightHeight = NexoraTerrainGenerator.surfaceHeight(16, z, 0L);
            assertEquals(leftHeight, left.getBlock(15, leftHeight, z) == BlockType.GRASS_BLOCK
                    || left.getBlock(15, leftHeight, z) == BlockType.STONE
                    || left.getBlock(15, leftHeight, z) == BlockType.GRAVEL
                    || left.getBlock(15, leftHeight, z) == BlockType.OBSIDIAN
                    ? leftHeight : -1);
            assertEquals(rightHeight, NexoraTerrainGenerator.surfaceHeight(16, z, 0L));
        }

        int surface = NexoraTerrainGenerator.surfaceHeight(4, 4, 0L);
        assertEquals(BlockType.GRASS_BLOCK, left.getBlock(4, surface, 4));
        assertEquals(BlockType.DIRT, left.getBlock(4, surface - 1, 4));
        assertEquals(BlockType.BEDROCK, left.getBlock(4, WorldChunk.MIN_Y, 4));
    }
}
