package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChunkSectionTest {
    @Test
    void storesAndReadsBlocksAcrossNibbleByteAndShortPaletteWidths() {
        ChunkSection section = new ChunkSection();
        BlockState[] states = new BlockState[300];

        for (int i = 0; i < states.length; i++) {
            states[i] = new BlockState("test:block_" + i);
            section.set(i & 15, (i >>> 8) & 15, (i >>> 4) & 15, states[i]);
        }

        for (int i = 0; i < states.length; i++) {
            assertEquals(states[i],
                    section.get(i & 15, (i >>> 8) & 15, (i >>> 4) & 15),
                    "block index " + i + " must survive palette widening");
        }
        assertFalse(section.isEmpty());
    }

    @Test
    void clearingBlocksRestoresAirAndTracksEmptySections() {
        ChunkSection section = new ChunkSection();
        BlockState stone = new BlockState("minecraft:stone");

        section.set(0, 0, 0, stone);
        assertEquals(stone, section.get(0, 0, 0));
        assertFalse(section.isEmpty());

        section.set(0, 0, 0, BlockState.AIR);
        assertEquals(BlockState.AIR, section.get(0, 0, 0));
        assertTrue(section.isEmpty());
    }

    @Test
    void supportsAllLocalCoordinatesAndRejectsOutOfRangeCoordinates() {
        ChunkSection section = new ChunkSection();
        BlockState marker = new BlockState("test:corner");

        section.set(15, 15, 15, marker);

        assertEquals(marker, section.get(15, 15, 15));
        assertThrows(IndexOutOfBoundsException.class, () -> section.get(-1, 0, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> section.get(0, 16, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> section.set(0, 0, 16, marker));
    }
}
