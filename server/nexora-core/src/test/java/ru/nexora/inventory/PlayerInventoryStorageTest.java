package ru.nexora.inventory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PlayerInventoryStorageTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsSlotsAndSelectedHotbarSlot() throws Exception {
        PlayerInventoryStorage storage = new PlayerInventoryStorage(temporaryDirectory);
        PlayerInventory inventory = new PlayerInventory();
        inventory.setSlot(0, new ItemStack("nexora:arcane_shard", 17, 64));
        inventory.setSlot(12, new ItemStack("nexora:wand", 1, 1));
        inventory.selectHotbarSlot(4);

        storage.save("player-123", inventory);

        PlayerInventory loaded = storage.load("player-123").orElseThrow();
        assertEquals(36, loaded.size());
        assertEquals(4, loaded.selectedHotbarSlot());
        assertEquals(new ItemStack("nexora:arcane_shard", 17, 64), loaded.getSlot(0).orElseThrow());
        assertEquals(new ItemStack("nexora:wand", 1, 1), loaded.getSlot(12).orElseThrow());
        assertTrue(loaded.getSlot(1).isEmpty());
        var legacyState = storage.loadState("player-123").orElseThrow();
        assertEquals(51, legacyState.craftingUiInventory().size());
        assertTrue(legacyState.craftingUiInventory().getSlot(28).isEmpty());
    }

    @Test
    void atomicallyRoundTripsPlayerUiCraftingGridInVersionFourFormat() throws Exception {
        PlayerInventoryStorage storage = new PlayerInventoryStorage(temporaryDirectory);
        PlayerInventory inventory = new PlayerInventory();
        inventory.setSlot(0, new ItemStack("minecraft:iron_nugget", 9, 64));
        inventory.selectHotbarSlot(2);
        ContainerInventory ui = new ContainerInventory(51);
        ui.setSlot(28, new ItemStack("minecraft:oak_planks", 2, 64));
        ui.setSlot(31, new ItemStack("minecraft:oak_log", 1, 64));

        storage.save("craft-player", inventory, ui);

        var loaded = storage.loadState("craft-player").orElseThrow();
        assertEquals(2, loaded.inventory().selectedHotbarSlot());
        assertEquals(9, loaded.inventory().count("minecraft:iron_nugget"));
        assertEquals(new ItemStack("minecraft:oak_planks", 2, 64),
                loaded.craftingUiInventory().getSlot(28).orElseThrow());
        assertEquals(new ItemStack("minecraft:oak_log", 1, 64),
                loaded.craftingUiInventory().getSlot(31).orElseThrow());
        assertEquals(4, Byte.toUnsignedInt(Files.readAllBytes(
                temporaryDirectory.resolve("craft-player.nxi"))[4]));
        assertEquals(9, storage.load("craft-player").orElseThrow().count("minecraft:iron_nugget"));
    }

    @Test
    void returnsEmptyWhenInventoryDoesNotExist() throws Exception {
        PlayerInventoryStorage storage = new PlayerInventoryStorage(temporaryDirectory);
        assertTrue(storage.load("unknown-player").isEmpty());
    }

    @Test
    void rejectsCorruptedInventoryChecksum() throws Exception {
        PlayerInventoryStorage storage = new PlayerInventoryStorage(temporaryDirectory);
        PlayerInventory inventory = new PlayerInventory();
        inventory.setSlot(0, new ItemStack("minecraft:stone", 1, 64));
        storage.save("player-456", inventory);

        Path file = temporaryDirectory.resolve("player-456.nxi");
        byte[] bytes = Files.readAllBytes(file);
        bytes[5] ^= 0x01;
        Files.write(file, bytes);

        assertThrows(IOException.class, () -> storage.load("player-456"));
    }

    @Test
    void rejectsInvalidPlayerIdentifiers() throws Exception {
        PlayerInventoryStorage storage = new PlayerInventoryStorage(temporaryDirectory);
        assertThrows(IllegalArgumentException.class, () -> storage.save("../outside", new PlayerInventory()));
        assertThrows(IllegalArgumentException.class, () -> storage.load("../outside"));
    }
    @Test
    void persistsEnchantmentLevelsAndAnvilRepairPenalty() throws Exception {
        PlayerInventoryStorage storage = new PlayerInventoryStorage(temporaryDirectory);
        PlayerInventory inventory = new PlayerInventory();
        inventory.setSlot(0, new ItemStack("minecraft:diamond_sword", 1, 1,
                java.util.Map.of("minecraft:sharpness", 5, "minecraft:unbreaking", 3), 7));
        storage.save("enchanted-player", inventory);

        ItemStack restored = storage.load("enchanted-player").orElseThrow()
                .getSlot(0).orElseThrow();
        assertEquals(java.util.Map.of("minecraft:sharpness", 5, "minecraft:unbreaking", 3),
                restored.enchantments());
        assertEquals(7, restored.repairCost());
        assertEquals(4, Byte.toUnsignedInt(Files.readAllBytes(
                temporaryDirectory.resolve("enchanted-player.nxi"))[4]));
    }

    @Test
    void enchantedStacksDoNotMergeWithUnenchantedStacks() {
        ItemStack plain = new ItemStack("minecraft:diamond_sword", 1, 1);
        ItemStack enchanted = new ItemStack("minecraft:diamond_sword", 1, 1,
                java.util.Map.of("minecraft:sharpness", 1));
        assertFalse(plain.canStackWith(enchanted));
    }

}
