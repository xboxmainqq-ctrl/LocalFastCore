package ru.nexora.world;

import java.util.List;
import java.nio.file.Path;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class WorldTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void saveAllContinuesPersistingIndependentWorldStoresAfterChunkFailure() throws Exception {
        WorldStorage storage = new WorldStorage(temporaryDirectory, "NEXORA");
        WorldItemEntityStorage itemStorage = new WorldItemEntityStorage(temporaryDirectory, "NEXORA");
        WorldLivingEntityStorage livingStorage = new WorldLivingEntityStorage(temporaryDirectory, "NEXORA");
        World world = new World("NEXORA", 1L, (chunk, seed) -> { },
                0, 65, 0, storage, itemStorage, null, null, livingStorage);
        Path blockedChunk = storage.worldDirectory().resolve("chunks").resolve("0_0.nxc");
        try {
            world.setBlock(1, 70, 1, BlockType.STONE);
            Files.createDirectory(blockedChunk);

            java.io.IOException failure = assertThrows(java.io.IOException.class, world::saveAll);

            assertTrue(failure.getMessage().contains("chunk"),
                    "the original chunk persistence failure must remain visible");
            assertTrue(Files.isRegularFile(temporaryDirectory.resolve("NEXORA/items/entities.nxe")),
                    "dropped-item persistence must still run after a chunk write fails");
            assertTrue(Files.isRegularFile(temporaryDirectory.resolve("NEXORA/entities/living.nxe")),
                    "living-entity persistence must still run after a chunk write fails");
            assertTrue(world.getChunk(0, 0).isDirty(),
                    "a failed chunk write must remain dirty for a later retry");
        } finally {
            world.closeChunkLoadService();
        }
    }

    @Test
    void loadedChunkCoordinateSnapshotIncludesDirectApiLoads() {
        World world = new World("NEXORA", 1L, (chunk, seed) -> { });
        world.getChunk(12, -9);

        var coordinates = world.loadedChunkCoordinates();
        assertEquals(1, coordinates.size());
        assertTrue(coordinates.contains(new WorldChunkResidencyManager.ChunkCoordinate(12, -9)));
        assertThrows(UnsupportedOperationException.class,
                () -> coordinates.add(new WorldChunkResidencyManager.ChunkCoordinate(0, 0)));
    }

    @Test
    void loadedChunkLookupNeverGeneratesOrLoadsMissingChunks() {
        java.util.concurrent.atomic.AtomicInteger generated = new java.util.concurrent.atomic.AtomicInteger();
        World world = new World("NEXORA", 1L, (chunk, seed) -> generated.incrementAndGet());

        assertNull(world.getLoadedChunkOrNull(7, -4));
        assertFalse(world.isChunkLoadedAt(7 * 16, -4 * 16));
        assertEquals(0, generated.get());
        assertEquals(0, world.loadedChunkCount());

        WorldChunk loaded = world.getChunk(7, -4);
        assertSame(loaded, world.getLoadedChunkOrNull(7, -4));
        assertEquals(1, generated.get());
    }

    @Test
    void reportsExactAllocatedBlockSectionBytesWithoutCountingEmptySections() {
        World world = new World("NEXORA", 1L, (chunk, seed) -> { });

        world.setBlock(1, 65, 1, BlockType.STONE);
        assertEquals(1, world.loadedChunkCount());
        assertEquals(1L, world.loadedChunkSectionCount());
        assertEquals(4096L, world.loadedChunkBlockStorageBytes());

        world.setBlock(1, 80, 1, BlockType.STONE);
        assertEquals(2L, world.loadedChunkSectionCount());
        assertEquals(8192L, world.loadedChunkBlockStorageBytes());

        world.setBlock(1, 65, 1, BlockType.AIR);
        assertEquals(1L, world.loadedChunkSectionCount());
        assertEquals(4096L, world.loadedChunkBlockStorageBytes());
    }

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
    void failedChunkPersistenceKeepsDirtyChunkResidentAndCanRetry() throws Exception {
        WorldStorage storage = new WorldStorage(temporaryDirectory, "NEXORA");
        Path blockedTarget = storage.worldDirectory().resolve("chunks").resolve("0_0.nxc");
        World world = new World("NEXORA", 1L, (chunk, seed) -> { }, 0, 65, 0, storage);
        try {
            world.setBlock(1, 65, 1, BlockType.STONE);
            Files.createDirectory(blockedTarget);

            assertThrows(java.io.UncheckedIOException.class, () -> world.unloadChunk(0, 0));
            assertEquals(1, world.loadedChunkCount(),
                    "a failed durable write must never evict the authoritative chunk");
            assertTrue(world.getChunk(0, 0).isDirty(),
                    "a failed write must leave the chunk marked dirty for retry");

            Files.delete(blockedTarget);
            assertTrue(world.unloadChunk(0, 0));
            assertEquals(0, world.loadedChunkCount());

            WorldChunk restored = storage.loadChunk(0, 0).orElseThrow();
            assertEquals(BlockType.STONE, restored.getBlock(1, 65, 1));
            assertFalse(restored.isDirty());
        } finally {
            world.closeChunkLoadService();
        }
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
    void repeatedLongDistanceMovementPersistsAndRestoresDirtyChunksWithinCapacity() throws Exception {
        WorldStorage storage = new WorldStorage(temporaryDirectory, "NEXORA");
        World world = new World("NEXORA", 1L, (chunk, seed) -> { }, 0, 65, 0, storage);
        WorldChunkResidencyManager residency = new WorldChunkResidencyManager(
                1, 100, WorldChunkResidencyManager.Shape.SQUARE, 9);
        try {
            var initial = residency.update(
                    List.of(new WorldChunkResidencyManager.PlayerChunk(0, 0)), 0);
            assertEquals(9, world.applyChunkResidencyPlan(initial, 9).loaded());
            world.setBlock(1, 65, 1, BlockType.DIAMOND_BLOCK);

            for (int step = 1; step <= 40; step++) {
                var plan = residency.update(
                        List.of(new WorldChunkResidencyManager.PlayerChunk(step, 0)), step);
                var applied = world.applyChunkResidencyPlan(plan, 9);
                assertEquals(9, applied.unloaded() + world.loadedChunkCount() - applied.loaded(),
                        "movement must reconcile the old view before admitting the new view");
                assertTrue(world.loadedChunkCount() <= 9,
                        "the actual persisted world cache must stay within its residency target");
                assertEquals(9, residency.residentChunkCount());
            }

            var returned = residency.update(
                    List.of(new WorldChunkResidencyManager.PlayerChunk(0, 0)), 41);
            var restored = world.applyChunkResidencyPlan(returned, 9);
            assertEquals(9, restored.loaded());
            assertEquals(9, world.loadedChunkCount());

            WorldChunk origin = world.getLoadedChunkOrNull(0, 0);
            assertNotNull(origin, "the original chunk must be resident again");
            assertEquals(BlockType.DIAMOND_BLOCK, origin.getBlock(1, 65, 1),
                    "dirty block data must survive repeated view eviction and disk reload");
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
