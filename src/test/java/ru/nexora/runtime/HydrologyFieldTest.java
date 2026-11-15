package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HydrologyFieldTest {
    private final WorldHeight height = new WorldHeight(-64, 320);

    @Test
    void chunksInTheSameMacroTileShareTheSameDrainageField() {
        HydrologyField first = HydrologyField.forChunk(new ChunkPosition(0, 0), height, 741L);
        HydrologyField second = HydrologyField.forChunk(new ChunkPosition(1, 1), height, 741L);

        assertEquals(first.sample(15, 23, 68), second.sample(15, 23, 68));
        assertEquals(first.sample(16, 23, 68), second.sample(16, 23, 68));
    }

    @Test
    void drainageSamplingIsDeterministicForASeedAndWorldPosition() {
        HydrologyField first = HydrologyField.forChunk(new ChunkPosition(-1, 2), height, 991L);
        HydrologyField second = HydrologyField.forChunk(new ChunkPosition(-1, 2), height, 991L);

        for (int x = -16; x < 0; x += 3) {
            for (int z = 32; z < 48; z += 3) {
                assertEquals(first.sample(x, z, 70), second.sample(x, z, 70));
            }
        }
    }
}
