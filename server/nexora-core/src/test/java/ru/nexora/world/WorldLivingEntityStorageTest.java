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
    void worldAutosaveFlushesAndRestoresLivingActors() throws Exception {
        WorldLivingEntityStorage storage = new WorldLivingEntityStorage(temp, "autosave-world");
        World world = new World("autosave-world", 0L, new FlatWorldGenerator(),
                0, 65, 0, null, null, null, null, storage);
        WorldLivingEntity zombie = world.livingEntities().spawn(
                "minecraft:zombie", 20, 8, 65, -4);
        world.saveAll();

        World restored = new World("autosave-world", 0L, new FlatWorldGenerator(),
                0, 65, 0, null, null, null, null, storage);
        assertEquals(zombie, restored.livingEntities().find(zombie.runtimeId()).orElseThrow());
    }

    @Test
    void restoresPreviousVerifiedLivingActorSnapshotWhenPrimaryIsCorrupted() throws Exception {
        WorldLivingEntityStorage storage = new WorldLivingEntityStorage(temp, "actor-recovery");
        WorldLivingEntityManager manager = new WorldLivingEntityManager(storage);
        WorldLivingEntity zombie = manager.spawn("minecraft:zombie", 20, 1, 65, 1);
        manager.save();
        manager.move(zombie.runtimeId(), 5, 65, 5);
        manager.save();

        Path primary = temp.resolve("actor-recovery/entities/living.nxe");
        Path backup = temp.resolve("actor-recovery/entities/living.nxe.bak");
        byte[] damaged = Files.readAllBytes(primary);
        damaged[damaged.length / 2] ^= 1;
        Files.write(primary, damaged);

        assertEquals(1, storage.load().size());
        assertEquals(1.0, storage.load().get(0).x(), 0.0001);
        assertArrayEquals(Files.readAllBytes(backup), Files.readAllBytes(primary));
    }

    @Test
    void rejectsDuplicateLivingActorUniqueIds() throws Exception {
        WorldLivingEntityStorage storage = new WorldLivingEntityStorage(temp, "duplicate-unique-id");
        WorldLivingEntity first = new WorldLivingEntity(
                -10, 10, "minecraft:zombie", WorldLivingEntity.Family.UNDEAD,
                0, 65, 0, 0, 0, 0, 20, 20, 0, 0);
        WorldLivingEntity second = new WorldLivingEntity(
                -10, 11, "minecraft:spider", WorldLivingEntity.Family.ARTHROPOD,
                1, 65, 0, 0, 0, 0, 16, 16, 0, 0);

        assertThrows(IOException.class, () -> storage.save(java.util.List.of(first, second)));
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
