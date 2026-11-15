package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TerrainHeightSamplerTest {
    private final WorldHeight world = new WorldHeight(-64, 320);

    @Test
    void terrainIsDeterministicAndSeedDependent() {
        int first = TerrainHeightSampler.sample(-1_237, 8_421, 55L, world, 1.0);
        assertEquals(first, TerrainHeightSampler.sample(-1_237, 8_421, 55L, world, 1.0));
        assertNotEquals(first, TerrainHeightSampler.sample(-1_237, 8_421, 56L, world, 1.0));
    }

    @Test
    void landformsStayInsideWorldHeight() {
        int minimum = Integer.MAX_VALUE;
        int maximum = Integer.MIN_VALUE;
        for (int x = -2_048; x <= 2_048; x += 32) {
            for (int z = -2_048; z <= 2_048; z += 32) {
                int height = TerrainHeightSampler.sample(x, z, 991L, world, 1.4);
                minimum = Math.min(minimum, height);
                maximum = Math.max(maximum, height);
                assertTrue(world.contains(height));
            }
        }
        assertTrue(maximum - minimum > 20, "The terrain field should contain varied landforms");
    }

    @Test
    void adjacentWorldSamplesDoNotCreateExtremeSingleBlockCliffs() {
        for (int z = -1_024; z <= 1_024; z += 31) {
            int west = TerrainHeightSampler.sample(15, z, 123L, world, 1.0);
            int east = TerrainHeightSampler.sample(16, z, 123L, world, 1.0);
            assertTrue(Math.abs(west - east) < 12,
                    "Adjacent samples across a chunk boundary should remain continuous");
        }
    }

    @Test
    void rejectsInvalidReliefAndTinyWorld() {
        assertThrows(IllegalArgumentException.class,
                () -> TerrainHeightSampler.sample(0, 0, 1L, new WorldHeight(0, 7), 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> TerrainHeightSampler.sample(0, 0, 1L, world, Double.NaN));
    }
}
