package ru.nexora.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorldLivingEntityStorageTest {
    @TempDir
    Path temp;

    @Test
    void savesAndRestoresLivingActorStateAndAdvancesRuntimeIds() throws Exception {
        WorldLivingEntityStorage storage = new WorldLivingEntityStorage(temp, "test-world");
        WorldLivingEntityManager manager = new WorldLivingEntityManager(storage);
        WorldLivingEntity zombie = manager.spawn("minecraft:zombie", 20, 2.5, 65, -3.5);
        manager.move(zombie.runtimeId(), 3.0, 65, -2.0);
        manager.setVelocity(zombie.runtimeId(), 0.1, 0.2, -0.1);
        manager.damage(zombie.runtimeId(), 4).orElseThrow();
        manager.ignite(zombie.runtimeId(), 4, 1_000).orElseThrow();
        manager.save();

        WorldLivingEntityManager restored = new WorldLivingEntityManager(storage);
        WorldLivingEntity loaded = restored.find(zombie.runtimeId()).orElseThrow();
        assertEquals("minecraft:zombie", loaded.identifier());
        assertEquals(WorldLivingEntity.Family.UNDEAD, loaded.family());
        assertEquals(3.0, loaded.x(), 0.0001);
        assertEquals(65.0, loaded.y(), 0.0001);
        assertEquals(-2.0, loaded.z(), 0.0001);
        assertEquals(16.0f, loaded.health(), 0.0001f);
        assertEquals(5_000L, loaded.fireUntilMillis());
        assertEquals(2_000L, loaded.nextFireDamageMillis());

        WorldLivingEntity next = restored.spawn("minecraft:spider", 16, 4, 65, 4);
        assertTrue(next.runtimeId() > zombie.runtimeId());
    }

    @Test
    void rejectsChecksumCorruption() throws Exception {
        WorldLivingEntityStorage storage = new WorldLivingEntityStorage(temp, "corrupt-world");
        WorldLivingEntityManager manager = new WorldLivingEntityManager(storage);
        manager.spawn("minecraft:zombie", 20, 0, 65, 0);
        manager.save();

        Path file = temp.resolve("corrupt-world").resolve("entities").resolve("living.nxe");
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length / 2] ^= 0x20;
        Files.write(file, bytes);
        assertThrows(IOException.class, storage::load);
    }
}
