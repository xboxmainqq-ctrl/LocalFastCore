package ru.nexora.inventory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContainerInventoryStorageTest {
    @TempDir Path directory;

    @Test
    void roundTripsDurabilityEnchantmentsAndRepairCost() throws Exception {
        ContainerInventoryStorage storage = new ContainerInventoryStorage(directory, "durability-world");
        ContainerInventory inventory = new ContainerInventory(27);
        ItemStack wornSword = new ItemStack("minecraft:diamond_sword", 1, 1,
                Map.of("minecraft:unbreaking", 3, "minecraft:sharpness", 5), 6, 321);
        inventory.setSlot(13, wornSword);

        storage.save(12, 64, -8, inventory);

        ContainerInventory loaded = storage.load(12, 64, -8).orElseThrow();
        assertEquals(wornSword, loaded.getSlot(13).orElseThrow());
        assertEquals(2, Byte.toUnsignedInt(Files.readAllBytes(
                directory.resolve("durability-world").resolve("containers")
                        .resolve("12_64_-8.nxc"))[4]));
    }

    @Test
    void returnsEmptyForMissingContainerAndRejectsCoordinateMismatch() throws Exception {
        ContainerInventoryStorage storage = new ContainerInventoryStorage(directory, "empty-world");
        assertTrue(storage.load(0, 64, 0).isEmpty());
        ContainerInventory inventory = new ContainerInventory(9);
        inventory.setSlot(0, new ItemStack("minecraft:stone", 1, 64));
        storage.save(0, 64, 0, inventory);
        assertThrows(java.io.IOException.class, () -> storage.load(1, 64, 0));
    }
}
