package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BiomeWorldStorageTest {
    @Test
    void defaultsToPlainsWithoutAllocatingChunks() {
        InMemoryWorld world = new InMemoryWorld("overworld", new WorldHeight(-64, 320));

        assertEquals(1, world.biomeAt(new BlockPosition(0, 64, 0)));
        assertEquals(0, world.loadedChunkCount());
    }

    @Test
    void storesBiomeAtBlockResolutionAcrossNegativeChunkAndSectionCoordinates() {
        InMemoryWorld world = new InMemoryWorld("overworld", new WorldHeight(-64, 320));
        BlockPosition position = new BlockPosition(-1, -17, -16);

        world.setBiome(position, 42);

        assertEquals(42, world.biomeAt(position));
        assertEquals(1, world.loadedChunkCount());
        int[] palette = world.biomesInSection(new ChunkPosition(-1, -1), -2);
        assertEquals(42, palette[(15 * 16 + 0) * 16 + 15]);
        assertEquals(1, palette[(15 * 16 + 0) * 16 + 14]);
    }

    @Test
    void biomeSnapshotsAreDetachedAndReturnToUniformDefault() {
        InMemoryWorld world = new InMemoryWorld("overworld", new WorldHeight(0, 32));
        ChunkPosition chunk = new ChunkPosition(0, 0);
        world.setBiome(new BlockPosition(2, 3, 4), 7);

        int[] snapshot = world.biomesInSection(chunk, 0);
        snapshot[0] = 999;
        assertEquals(7, world.biomeAt(new BlockPosition(2, 3, 4)));
        assertEquals(1, world.biomeAt(new BlockPosition(0, 0, 0)));

        world.setBiome(new BlockPosition(2, 3, 4), BiomeSection.DEFAULT_BIOME_ID);
        assertEquals(0, world.loadedChunkCount());
    }

    @Test
    void rejectsNegativeBiomeIdsAndOutOfWorldCoordinates() {
        InMemoryWorld world = new InMemoryWorld("overworld", new WorldHeight(0, 16));
        assertThrows(IllegalArgumentException.class,
                () -> world.setBiome(new BlockPosition(0, 0, 0), -1));
        assertThrows(IllegalArgumentException.class,
                () -> world.biomeAt(new BlockPosition(0, 16, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> world.biomesInSection(new ChunkPosition(0, 0), 1));
    }
}
