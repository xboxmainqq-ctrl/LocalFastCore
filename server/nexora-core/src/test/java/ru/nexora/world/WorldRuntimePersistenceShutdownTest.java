package ru.nexora.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import ru.nexora.inventory.ItemStack;

import static org.junit.jupiter.api.Assertions.*;

class WorldRuntimePersistenceShutdownTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void finalRuntimeShutdownFlushPersistsDirtyChunksBeforeStoppingWorkers() throws Exception {
        WorldStorage storage = new WorldStorage(temporaryDirectory, "ShutdownWorld");
        WorldItemEntityStorage itemStorage = new WorldItemEntityStorage(temporaryDirectory, "ShutdownWorld");
        World world = new World("ShutdownWorld", 42L, new FlatWorldGenerator(),
                0, 65, 0, storage, itemStorage);
        WorldRuntimeModule runtime = new WorldRuntimeModule(
                world,
                Duration.ofHours(1),
                failure -> fail("Unexpected autosave failure: " + failure),
                () -> {},
                ignored -> {},
                ignored -> {},
                ignored -> {});

        world.setBlock(-17, 72, 31, BlockType.DIAMOND_BLOCK);
        WorldItemEntity dropped = world.itemEntities().spawn(
                new ItemStack("minecraft:diamond", 3, 64),
                4.5, 66.0, -2.5, 0.1, 0.2, -0.1,
                1_000L, 500L, "shutdown-player");
        assertTrue(world.getChunk(-2, 1).isDirty());

        runtime.close();

        assertEquals(BlockType.DIAMOND_BLOCK,
                storage.loadChunk(-2, 1).orElseThrow().getBlock(15, 72, 15));
        assertFalse(world.getChunk(-2, 1).isDirty());
        assertEquals(java.util.List.of(dropped), itemStorage.load(),
                "Final shutdown flush must persist dropped item actors too");
        assertEquals(0, world.activeChunkSaveWorkers());
        assertEquals(0, world.queuedChunkSaves());
        assertEquals(0, world.inFlightChunkSaves());
    }
}
