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
