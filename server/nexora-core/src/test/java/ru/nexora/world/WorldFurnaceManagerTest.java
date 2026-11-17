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
