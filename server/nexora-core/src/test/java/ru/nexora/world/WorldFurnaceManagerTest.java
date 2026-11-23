package ru.nexora.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nexora.inventory.ContainerInventoryStorage;
import ru.nexora.inventory.FurnaceService;
import ru.nexora.inventory.FurnaceStateStorage;
import ru.nexora.inventory.ItemStack;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorldFurnaceManagerTest {
    @TempDir Path temporaryDirectory;

    @Test
    void persistsFurnaceInventoryAndProgressAcrossManagerRestart() throws Exception {
        ContainerInventoryStorage inventoryStorage =
                new ContainerInventoryStorage(temporaryDirectory, "NEXORA");
        FurnaceStateStorage stateStorage = new FurnaceStateStorage(temporaryDirectory, "NEXORA");
        WorldFurnaceManager first = new WorldFurnaceManager(inventoryStorage, stateStorage);
        var furnace = first.getOrCreate(3, 64, -5);
        furnace.setSlot(0, new ItemStack("minecraft:raw_iron", 1, 64));
        furnace.setSlot(1, new ItemStack("minecraft:stick", 1, 64));

        var partial = first.tick(3, 64, -5, 100);
        assertEquals(50, partial.cookPercent());
        first.saveAll();

        WorldFurnaceManager restarted = new WorldFurnaceManager(inventoryStorage, stateStorage);
        var restored = restarted.getOrCreate(3, 64, -5);
        assertEquals(new ItemStack("minecraft:raw_iron", 1, 64),
                restored.getSlot(0).orElseThrow());
        assertTrue(restored.getSlot(1).isEmpty()); // The stick was consumed to supply the first 100 burn ticks.

        restored.setSlot(1, new ItemStack("minecraft:blaze_rod", 1, 64));
        restarted.tick(3, 64, -5, 100);
        assertEquals(new ItemStack("minecraft:iron_ingot", 1, 64),
                restored.getSlot(2).orElseThrow());
        assertTrue(restored.getSlot(0).isEmpty());
    }

    @Test
    void tickAllAdvancesEveryLoadedFurnace() throws Exception {
        ContainerInventoryStorage inventoryStorage =
                new ContainerInventoryStorage(temporaryDirectory, "NEXORA");
        FurnaceStateStorage stateStorage = new FurnaceStateStorage(temporaryDirectory, "NEXORA");
        WorldFurnaceManager manager = new WorldFurnaceManager(inventoryStorage, stateStorage);
        var first = manager.getOrCreate(0, 64, 0);
        var second = manager.getOrCreate(1, 64, 0);
        first.setSlot(0, new ItemStack("minecraft:raw_iron", 1, 64));
        first.setSlot(1, new ItemStack("minecraft:blaze_rod", 1, 64));
        second.setSlot(0, new ItemStack("minecraft:raw_gold", 1, 64));
        second.setSlot(1, new ItemStack("minecraft:blaze_rod", 1, 64));
        java.util.concurrent.atomic.AtomicInteger updates = new java.util.concurrent.atomic.AtomicInteger();
        manager.addUpdateListener(update -> updates.incrementAndGet());

        manager.tickAll(200);

        assertEquals(2, updates.get());
        var stats = manager.tickStats();
        assertEquals(1, stats.tickBatches());
        assertEquals(2, stats.furnacesProcessed());
        assertEquals(2, stats.changedFurnaces());
        assertTrue(stats.totalExecutionNanos() >= stats.maxExecutionNanos());
        assertTrue(stats.maxExecutionNanos() >= stats.lastExecutionNanos());
        assertTrue(stats.averageExecutionMillis() >= 0.0);
        assertEquals(new ItemStack("minecraft:iron_ingot", 1, 64),
                first.getSlot(2).orElseThrow());
        assertEquals(new ItemStack("minecraft:gold_ingot", 1, 64),
                second.getSlot(2).orElseThrow());
    }

    @Test
    void tickAllUsesTheSpecializedFurnaceCookingProfiles() throws Exception {
        ContainerInventoryStorage inventoryStorage =
                new ContainerInventoryStorage(temporaryDirectory, "NEXORA");
        FurnaceStateStorage stateStorage = new FurnaceStateStorage(temporaryDirectory, "NEXORA");
        WorldFurnaceManager manager = new WorldFurnaceManager(inventoryStorage, stateStorage);

        var blast = manager.getOrCreate(4, 64, 0, FurnaceService.Kind.BLAST_FURNACE);
        blast.setSlot(0, new ItemStack("minecraft:raw_iron", 1, 64));
        blast.setSlot(1, new ItemStack("minecraft:coal", 1, 64));
        var smoker = manager.getOrCreate(5, 64, 0, FurnaceService.Kind.SMOKER);
        smoker.setSlot(0, new ItemStack("minecraft:beef", 1, 64));
        smoker.setSlot(1, new ItemStack("minecraft:coal", 1, 64));

        manager.tickAll(100);

        assertEquals(new ItemStack("minecraft:iron_ingot", 1, 64), blast.getSlot(2).orElseThrow());
        assertEquals(new ItemStack("minecraft:cooked_beef", 1, 64), smoker.getSlot(2).orElseThrow());
    }

    @Test
    void preservesBlastFurnaceProgressAcrossRestart() throws Exception {
        ContainerInventoryStorage inventoryStorage =
                new ContainerInventoryStorage(temporaryDirectory, "NEXORA");
        FurnaceStateStorage stateStorage = new FurnaceStateStorage(temporaryDirectory, "NEXORA");
        WorldFurnaceManager first = new WorldFurnaceManager(inventoryStorage, stateStorage);
        var blast = first.getOrCreate(7, 64, -2, FurnaceService.Kind.BLAST_FURNACE);
        blast.setSlot(0, new ItemStack("minecraft:raw_iron", 1, 64));
        blast.setSlot(1, new ItemStack("minecraft:coal", 1, 64));
        var partial = first.tick(7, 64, -2, FurnaceService.Kind.BLAST_FURNACE, 50);
        assertEquals(50, partial.cookPercent());
        first.saveAll();

        WorldFurnaceManager restarted = new WorldFurnaceManager(inventoryStorage, stateStorage);
        var restored = restarted.getOrCreate(7, 64, -2, FurnaceService.Kind.BLAST_FURNACE);
        var completed = restarted.tick(7, 64, -2, FurnaceService.Kind.BLAST_FURNACE, 50);

        assertEquals(0, completed.cookProgress());
        assertEquals(new ItemStack("minecraft:iron_ingot", 1, 64), restored.getSlot(2).orElseThrow());
    }

    @Test
    void preservesPendingExperienceAcrossRestartAndOutputCollection() throws Exception {
        ContainerInventoryStorage inventoryStorage =
                new ContainerInventoryStorage(temporaryDirectory, "NEXORA");
        FurnaceStateStorage stateStorage = new FurnaceStateStorage(temporaryDirectory, "NEXORA");
        WorldFurnaceManager first = new WorldFurnaceManager(inventoryStorage, stateStorage);
        var furnace = first.getOrCreate(8, 64, 1, FurnaceService.Kind.BLAST_FURNACE);
        furnace.setSlot(0, new ItemStack("minecraft:raw_iron", 1, 64));
        furnace.setSlot(1, new ItemStack("minecraft:coal", 1, 64));
        first.tick(8, 64, 1, FurnaceService.Kind.BLAST_FURNACE, 100);
        first.saveAll();

        WorldFurnaceManager restarted = new WorldFurnaceManager(inventoryStorage, stateStorage);
        restarted.getOrCreate(8, 64, 1, FurnaceService.Kind.BLAST_FURNACE);
        assertEquals(700, restarted.collectExperience(8, 64, 1, 1, 1));
        restarted.save(8, 64, 1);
        WorldFurnaceManager afterClaim = new WorldFurnaceManager(inventoryStorage, stateStorage);
        afterClaim.getOrCreate(8, 64, 1, FurnaceService.Kind.BLAST_FURNACE);
        assertEquals(0, afterClaim.collectExperience(8, 64, 1, 1, 1));
    }

    @Test
    void pendingExperiencePreventsFurnaceValueFromBeingSilentlyDiscarded() throws Exception {
        ContainerInventoryStorage inventoryStorage =
                new ContainerInventoryStorage(temporaryDirectory, "NEXORA");
        FurnaceStateStorage stateStorage = new FurnaceStateStorage(temporaryDirectory, "NEXORA");
        WorldFurnaceManager manager = new WorldFurnaceManager(inventoryStorage, stateStorage);
        var furnace = manager.getOrCreate(9, 64, 1);
        furnace.setSlot(0, new ItemStack("minecraft:raw_iron", 1, 64));
        furnace.setSlot(1, new ItemStack("minecraft:coal", 1, 64));
        manager.tick(9, 64, 1, 200);
        assertEquals(new ItemStack("minecraft:iron_ingot", 1, 64), furnace.getSlot(2).orElseThrow());
        furnace.setSlot(2, null);
        assertTrue(manager.hasPendingExperience(9, 64, 1));
        assertEquals(700, manager.collectExperience(9, 64, 1, 1, 1));
        assertFalse(manager.hasPendingExperience(9, 64, 1));
    }

    @Test
    void failedFurnaceStateDeleteKeepsInventoryAndLoadedStateAvailable() throws Exception {
        ContainerInventoryStorage inventoryStorage =
                new ContainerInventoryStorage(temporaryDirectory, "NEXORA");
        FurnaceStateStorage stateStorage = new FurnaceStateStorage(temporaryDirectory, "NEXORA");
        WorldFurnaceManager manager = new WorldFurnaceManager(inventoryStorage, stateStorage);
        var furnace = manager.getOrCreate(10, 64, 2);
        furnace.setSlot(0, new ItemStack("minecraft:raw_iron", 1, 64));
        furnace.setSlot(1, new ItemStack("minecraft:stick", 1, 64));
        manager.tick(10, 64, 2, 40);
        manager.save(10, 64, 2);

        Path primary = temporaryDirectory.resolve("NEXORA/furnaces/10_64_2.nxf");
        Path target = temporaryDirectory.resolve("protected-furnace-state-target");
        java.nio.file.Files.writeString(target, "keep");
        java.nio.file.Files.delete(primary);
        java.nio.file.Files.createSymbolicLink(primary, target);

        assertThrows(java.io.IOException.class, () -> manager.remove(10, 64, 2));
        assertSame(furnace, manager.getOrCreate(10, 64, 2));
        assertEquals(new ItemStack("minecraft:raw_iron", 1, 64),
                inventoryStorage.load(10, 64, 2).orElseThrow().getSlot(0).orElseThrow());
        assertEquals(40, manager.snapshotState(10, 64, 2).cookProgress());
    }

    @Test
    void failedInventoryDeleteRestoresFurnaceStateAndKeepsLoadedFurnace() throws Exception {
        ContainerInventoryStorage inventoryStorage =
                new ContainerInventoryStorage(temporaryDirectory, "NEXORA");
        FurnaceStateStorage stateStorage = new FurnaceStateStorage(temporaryDirectory, "NEXORA");
        WorldFurnaceManager manager = new WorldFurnaceManager(inventoryStorage, stateStorage);
        var furnace = manager.getOrCreate(11, 64, 2);
        furnace.setSlot(0, new ItemStack("minecraft:raw_iron", 1, 64));
        furnace.setSlot(1, new ItemStack("minecraft:stick", 1, 64));
        manager.tick(11, 64, 2, 60);
        manager.save(11, 64, 2);

        Path primary = temporaryDirectory.resolve("NEXORA/containers/11_64_2.nxc");
        Path target = temporaryDirectory.resolve("protected-furnace-inventory-target");
        java.nio.file.Files.writeString(target, "keep");
        java.nio.file.Files.delete(primary);
        java.nio.file.Files.createSymbolicLink(primary, target);

        assertThrows(java.io.IOException.class, () -> manager.remove(11, 64, 2));
        assertSame(furnace, manager.getOrCreate(11, 64, 2));
        assertEquals(60, manager.snapshotState(11, 64, 2).cookProgress());
        assertEquals(60, stateStorage.load(11, 64, 2).orElseThrow().cookProgress());
        assertEquals(new ItemStack("minecraft:raw_iron", 1, 64),
                furnace.getSlot(0).orElseThrow());
    }

    @Test
    void failedRemovalOfUnloadedFurnaceRestoresPersistedProcessingState() throws Exception {
        ContainerInventoryStorage inventoryStorage =
                new ContainerInventoryStorage(temporaryDirectory, "NEXORA");
        FurnaceStateStorage stateStorage = new FurnaceStateStorage(temporaryDirectory, "NEXORA");
        WorldFurnaceManager writer = new WorldFurnaceManager(inventoryStorage, stateStorage);
        var furnace = writer.getOrCreate(12, 64, 3);
        furnace.setSlot(0, new ItemStack("minecraft:raw_iron", 1, 64));
        furnace.setSlot(1, new ItemStack("minecraft:stick", 1, 64));
        writer.tick(12, 64, 3, 60);
        writer.saveAll();

        // A fresh manager has not loaded this furnace; removal must still preserve
        // the durable state if deleting its inventory fails after the state file.
        WorldFurnaceManager remover = new WorldFurnaceManager(inventoryStorage, stateStorage);
        Path primary = temporaryDirectory.resolve("NEXORA/containers/12_64_3.nxc");
        Path backup = temporaryDirectory.resolve("NEXORA/containers/12_64_3.nxc.bak");
        Path target = temporaryDirectory.resolve("protected-unloaded-furnace-inventory");
        byte[] inventoryBeforeRemoval = java.nio.file.Files.readAllBytes(primary);
        java.nio.file.Files.writeString(target, "keep");
        java.nio.file.Files.createSymbolicLink(backup, target);

        assertThrows(java.io.IOException.class, () -> remover.remove(12, 64, 3));
        assertEquals(60, stateStorage.load(12, 64, 3).orElseThrow().cookProgress());
        assertArrayEquals(inventoryBeforeRemoval, java.nio.file.Files.readAllBytes(primary));
    }

    @Test
    void failedInventorySaveRestoresPreviouslyPersistedFurnaceTimers() throws Exception {
        ContainerInventoryStorage inventoryStorage =
                new ContainerInventoryStorage(temporaryDirectory, "NEXORA");
        FurnaceStateStorage stateStorage = new FurnaceStateStorage(temporaryDirectory, "NEXORA");
        WorldFurnaceManager manager = new WorldFurnaceManager(inventoryStorage, stateStorage);
        var furnace = manager.getOrCreate(13, 64, 3);
        furnace.setSlot(0, new ItemStack("minecraft:raw_iron", 1, 64));
        furnace.setSlot(1, new ItemStack("minecraft:blaze_rod", 1, 64));
        manager.tick(13, 64, 3, 60);
        manager.save(13, 64, 3);
        assertEquals(60, stateStorage.load(13, 64, 3).orElseThrow().cookProgress());

        manager.tick(13, 64, 3, 20);
        assertEquals(80, manager.snapshotState(13, 64, 3).cookProgress());
        Path backup = temporaryDirectory.resolve("NEXORA/containers/13_64_3.nxc.bak");
        Path target = temporaryDirectory.resolve("protected-furnace-save-backup");
        java.nio.file.Files.writeString(target, "keep");
        java.nio.file.Files.createSymbolicLink(backup, target);

        assertThrows(java.io.IOException.class, () -> manager.save(13, 64, 3));
        assertEquals(60, stateStorage.load(13, 64, 3).orElseThrow().cookProgress(),
                "failed inventory write must roll furnace timers back to the last durable snapshot");
        assertEquals(80, manager.snapshotState(13, 64, 3).cookProgress(),
                "live processing continues; a later successful save can persist the newer snapshot");
    }

    @Test
    void removeDeletesBothInventoryAndProgress() throws Exception {
        ContainerInventoryStorage inventoryStorage =
                new ContainerInventoryStorage(temporaryDirectory, "NEXORA");
        FurnaceStateStorage stateStorage = new FurnaceStateStorage(temporaryDirectory, "NEXORA");
        WorldFurnaceManager manager = new WorldFurnaceManager(inventoryStorage, stateStorage);
        manager.getOrCreate(1, 2, 3).setSlot(0, new ItemStack("minecraft:potato", 1, 64));
        manager.tick(1, 2, 3, 10);
        manager.remove(1, 2, 3);

        assertEquals(0, manager.loadedCount());
        assertTrue(inventoryStorage.load(1, 2, 3).isEmpty());
        assertTrue(stateStorage.load(1, 2, 3).isEmpty());
    }
}
