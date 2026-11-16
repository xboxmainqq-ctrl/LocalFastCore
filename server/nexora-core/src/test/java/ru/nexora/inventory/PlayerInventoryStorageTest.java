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
}
