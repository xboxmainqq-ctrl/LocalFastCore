package ru.nexora.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nexora.inventory.ItemStack;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorldItemEntityMovementPersistenceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsFallingItemPositionSoRestartDoesNotReturnItToSpawnPoint() throws Exception {
        WorldItemEntityStorage storage = new WorldItemEntityStorage(temporaryDirectory, "moving-items");
        WorldItemEntityManager manager = new WorldItemEntityManager(storage);
        World world = new World("moving-items", 1L, new FlatWorldGenerator());
        WorldItemEntity spawned = manager.spawn(new ItemStack("minecraft:diamond", 2, 64),
                0.5, 70.0, 0.5, 0, 0, 0, 1_000, 0, null);

        manager.tick(world, 0.05, 1_000);
        WorldItemEntity moved = manager.find(spawned.runtimeId()).orElseThrow();
        assertTrue(moved.y() < spawned.y(), "item should fall during the world tick");

        WorldItemEntityManager afterRestart = new WorldItemEntityManager(storage);
        WorldItemEntity restored = afterRestart.find(spawned.runtimeId()).orElseThrow();
        assertEquals(moved, restored, "restart must restore the last persisted item position");
    }

    @Test
    void throttlesMovementPersistenceInsteadOfWritingEveryPhysicsTick() throws Exception {
        WorldItemEntityStorage storage = new WorldItemEntityStorage(temporaryDirectory, "throttled-items");
        WorldItemEntityManager manager = new WorldItemEntityManager(storage);
        World world = new World("throttled-items", 1L, new FlatWorldGenerator());
        WorldItemEntity spawned = manager.spawn(new ItemStack("minecraft:diamond", 1, 64),
                0.5, 70.0, 0.5, 0, 0, 0, 1_000, 0, null);

        manager.tick(world, 0.05, 1_000);
        WorldItemEntity firstPersisted = manager.find(spawned.runtimeId()).orElseThrow();
        manager.tick(world, 0.05, 1_050);

        WorldItemEntityManager afterRestart = new WorldItemEntityManager(storage);
        assertEquals(firstPersisted,
                afterRestart.find(spawned.runtimeId()).orElseThrow(),
                "sub-second physics updates should not rewrite the durable snapshot");
    }
}
