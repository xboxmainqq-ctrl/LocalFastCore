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
    void publishesOnlyRealRuntimeBlockChangesAndCanUnregisterListeners() throws Exception {
        World world = new World("NEXORA", 1L, new FlatWorldGenerator());
        java.util.List<World.BlockChange> changes = new java.util.ArrayList<>();
        AutoCloseable subscription = world.addBlockChangeListener(changes::add);

        world.setBlock(-1, 70, -17, BlockType.STONE);
        world.setBlock(-1, 70, -17, BlockType.STONE);
        world.setBlock(-1, 70, -17, BlockType.DIRT);

        assertEquals(2, changes.size());
        assertEquals(new World.BlockChange(-1, 70, -17, BlockType.AIR, BlockType.STONE), changes.get(0));
        assertEquals(new World.BlockChange(-1, 70, -17, BlockType.STONE, BlockType.DIRT), changes.get(1));

        subscription.close();
        world.setBlock(-1, 70, -17, BlockType.AIR);
        assertEquals(2, changes.size());
    }

    @Test
    void serializesMutationsAndEventsWithinOneChunk() throws Exception {
        World world = new World("NEXORA", 1L, new FlatWorldGenerator());
        java.util.List<World.BlockChange> changes = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.CountDownLatch firstListenerEntered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseFirstListener = new java.util.concurrent.CountDownLatch(1);
        world.addBlockChangeListener(change -> {
            changes.add(change);
            if (change.current() == BlockType.STONE) {
                firstListenerEntered.countDown();
                try {
                    if (!releaseFirstListener.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out waiting to release first block event");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            }
        });

        Thread first = new Thread(() -> world.setBlock(1, 70, 1, BlockType.STONE));
        Thread second = new Thread(() -> world.setBlock(1, 70, 1, BlockType.DIRT));
        first.start();
        assertTrue(firstListenerEntered.await(2, java.util.concurrent.TimeUnit.SECONDS));
        second.start();
        releaseFirstListener.countDown();
        first.join(2_000);
        second.join(2_000);

        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        assertEquals(2, changes.size());
        assertEquals(BlockType.AIR, changes.get(0).previous());
        assertEquals(BlockType.STONE, changes.get(0).current());
        assertEquals(BlockType.STONE, changes.get(1).previous());
        assertEquals(BlockType.DIRT, changes.get(1).current());
    }

    @Test
    void chunkReturnsPreviousBlockFromAtomicMutation() {
        WorldChunk chunk = new WorldChunk(0, 0);

        assertEquals(BlockType.AIR, chunk.setBlockAndGetPrevious(1, 64, 1, BlockType.STONE));
        assertEquals(BlockType.STONE, chunk.setBlockAndGetPrevious(1, 64, 1, BlockType.DIRT));
        assertEquals(BlockType.DIRT, chunk.getBlock(1, 64, 1));
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
