package ru.nexora.inventory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlayerInventoryTest {
    @Test
    void mergesStacksBeforeUsingEmptySlotsAndReportsRemainder() {
        PlayerInventory inventory = new PlayerInventory(9);
        inventory.setSlot(0, new ItemStack("nexora:mana_crystal", 60, 64));

        assertEquals(0, inventory.add(new ItemStack("nexora:mana_crystal", 10, 64)));
        assertEquals(64, inventory.getSlot(0).orElseThrow().count());
        assertEquals(6, inventory.getSlot(1).orElseThrow().count());
        assertEquals(70, inventory.count("nexora:mana_crystal"));
    }

    @Test
    void failedRemovalDoesNotPartiallyMutateInventory() {
        PlayerInventory inventory = new PlayerInventory(9);
        inventory.setSlot(0, new ItemStack("nexora:arcane_dust", 3, 64));
        inventory.setSlot(1, new ItemStack("nexora:arcane_dust", 2, 64));

        assertFalse(inventory.remove("nexora:arcane_dust", 6));
        assertEquals(5, inventory.count("nexora:arcane_dust"));
        assertTrue(inventory.remove("nexora:arcane_dust", 4));
        assertEquals(1, inventory.count("nexora:arcane_dust"));
    }

    @Test
    void selectedHotbarSlotAndHeldItemAreConsistent() {
        PlayerInventory inventory = new PlayerInventory();
        inventory.setSlot(2, new ItemStack("nexora:wand", 1, 1));

        inventory.selectHotbarSlot(2);
        assertEquals(2, inventory.selectedHotbarSlot());
        assertEquals("nexora:wand", inventory.heldItem().orElseThrow().itemId());
        assertThrows(IllegalArgumentException.class, () -> inventory.selectHotbarSlot(9));
    }

    @Test
    void snapshotsAreImmutableAndRepresentEmptySlotsExplicitly() {
        PlayerInventory inventory = new PlayerInventory(9);
        var snapshot = inventory.snapshot();
        assertTrue(snapshot.get(0).isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.set(0, java.util.Optional.empty()));
        inventory.setSlot(0, new ItemStack("nexora:wand", 1, 1));
        assertTrue(snapshot.get(0).isEmpty());
    }

    @Test
    void rejectsInvalidItemIdentifiersAndStackSizes() {
        assertThrows(IllegalArgumentException.class, () -> new ItemStack("diamond", 1, 64));
        assertThrows(IllegalArgumentException.class, () -> new ItemStack("nexora:wand", 2, 1));
    }
}
