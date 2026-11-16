package ru.nexora.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FilePlayerInventoryStorageTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsExactSlotsAndStackCounts() throws IOException {
        UUID playerId = UUID.randomUUID();
        FilePlayerInventoryStorage storage =
                new FilePlayerInventoryStorage(temporaryDirectory.resolve("players"));
        PlayerInventory inventory = new PlayerInventory(playerId);
        for (int i = 0; i < 100; i++) assertTrue(inventory.addOne("minecraft:apple"));
        assertTrue(inventory.addOne("minecraft:diamond"));
        assertNotNull(inventory.removeStack(0));

        storage.save(inventory);
        PlayerInventory loaded = storage.load(playerId);

        assertEquals(playerId, loaded.ownerId());
        assertEquals(36, loaded.slotCount());
        assertNull(loaded.stackAt(0));
        assertEquals(new ItemStack("minecraft:apple", 1), loaded.stackAt(1));
        assertEquals(new ItemStack("minecraft:diamond", 1), loaded.stackAt(2));
    }

    @Test
    void persistsAndRestoresNinetyNineItemStacks() throws IOException {
        UUID playerId = UUID.randomUUID();
        FilePlayerInventoryStorage storage =
                new FilePlayerInventoryStorage(temporaryDirectory.resolve("large-stacks"));
        PlayerInventory inventory = new PlayerInventory(playerId);

        for (int i = 0; i < 99; i++) {
            assertTrue(inventory.addOne("minecraft:apple"));
        }
        assertEquals(99, inventory.stackAt(0).count());
        assertTrue(inventory.addOne("minecraft:apple"));
        assertEquals(1, inventory.stackAt(1).count());

        storage.save(inventory);
        PlayerInventory loaded = storage.load(playerId);
        assertEquals(99, loaded.stackAt(0).count());
    }

    @Test
    void returnsAnEmptyInventoryWhenNoSaveExists() throws IOException {
        PlayerInventory loaded = new FilePlayerInventoryStorage(
                temporaryDirectory.resolve("players")).load(UUID.randomUUID());

        assertEquals(0, loaded.occupiedSlots());
        assertEquals(36, loaded.slotCount());
    }

    @Test
    void rejectsCorruptedInventoryChecksum() throws IOException {
        UUID playerId = UUID.randomUUID();
        Path directory = temporaryDirectory.resolve("players");
        FilePlayerInventoryStorage storage = new FilePlayerInventoryStorage(directory);
        PlayerInventory inventory = new PlayerInventory(playerId);
        assertTrue(inventory.addOne("minecraft:apple"));
        storage.save(inventory);

        Path file = directory.resolve(playerId + ".nxi");
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 5] ^= 0x40;
        Files.write(file, bytes);

        assertThrows(IOException.class, () -> storage.load(playerId));
    }

    @Test
    void refusesToPersistInventoriesWithUnexpectedSlotCount() {
        FilePlayerInventoryStorage storage =
                new FilePlayerInventoryStorage(temporaryDirectory.resolve("players"));
        assertThrows(IOException.class,
                () -> storage.save(new PlayerInventory(UUID.randomUUID(), 9)));
    }
}
