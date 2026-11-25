package ru.nexora.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorldChunkResidencyFailureTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void failedChunkFlushKeepsCacheAndPlannerConsistentAndRetriesLater() throws Exception {
        WorldStorage storage = new WorldStorage(temporaryDirectory, "NEXORA");
        World world = new World("NEXORA", 1L, new FlatWorldGenerator(),
                0, 65, 0, storage);
        WorldChunkResidencyManager manager = new WorldChunkResidencyManager(1, 0);
        try {
            world.setBlock(1, 70, 1, BlockType.DIAMOND_BLOCK);
            var initial = manager.update(
                    List.of(new WorldChunkResidencyManager.PlayerChunk(0, 0)), 0);
            world.applyChunkResidencyPlan(initial);

            // Turn the target file into a directory after its chunk is already
            // loaded, forcing the next durable flush to fail without corrupting
            // the in-memory authoritative chunk.
            Path blockedChunkFile = storage.worldDirectory()
                    .resolve("chunks").resolve("0_0.nxc");
            Files.createDirectory(blockedChunkFile);

            var expired = manager.update(List.of(), 1);
            World.ChunkResidencyResult failedFlush =
                    world.applyChunkResidencyPlan(expired);
            manager.retainResidentChunks(world.loadedChunkCoordinates());

            assertEquals(1, failedFlush.retained());
            assertEquals(1, world.loadedChunkCount());
            assertTrue(manager.isResident(0, 0));
            assertEquals(BlockType.DIAMOND_BLOCK, world.getLoadedChunkOrNull(0, 0)
                    .getBlock(1, 70, 1));

            Files.delete(blockedChunkFile);
            var retry = manager.update(List.of(), 2);
            assertEquals(List.of(new WorldChunkResidencyManager.ChunkCoordinate(0, 0)),
                    retry.unload());

            World.ChunkResidencyResult successfulRetry =
                    world.applyChunkResidencyPlan(retry);
            manager.retainResidentChunks(world.loadedChunkCoordinates());

            assertEquals(1, successfulRetry.unloaded());
            assertEquals(0, world.loadedChunkCount());
            assertFalse(manager.isResident(0, 0));
            assertEquals(BlockType.DIAMOND_BLOCK,
                    storage.loadChunk(0, 0).orElseThrow().getBlock(1, 70, 1));
        } finally {
            world.closeChunkLoadService();
        }
    }
}
