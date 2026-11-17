package ru.nexora.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nexora.inventory.ContainerInventory;
import ru.nexora.inventory.ContainerInventoryStorage;
import ru.nexora.inventory.ItemStack;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorldContainerIntegrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void worldAutosaveLifecyclePersistsBlockContainerContents() throws Exception {
        WorldStorage chunks = new WorldStorage(temporaryDirectory.resolve("worlds"), "NEXORA");
        WorldItemEntityStorage items = new WorldItemEntityStorage(temporaryDirectory.resolve("worlds"), "NEXORA");
        ContainerInventoryStorage containers =
                new ContainerInventoryStorage(temporaryDirectory.resolve("worlds"), "NEXORA");
        World world = new World("NEXORA", 1L, new FlatWorldGenerator(),
                0, 65, 0, chunks, items, containers);
        ContainerInventory chest = world.containers().orElseThrow().getOrCreate(8, 65, -2);
        chest.setSlot(5, new ItemStack("minecraft:gold_ingot", 21, 64));
        world.saveAll();

        World restarted = new World("NEXORA", 1L, new FlatWorldGenerator(),
                0, 65, 0,
                new WorldStorage(temporaryDirectory.resolve("worlds"), "NEXORA"),
                new WorldItemEntityStorage(temporaryDirectory.resolve("worlds"), "NEXORA"),
                new ContainerInventoryStorage(temporaryDirectory.resolve("worlds"), "NEXORA"));
        assertEquals(new ItemStack("minecraft:gold_ingot", 21, 64),
                restarted.containers().orElseThrow().getOrCreate(8, 65, -2)
                        .getSlot(5).orElseThrow());
    }
}
