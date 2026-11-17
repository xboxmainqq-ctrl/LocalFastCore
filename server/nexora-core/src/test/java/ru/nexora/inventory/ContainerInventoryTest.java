package ru.nexora.inventory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ContainerInventoryTest {
    @TempDir Path temporaryDirectory;

    @Test
    void transfersItemsBetweenPlayerAndContainerWithoutDuplication() {
        PlayerInventory player = new PlayerInventory();
        ContainerInventory chest = new ContainerInventory();
        player.setSlot(0, new ItemStack("minecraft:stone", 32, 64));

        assertEquals(12, chest.moveFrom(player, 0, 4, 12));
        assertEquals(new ItemStack("minecraft:stone", 20, 64), player.getSlot(0).orElseThrow());
        assertEquals(new ItemStack("minecraft:stone", 12, 64), chest.getSlot(4).orElseThrow());

        assertEquals(7, chest.moveTo(player, 4, 1, 7));
        assertEquals(new ItemStack("minecraft:stone", 5, 64), chest.getSlot(4).orElseThrow());
        assertEquals(new ItemStack("minecraft:stone", 7, 64), player.getSlot(1).orElseThrow());
    }

    @Test
    void rejectsIncompatibleDestinationAndDoesNotMutateEitherInventory() {
        PlayerInventory player = new PlayerInventory();
        ContainerInventory chest = new ContainerInventory();
        player.setSlot(0, new ItemStack("minecraft:dirt", 8, 64));
        chest.setSlot(0, new ItemStack("minecraft:stone", 3, 64));

        assertEquals(0, chest.moveFrom(player, 0, 0, 8));
        assertEquals(new ItemStack("minecraft:dirt", 8, 64), player.getSlot(0).orElseThrow());
        assertEquals(new ItemStack("minecraft:stone", 3, 64), chest.getSlot(0).orElseThrow());
    }

    @Test
    void appliesOnlyConservingNonStaleSlotChanges() {
        ContainerInventory chest = new ContainerInventory();
        chest.setSlot(0, new ItemStack("minecraft:stone", 10, 64));
        assertFalse(chest.applySlotChanges(List.of(
                new ContainerInventory.SlotChange(0, Optional.of(new ItemStack("minecraft:dirt", 10, 64)),
                        Optional.empty()))));
        assertTrue(chest.applySlotChanges(List.of(
                new ContainerInventory.SlotChange(0, Optional.of(new ItemStack("minecraft:stone", 10, 64)),
                        Optional.empty()),
                new ContainerInventory.SlotChange(1, Optional.empty(),
                        Optional.of(new ItemStack("minecraft:stone", 10, 64))))));
        assertTrue(chest.getSlot(0).isEmpty());
        assertEquals(10, chest.getSlot(1).orElseThrow().count());
    }

    @Test
    void persistsContainerByWorldCoordinates() throws Exception {
        ContainerInventoryStorage storage = new ContainerInventoryStorage(temporaryDirectory, "NEXORA");
        ContainerInventory chest = new ContainerInventory();
        chest.setSlot(0, new ItemStack("minecraft:diamond", 3, 64));
        storage.save(-4, 72, 9, chest);

        ContainerInventory loaded = storage.load(-4, 72, 9).orElseThrow();
        assertEquals(27, loaded.size());
        assertEquals(new ItemStack("minecraft:diamond", 3, 64), loaded.getSlot(0).orElseThrow());
        assertTrue(storage.load(0, 64, 0).isEmpty());
    }

    @Test
    void rejectsCorruptedContainerAndUnsafeWorldNames() throws Exception {
        ContainerInventoryStorage storage = new ContainerInventoryStorage(temporaryDirectory, "NEXORA");
        storage.save(1, 2, 3, new ContainerInventory());
        Path file = temporaryDirectory.resolve("NEXORA/containers/1_2_3.nxc");
        byte[] bytes = Files.readAllBytes(file);
        bytes[5] ^= 1;
        Files.write(file, bytes);
        assertThrows(IOException.class, () -> storage.load(1, 2, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new ContainerInventoryStorage(temporaryDirectory, "../outside"));
    }
}
