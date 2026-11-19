package ru.nexora.world;

import java.util.List;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class WorldTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void appliesChunkResidencyLoadsAcrossTicksWithOneGlobalBudget() {
        World world = new World("NEXORA", 1L, new FlatWorldGenerator());
        WorldChunkResidencyManager residency = new WorldChunkResidencyManager(2, 20);
        var player = List.of(new WorldChunkResidencyManager.PlayerChunk(0, 0));

        var initial = residency.update(player, 0);
        var firstTick = world.applyChunkResidencyPlan(initial, 3);
        assertEquals(13, firstTick.loadRequested());
        assertEquals(3, firstTick.loaded());
        assertEquals(3, world.loadedChunkCount());
        assertEquals(10, world.pendingChunkLoadCount());

        var nextTick = residency.update(player, 1);
        assertTrue(nextTick.load().isEmpty(), "the residency manager must not re-request resident chunks");
        var secondTick = world.applyChunkResidencyPlan(nextTick, 3);
        assertEquals(10, secondTick.loadRequested());
        assertEquals(3, secondTick.loaded());
        assertEquals(6, world.loadedChunkCount());
        assertEquals(7, world.pendingChunkLoadCount());
    }

    @Test
    void unloadPlanCancelsChunkLoadsThatHaveNotStarted() {
        World world = new World("NEXORA", 1L, new FlatWorldGenerator());
        WorldChunkResidencyManager residency = new WorldChunkResidencyManager(1, 0);
        var initial = residency.update(
                List.of(new WorldChunkResidencyManager.PlayerChunk(0, 0)), 0);
        world.applyChunkResidencyPlan(initial, 1);
        assertEquals(4, world.pendingChunkLoadCount());

        var departed = residency.update(List.of(), 1);
        world.applyChunkResidencyPlan(departed, 1);

        assertEquals(0, world.pendingChunkLoadCount(),
                "chunks no longer in the residency plan must not remain queued");
        assertEquals(1, world.loadedChunkCount(),
                "the no-storage world keeps already generated chunks rather than discarding data");
    }

    @Test
    void failedChunkGenerationKeepsTheDeferredLoadForRetry() {
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        World world = new World("NEXORA", 1L, (chunk, seed) -> {
            if (attempts.getAndIncrement() == 0) throw new IllegalStateException("temporary generation failure");
            new FlatWorldGenerator().generate(chunk, seed);
        });
        WorldChunkResidencyManager residency = new WorldChunkResidencyManager(1, 20);
        var player = List.of(new WorldChunkResidencyManager.PlayerChunk(0, 0));
        var plan = residency.update(player, 0);

        assertThrows(IllegalStateException.class, () -> world.applyChunkResidencyPlan(plan, 1));
        assertEquals(5, world.pendingChunkLoadCount());
        assertEquals(0, world.loadedChunkCount());

        var retryPlan = residency.update(player, 1);
        assertTrue(retryPlan.load().isEmpty());
        var retry = world.applyChunkResidencyPlan(retryPlan, 1);
        assertEquals(5, retry.loadRequested());
        assertEquals(1, retry.loaded());
        assertEquals(4, world.pendingChunkLoadCount());
        assertEquals(1, world.loadedChunkCount());
    }


    @Test
    void persistedWorldCacheStaysWithinResidencyTargetAcrossPlayerMovement() throws Exception {
        WorldStorage storage = new WorldStorage(temporaryDirectory, "NEXORA");
        World world = new World("NEXORA", 1L, new FlatWorldGenerator(), 0, 65, 0, storage);
        WorldChunkResidencyManager residency = new WorldChunkResidencyManager(
                1, 0, WorldChunkResidencyManager.Shape.SQUARE, 4);
        try {
            var firstPlan = residency.update(
                    List.of(new WorldChunkResidencyManager.PlayerChunk(0, 0)), 0);
            var firstApply = world.applyChunkResidencyPlan(firstPlan, 4);
            assertEquals(4, firstApply.loaded());
            assertEquals(4, world.loadedChunkCount());
            assertEquals(4, residency.residentChunkCount());

            var movedPlan = residency.update(
                    List.of(new WorldChunkResidencyManager.PlayerChunk(100, 100)), 1);
            var movedApply = world.applyChunkResidencyPlan(movedPlan, 4);
            assertEquals(4, movedApply.unloaded());
            assertEquals(4, movedApply.loaded());
            assertEquals(4, world.loadedChunkCount());
            assertEquals(4, residency.residentChunkCount());

            for (WorldChunkResidencyManager.ChunkCoordinate coordinate : movedPlan.load()) {
                assertTrue(world.isChunkLoadedAt(coordinate.chunkX() * 16, coordinate.chunkZ() * 16));
            }
        } finally {
            world.closeChunkLoadService();
        }
    }

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
