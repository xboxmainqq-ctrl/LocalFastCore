package ru.nexora.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorldStorageTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void savesAndRestoresGeneratedAndModifiedBlocks() throws Exception {
        WorldStorage storage = new WorldStorage(temporaryDirectory, "NEXORA");
        WorldChunk chunk = new WorldChunk(-2, 3);
        new FlatWorldGenerator().generate(chunk, 99L);
        assertTrue(chunk.isDirty(), "newly generated chunks must be saved once");
        chunk.setBlock(4, 70, 5, BlockType.STONE);

        storage.saveChunk(chunk);
        assertFalse(chunk.isDirty(), "successful storage writes mark the exact revision clean");
        WorldChunk restored = storage.loadChunk(-2, 3).orElseThrow();
        assertFalse(restored.isDirty(), "chunks restored from disk start clean");

        assertEquals(BlockType.BEDROCK, restored.getBlock(0, WorldChunk.MIN_Y, 0));
        int surface = FlatWorldGenerator.surfaceHeight(-17, 63, 99L);
        assertEquals(BlockType.GRASS_BLOCK, restored.getBlock(15, surface, 15));
        assertEquals(BlockType.STONE, restored.getBlock(4, 70, 5));
        assertEquals(BlockType.AIR, restored.getBlock(4, 71, 5));
    }

    @Test
    void worldLoadsSavedChunksInsteadOfRegeneratingThem() throws Exception {
        WorldStorage storage = new WorldStorage(temporaryDirectory, "NEXORA");
        World first = new World("NEXORA", 1L, new FlatWorldGenerator(), 0, 65, 0, storage);
        first.setBlock(-1, 70, -1, BlockType.STONE);
        first.saveAll();

        World restarted = new World("NEXORA", 1L, new FlatWorldGenerator(), 0, 65, 0, storage);
        assertEquals(BlockType.STONE, restarted.getBlock(-1, 70, -1));
        assertEquals(BlockType.AIR, restarted.getBlock(0, 70, 0));
    }

    @Test
    void asynchronouslyPreparesPersistedChunkWithoutRunningGeneratorOnWorker() throws Exception {
        WorldStorage storage = new WorldStorage(temporaryDirectory, "NEXORA");
        WorldChunk persisted = new WorldChunk(4, -2);
        persisted.setBlock(3, 70, 5, BlockType.DIAMOND_BLOCK);
        storage.saveChunk(persisted);
        java.util.concurrent.atomic.AtomicInteger generationCalls = new java.util.concurrent.atomic.AtomicInteger();
        World world = new World("NEXORA", 2L, (chunk, seed) -> generationCalls.incrementAndGet(),
                0, 65, 0, storage);
        try {
            var prepared = world.requestStoredChunkLoad(4, -2).get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(prepared.isPresent());
            assertEquals(0, generationCalls.get(), "Disk preparation must not invoke the world generator");
            WorldChunk installed = world.getChunkFromPreparedStorageLoad(4, -2, prepared);
            assertEquals(BlockType.DIAMOND_BLOCK, installed.getBlock(3, 70, 5));
            assertTrue(world.isChunkLoadedAt(4 * 16, -2 * 16));
            assertEquals(0, generationCalls.get());
        } finally {
            world.closeChunkLoadService();
        }
    }

    @Test
    void asynchronouslyPreparedMissingChunkFallsBackToWorldThreadGeneration() throws Exception {
        WorldStorage storage = new WorldStorage(temporaryDirectory, "NEXORA");
        java.util.concurrent.atomic.AtomicInteger generationCalls = new java.util.concurrent.atomic.AtomicInteger();
        World world = new World("NEXORA", 3L, (chunk, seed) -> {
            generationCalls.incrementAndGet();
            chunk.setBlock(0, 64, 0, BlockType.GRASS_BLOCK);
        }, 0, 65, 0, storage);
        try {
            var prepared = world.requestStoredChunkLoad(9, 9).get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(prepared.isEmpty());
            WorldChunk generated = world.getChunkFromPreparedStorageLoad(9, 9, prepared);
            assertEquals(BlockType.GRASS_BLOCK, generated.getBlock(0, 64, 0));
            assertEquals(1, generationCalls.get());
        } finally {
            world.closeChunkLoadService();
        }
    }

    @Test
    void worldSaveUsesBoundedAsyncWriterAndFinalFlushPersistsLatestMutation() throws Exception {
        WorldStorage storage = new WorldStorage(temporaryDirectory, "AsyncSave");
        World world = new World("AsyncSave", 7L, new FlatWorldGenerator(), 0, 65, 0, storage);
        try {
            world.setBlock(1, 70, 1, BlockType.DIAMOND_BLOCK);
            assertEquals(0, world.activeChunkSaveWorkers());
            world.saveAll();
            assertEquals(0, world.inFlightChunkSaves(), "saveAll must await every chunk write");
            assertFalse(world.getChunk(0, 0).isDirty(), "completed autosave marks the chunk clean");
            assertEquals(BlockType.DIAMOND_BLOCK,
                    storage.loadChunk(0, 0).orElseThrow().getBlock(1, 70, 1));

            world.setBlock(1, 70, 1, BlockType.GOLD_BLOCK);
            assertTrue(world.getChunk(0, 0).isDirty(), "a later block edit makes the chunk dirty again");
            world.saveAll();
            assertFalse(world.getChunk(0, 0).isDirty());
            assertEquals(BlockType.GOLD_BLOCK,
                    storage.loadChunk(0, 0).orElseThrow().getBlock(1, 70, 1));
            assertEquals(0, world.inFlightChunkSaves());
        } finally {
            world.closeChunkLoadService();
        }
    }

    @Test
    void asyncSaveCoalescesConcurrentRequestsAndPersistsMutationDuringWrite() throws Exception {
        WorldStorage storage = new WorldStorage(temporaryDirectory, "CoalescedSave");
        World world = new World("CoalescedSave", 8L, new FlatWorldGenerator(), 0, 65, 0, storage);
        try {
            WorldChunk chunk = world.getChunk(0, 0);
            chunk.setBlock(2, 75, 2, BlockType.STONE);
            world.saveAll();
            chunk.setBlock(2, 75, 2, BlockType.DIAMOND_BLOCK);
            world.saveAll();
            assertEquals(BlockType.DIAMOND_BLOCK,
                    storage.loadChunk(0, 0).orElseThrow().getBlock(2, 75, 2));
        } finally {
            world.closeChunkLoadService();
        }
    }


    @Test
    void skipsCleanLoadedChunksAndPersistsThemAgainAfterMutation() throws Exception {
        WorldStorage storage = new WorldStorage(temporaryDirectory, "DirtyOnly");
        WorldChunk original = new WorldChunk(5, -3);
        storage.saveChunk(original);
        WorldChunk loaded = storage.loadChunk(5, -3).orElseThrow();
        assertFalse(loaded.isDirty());

        WorldChunkSaveService saves = new WorldChunkSaveService(storage);
        try {
            assertTrue(saves.request(loaded).isDone(), "clean chunks need no worker task");
            assertEquals(0, saves.inFlightSaves());

            loaded.setBlock(1, 70, 1, BlockType.DIAMOND_BLOCK);
            assertTrue(loaded.isDirty());
            saves.request(loaded).get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertFalse(loaded.isDirty());
            assertEquals(BlockType.DIAMOND_BLOCK,
                    storage.loadChunk(5, -3).orElseThrow().getBlock(1, 70, 1));
        } finally {
            saves.close();
        }
    }

    @Test
    void snapshotCompletionDoesNotClearMutationsMadeDuringDiskWrite() {
        WorldChunk chunk = new WorldChunk(0, 0);
        chunk.setBlock(0, 70, 0, BlockType.STONE);
        WorldChunk.PersistenceSnapshot snapshot = chunk.persistenceSnapshot();

        chunk.setBlock(0, 70, 0, BlockType.GOLD_BLOCK);
        chunk.markPersisted(snapshot.revision());

        assertTrue(chunk.isDirty(), "the newer revision must remain dirty after an older snapshot is written");
        chunk.markPersisted(chunk.revision());
        assertFalse(chunk.isDirty());
    }

    @Test
    void rejectsCorruptChunkChecksum() throws Exception {
        WorldStorage storage = new WorldStorage(temporaryDirectory, "NEXORA");
        storage.saveChunk(new WorldChunk(0, 0));
        Path file = temporaryDirectory.resolve("NEXORA/chunks/0_0.nxc");
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 1] ^= 0x01;
        Files.write(file, bytes);

        assertThrows(IOException.class, () -> storage.loadChunk(0, 0));
    }

    @Test
    void restoresPreviousVerifiedChunkWhenPrimaryChecksumIsCorrupted() throws Exception {
        WorldStorage storage = new WorldStorage(temporaryDirectory, "ChunkRecovery");
        WorldChunk first = new WorldChunk(2, -3);
        first.setBlock(1, 70, 1, BlockType.STONE);
        storage.saveChunk(first);
        WorldChunk second = new WorldChunk(2, -3);
        second.setBlock(1, 70, 1, BlockType.GOLD_BLOCK);
        storage.saveChunk(second);

        Path primary = temporaryDirectory.resolve("ChunkRecovery/chunks/2_-3.nxc");
        byte[] damaged = Files.readAllBytes(primary);
        damaged[damaged.length - 1] ^= 0x20;
        Files.write(primary, damaged);

        WorldChunk restored = storage.loadChunk(2, -3).orElseThrow();
        assertEquals(BlockType.STONE, restored.getBlock(1, 70, 1));
        assertArrayEquals(Files.readAllBytes(
                temporaryDirectory.resolve("ChunkRecovery/chunks/2_-3.nxc.bak")),
                Files.readAllBytes(primary));

        WorldChunk third = new WorldChunk(2, -3);
        third.setBlock(1, 70, 1, BlockType.DIAMOND_BLOCK);
        storage.saveChunk(third);
        assertEquals(BlockType.DIAMOND_BLOCK,
                storage.loadChunk(2, -3).orElseThrow().getBlock(1, 70, 1));
    }

    @Test
    void rejectsUnsafeWorldDirectoryNames() {
        assertThrows(IllegalArgumentException.class,
                () -> new WorldStorage(temporaryDirectory, "../outside"));
        assertThrows(IllegalArgumentException.class,
                () -> new WorldStorage(temporaryDirectory, "bad/world"));
    }

    @Test
    void returnsEmptyForChunksThatHaveNotBeenSaved() throws Exception {
        WorldStorage storage = new WorldStorage(temporaryDirectory, "NEXORA");
        assertTrue(storage.loadChunk(10, -10).isEmpty());
    }

    @Test
    void unloadPersistsDirtyChunkOnSaveWorkersBeforeEviction() throws Exception {
        WorldStorage storage = new WorldStorage(temporaryDirectory, "UnloadAsync");
        World world = new World("UnloadAsync", 12L, new FlatWorldGenerator(), 0, 65, 0, storage);
        try {
            world.setBlock(-1, 72, -1, BlockType.DIAMOND_BLOCK);
            assertEquals(1, world.loadedChunkCount());

            assertTrue(world.unloadChunk(-1, -1));
            assertEquals(0, world.loadedChunkCount(),
                    "a successfully persisted chunk should leave the resident cache");
            assertEquals(BlockType.DIAMOND_BLOCK,
                    storage.loadChunk(-1, -1).orElseThrow().getBlock(15, 72, 15));

            assertEquals(BlockType.DIAMOND_BLOCK, world.getBlock(-1, 72, -1),
                    "a later access must restore the persisted chunk");
            assertEquals(1, world.loadedChunkCount());
        } finally {
            world.closeChunkLoadService();
        }
    }

    @Test
    void cleanChunkUnloadDoesNotChangePersistedBlockState() throws Exception {
        WorldStorage storage = new WorldStorage(temporaryDirectory, "CleanUnload");
        World world = new World("CleanUnload", 13L, new FlatWorldGenerator(), 0, 65, 0, storage);
        try {
            world.setBlock(4, 71, 4, BlockType.GOLD_BLOCK);
            world.saveAll();
            assertFalse(world.getChunk(0, 0).isDirty());

            assertTrue(world.unloadChunk(0, 0));
            assertEquals(0, world.loadedChunkCount());
            assertEquals(BlockType.GOLD_BLOCK,
                    storage.loadChunk(0, 0).orElseThrow().getBlock(4, 71, 4));
        } finally {
            world.closeChunkLoadService();
        }
    }
}
