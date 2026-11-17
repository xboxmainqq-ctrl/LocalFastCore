package ru.nexora.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nexora.inventory.ContainerInventory;
import ru.nexora.inventory.ContainerInventoryStorage;
import ru.nexora.inventory.ItemStack;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorldContainerManagerTest {
    @TempDir Path temporaryDirectory;

    @Test
    void lazilyLoadsAndCachesContainersAndPersistsThemAcrossManagerRestart() throws Exception {
        ContainerInventoryStorage storage = new ContainerInventoryStorage(temporaryDirectory, "NEXORA");
        WorldContainerManager first = new WorldContainerManager(storage);
        assertEquals(0, first.loadedCount());
        ContainerInventory chest = first.getOrCreate(12, 65, -3);
        assertSame(chest, first.getOrCreate(12, 65, -3));
        chest.setSlot(2, new ItemStack("minecraft:iron_ingot", 16, 64));
        first.saveAll();

        WorldContainerManager restarted = new WorldContainerManager(storage);
        assertTrue(restarted.loaded(12, 65, -3).isEmpty());
        assertEquals(new ItemStack("minecraft:iron_ingot", 16, 64),
                restarted.getOrCreate(12, 65, -3).getSlot(2).orElseThrow());
    }

    @Test
    void removingContainerClearsMemoryAndPersistentContents() throws Exception {
        ContainerInventoryStorage storage = new ContainerInventoryStorage(temporaryDirectory, "NEXORA");
        WorldContainerManager manager = new WorldContainerManager(storage);
        manager.getOrCreate(1, 2, 3).setSlot(0, new ItemStack("minecraft:coal", 8, 64));
        manager.save(1, 2, 3);
        manager.remove(1, 2, 3);
        assertTrue(manager.loaded(1, 2, 3).isEmpty());
        assertTrue(storage.load(1, 2, 3).isEmpty());
    }
}
