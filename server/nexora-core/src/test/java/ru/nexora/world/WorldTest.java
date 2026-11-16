package ru.nexora.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorldTest {
    @Test
    void flatGeneratorCreatesAStableSpawnPlatform() {
        World world = new World("NEXORA World", 12345L, new FlatWorldGenerator());

        assertEquals(BlockType.BEDROCK, world.getBlock(0, 60, 0));
        assertEquals(BlockType.STONE, world.getBlock(0, 61, 0));
        assertEquals(BlockType.DIRT, world.getBlock(0, 62, 0));
        assertEquals(BlockType.DIRT, world.getBlock(0, 63, 0));
        assertEquals(BlockType.GRASS_BLOCK, world.getBlock(0, 64, 0));
        assertEquals(BlockType.AIR, world.getBlock(0, 65, 0));
        assertEquals(1, world.loadedChunkCount());
    }

    @Test
    void resolvesNegativeBlockCoordinatesUsingFloorDivision() {
        World world = new World("NEXORA", 1L, new FlatWorldGenerator());

        assertEquals(BlockType.GRASS_BLOCK, world.getBlock(-1, 64, -17));
        assertEquals(-1, world.getChunk(-1, -2).chunkX());
        assertEquals(-2, world.getChunk(-1, -2).chunkZ());
        assertEquals(BlockType.GRASS_BLOCK, world.getBlock(16, 64, 16));
        assertEquals(2, world.loadedChunkCount());
    }

    @Test
    void allocatesOnlySectionsContainingNonAirBlocks() {
        WorldChunk chunk = new WorldChunk(0, 0);

        assertEquals(0, chunk.highestNonEmptySectionCount());
        assertNull(chunk.section(0));

        chunk.setBlock(1, -64, 1, BlockType.STONE);
        assertNotNull(chunk.section(0));
        assertEquals(1, chunk.highestNonEmptySectionCount());

        chunk.setBlock(1, -64, 1, BlockType.AIR);
        assertNull(chunk.section(0));
        assertEquals(0, chunk.highestNonEmptySectionCount());
    }

    @Test
    void chunkSnapshotsAreDetachedFromSubsequentMutations() {
        WorldChunk chunk = new WorldChunk(0, 0);
        chunk.setBlock(1, 64, 1, BlockType.STONE);

        byte[][] snapshot = chunk.copySectionsRaw();
        ChunkSection detached = chunk.section(8);
        detached.set(1, 0, 1, BlockType.DIRT);
        chunk.setBlock(1, 64, 1, BlockType.GRASS_BLOCK);

        assertEquals(BlockType.STONE, BlockType.byInternalId(Byte.toUnsignedInt(snapshot[8][(1 << 8) | (1 << 4)])));
        assertEquals(BlockType.GRASS_BLOCK, chunk.getBlock(1, 64, 1));
        assertEquals(BlockType.GRASS_BLOCK,
                BlockType.byInternalId(Byte.toUnsignedInt(chunk.copySectionRaw(8)[(1 << 8) | (1 << 4)])));
    }

    @Test
    void rejectsCoordinatesOutsideChunkAndOverworldBounds() {
        WorldChunk chunk = new WorldChunk(0, 0);

        assertThrows(IndexOutOfBoundsException.class, () -> chunk.getBlock(16, 64, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> chunk.getBlock(0, -65, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> chunk.setBlock(0, 320, 0, BlockType.STONE));
    }
}
