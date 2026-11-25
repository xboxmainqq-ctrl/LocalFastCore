package ru.nexora.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class WorldChunkSaveServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void shutdownDrainsEveryAcceptedSaveBeforeReturning() throws Exception {
        WorldStorage storage = new WorldStorage(temporaryDirectory, "NEXORA");
        WorldChunkSaveService service = new WorldChunkSaveService(storage, 1, 16);
        List<WorldChunk> chunks = new ArrayList<>();
        List<CompletableFuture<Void>> saves = new ArrayList<>();

        for (int index = 0; index < 12; index++) {
            WorldChunk chunk = new WorldChunk(index, -index);
            chunk.setBlock(1, 70, 1, BlockType.DIAMOND_BLOCK);
            chunks.add(chunk);
            saves.add(service.request(chunk));
        }

        service.close();
        service.close();

        for (int index = 0; index < saves.size(); index++) {
            saves.get(index).join();
            WorldChunk restored = storage.loadChunk(index, -index).orElseThrow();
            assertEquals(BlockType.DIAMOND_BLOCK, restored.getBlock(1, 70, 1));
            assertFalse(chunks.get(index).isDirty());
        }
        assertEquals(0, service.inFlightSaves());
        assertEquals(0, service.queuedSaves());
        assertEquals(0, service.activeWorkers());
    }

    @Test
    void coalescesConcurrentRequestsForTheSameChunk() throws Exception {
        WorldStorage storage = new WorldStorage(temporaryDirectory, "NEXORA");
        WorldChunkSaveService service = new WorldChunkSaveService(storage, 1, 4);
        WorldChunk chunk = new WorldChunk(3, 7);
        chunk.setBlock(0, 64, 0, BlockType.STONE);
        try {
            CompletableFuture<Void> first = service.request(chunk);
            CompletableFuture<Void> second = service.request(chunk);
            assertSame(first, second);
            first.join();
            assertFalse(chunk.isDirty());
        } finally {
            service.close();
        }
    }

    @Test
    void savesLatestRevisionWhenChunkChangesDuringAnAcceptedWrite() throws Exception {
        WorldStorage storage = new WorldStorage(temporaryDirectory, "NEXORA");
        WorldChunkSaveService service = new WorldChunkSaveService(storage, 1, 8);
        WorldChunk chunk = new WorldChunk(11, -6);
        chunk.setBlock(4, 70, 4, BlockType.STONE);
        try {
            CompletableFuture<Void> first = service.request(chunk);
            Thread editor = new Thread(() -> {
                for (int index = 0; index < 2_000; index++) {
                    chunk.setBlock(4, 70, 4,
                            (index & 1) == 0 ? BlockType.DIRT : BlockType.COBBLESTONE);
                }
                chunk.setBlock(4, 70, 4, BlockType.EMERALD_BLOCK);
            }, "nexora-test-chunk-editor");
            editor.start();
            editor.join();

            // If the first write is still running this coalesces with it; otherwise
            // it schedules a fresh write for the newer revision.
            service.request(chunk).join();
            first.join();

            WorldChunk restored = storage.loadChunk(11, -6).orElseThrow();
            assertEquals(BlockType.EMERALD_BLOCK, restored.getBlock(4, 70, 4));
            assertFalse(chunk.isDirty(), "The newest revision must be marked persisted");
        } finally {
            service.close();
        }
    }

    @Test
    void rejectsNewDirtySaveRequestsAfterShutdownWithoutMarkingThemPersisted() throws Exception {
        WorldStorage storage = new WorldStorage(temporaryDirectory, "NEXORA");
        WorldChunkSaveService service = new WorldChunkSaveService(storage, 1, 4);
        service.close();

        WorldChunk chunk = new WorldChunk(8, 9);
        chunk.setBlock(2, 65, 2, BlockType.GOLD_BLOCK);
        CompletableFuture<Void> rejected = service.request(chunk);

        assertThrows(CompletionException.class, rejected::join);
        assertTrue(chunk.isDirty());
        assertEquals(0, service.inFlightSaves());
    }
}
