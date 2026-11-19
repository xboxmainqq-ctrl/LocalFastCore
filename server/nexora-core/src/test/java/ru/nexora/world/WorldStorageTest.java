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
        chunk.setBlock(4, 70, 5, BlockType.STONE);

        storage.saveChunk(chunk);
        WorldChunk restored = storage.loadChunk(-2, 3).orElseThrow();

        assertEquals(BlockType.BEDROCK, restored.getBlock(0, 60, 0));
        assertEquals(BlockType.GRASS_BLOCK, restored.getBlock(15, 64, 15));
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
            assertEquals(BlockType.DIAMOND_BLOCK,
                    storage.loadChunk(0, 0).orElseThrow().getBlock(1, 70, 1));

            world.setBlock(1, 70, 1, BlockType.GOLD_BLOCK);
            world.saveAll();
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
}
