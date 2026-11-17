package ru.nexora.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MobSpawnerStorageTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void worldSaveRestoresSpawnerConfigurationAndTimer() throws Exception {
        WorldStorage storage = new WorldStorage(temporaryDirectory, "spawner-world");
        World first = new World("spawner-world", 1L, new FlatWorldGenerator(),
                0, 65, 0, storage);
        first.setBlock(2, 65, 3, BlockType.SPAWNER);
        assertTrue(first.mobSpawners().configure(2, 65, 3,
                "minecraft:skeleton", 300, 50_000));
        first.saveAll();

        WorldStorage reopenedStorage = new WorldStorage(temporaryDirectory, "spawner-world");
        World reopened = new World("spawner-world", 1L, new FlatWorldGenerator(),
                0, 65, 0, reopenedStorage);

        MobSpawnerState restored = reopened.mobSpawners().snapshot().get(0);
        assertEquals("minecraft:skeleton", restored.entityIdentifier());
        assertEquals(300, restored.delayTicks());
        assertEquals(50_000, restored.nextSpawnTick());
        assertEquals(BlockType.SPAWNER, reopened.getBlock(2, 65, 3));
    }

    @Test
    void storageRejectsChecksumCorruption() throws Exception {
        MobSpawnerStorage storage = new MobSpawnerStorage(temporaryDirectory);
        storage.save(java.util.List.of(new MobSpawnerState(
                1, 65, 2, "minecraft:zombie", 200, 400)));
        Path file = temporaryDirectory.resolve("mob-spawners.dat");
        byte[] bytes = java.nio.file.Files.readAllBytes(file);
        bytes[5] ^= 1;
        java.nio.file.Files.write(file, bytes);

        assertThrows(java.io.IOException.class, storage::load);
    }
}
