package ru.nexora.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorldChunkPersistenceSnapshotTest {
    @Test
    void snapshotDetachesInputArraysAndReturnsDefensiveCopies() {
        WorldChunk source = new WorldChunk(-4, 9);
        source.setBlock(3, 70, 5, BlockType.DIAMOND_BLOCK);
        byte[][] sourceSections = source.copySectionsRaw();
        WorldChunk.PersistenceSnapshot snapshot =
                new WorldChunk.PersistenceSnapshot(source.revision(), sourceSections);
        byte[][] expected = snapshot.sections();

        // Neither the caller's original image nor an accessor result may mutate the snapshot.
        sourceSections[6][0] ^= 0x7f;
        byte[][] exposed = snapshot.sections();
        exposed[6][0] ^= 0x7f;

        assertArrayEquals(expected[6], snapshot.sections()[6]);
        WorldChunk restored = new WorldChunk(-4, 9);
        restored.loadSectionRaw(6, snapshot.sections()[6]);
        assertEquals(BlockType.DIAMOND_BLOCK, restored.getBlock(3, 70, 5));
    }

    @Test
    void rejectsMalformedSectionImagesAtSnapshotBoundary() {
        byte[][] sections = new byte[WorldChunk.SECTION_COUNT][];
        sections[0] = new byte[ChunkSection.VOLUME - 1];

        assertThrows(IllegalArgumentException.class,
                () -> new WorldChunk.PersistenceSnapshot(0, sections));
    }
}
