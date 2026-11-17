package ru.nexora.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.util.zip.CRC32C;

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
        long nowTick = Math.max(0L, System.nanoTime() / 50_000_000L);
        assertTrue(first.mobSpawners().configure(2, 65, 3,
                "minecraft:skeleton", 300, nowTick));
        first.saveAll();

        WorldStorage reopenedStorage = new WorldStorage(temporaryDirectory, "spawner-world");
        World reopened = new World("spawner-world", 1L, new FlatWorldGenerator(),
                0, 65, 0, reopenedStorage);

        MobSpawnerState restored = reopened.mobSpawners().snapshot().get(0);
        long remaining = restored.nextSpawnTick()
                - Math.max(0L, System.nanoTime() / 50_000_000L);
        assertEquals("minecraft:skeleton", restored.entityIdentifier());
        assertEquals(300, restored.delayTicks());
        assertTrue(remaining >= 0 && remaining <= 300);
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
    @Test
    void migratesLegacyAbsoluteTimerToConfiguredRemainingDelay() throws Exception {
        ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bodyBytes)) {
            out.writeInt(0x4e585350);
            out.writeByte(1);
            out.writeInt(1);
            out.writeInt(4);
            out.writeInt(65);
            out.writeInt(7);
            out.writeUTF("minecraft:zombie");
            out.writeInt(200);
            out.writeLong(987_654_321L);
        }
        byte[] body = bodyBytes.toByteArray();
        CRC32C crc = new CRC32C();
        crc.update(body, 0, body.length);
        ByteArrayOutputStream fileBytes = new ByteArrayOutputStream();
        fileBytes.writeBytes(body);
        try (DataOutputStream out = new DataOutputStream(fileBytes)) {
            out.writeInt((int) crc.getValue());
        }
        Files.write(temporaryDirectory.resolve("mob-spawners.dat"), fileBytes.toByteArray());

        MobSpawnerStorage storage = new MobSpawnerStorage(temporaryDirectory);
        MobSpawnerState migrated = storage.load().get(0);
        assertEquals(200, migrated.nextSpawnTick());
    }

}
