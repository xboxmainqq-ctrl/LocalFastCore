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
    void movesItemsPartiallyAndMergesOnlyCompatibleStacks() {
        PlayerInventory inventory = new PlayerInventory(9);
        inventory.setSlot(0, new ItemStack("nexora:mana_crystal", 20, 64));
        inventory.setSlot(1, new ItemStack("nexora:mana_crystal", 60, 64));

        assertEquals(4, inventory.move(0, 1, 10));
        assertEquals(16, inventory.getSlot(0).orElseThrow().count());
        assertEquals(64, inventory.getSlot(1).orElseThrow().count());
        assertEquals(0, inventory.move(0, 1, 10));
        assertThrows(IllegalArgumentException.class, () -> inventory.move(0, 2, 0));
    }

    @Test
    void movesIntoEmptySlotAndNeverOverwritesIncompatibleItems() {
        PlayerInventory inventory = new PlayerInventory(9);
        inventory.setSlot(0, new ItemStack("nexora:arcane_dust", 8, 64));
        inventory.setSlot(1, new ItemStack("nexora:mana_crystal", 3, 64));

        assertEquals(5, inventory.move(0, 2, 5));
        assertEquals(3, inventory.getSlot(0).orElseThrow().count());
        assertEquals(5, inventory.getSlot(2).orElseThrow().count());
        assertEquals(0, inventory.move(0, 1, 3));
        assertEquals("nexora:mana_crystal", inventory.getSlot(1).orElseThrow().itemId());
        assertEquals(3, inventory.getSlot(0).orElseThrow().count());
    }

    @Test
    void swapsSlotsWithoutChangingItemCounts() {
        PlayerInventory inventory = new PlayerInventory(9);
        inventory.setSlot(0, new ItemStack("nexora:arcane_dust", 8, 64));
        inventory.setSlot(1, new ItemStack("nexora:mana_crystal", 3, 64));

        inventory.swapSlots(0, 1);
        assertEquals("nexora:mana_crystal", inventory.getSlot(0).orElseThrow().itemId());
        assertEquals("nexora:arcane_dust", inventory.getSlot(1).orElseThrow().itemId());
        assertEquals(11, inventory.count("nexora:arcane_dust")
                + inventory.count("nexora:mana_crystal"));
    }

    @Test
    void rejectsInvalidMoveArgumentsAndSlotIndexes() {
        PlayerInventory inventory = new PlayerInventory(9);
        assertThrows(IllegalArgumentException.class, () -> inventory.move(0, 1, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> inventory.move(-1, 1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> inventory.swapSlots(0, 9));
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

    @Test
    void restoresInventorySnapshotAndSelectedHotbarSlot() {
        PlayerInventory inventory = new PlayerInventory();
        inventory.setSlot(2, new ItemStack("minecraft:diamond", 5, 64));
        inventory.selectHotbarSlot(2);
        var snapshot = inventory.snapshot();

        inventory.setSlot(2, null);
        inventory.setSlot(3, new ItemStack("minecraft:stone", 12, 64));
        inventory.selectHotbarSlot(3);
        inventory.restore(snapshot, 2);

        assertEquals(new ItemStack("minecraft:diamond", 5, 64), inventory.getSlot(2).orElseThrow());
        assertTrue(inventory.getSlot(3).isEmpty());
        assertEquals(2, inventory.selectedHotbarSlot());
    }

    @Test
    void rejectsInvalidInventoryRestoreWithoutChangingState() {
        PlayerInventory inventory = new PlayerInventory();
        ItemStack diamond = new ItemStack("minecraft:diamond", 1, 64);
        inventory.setSlot(0, diamond);
        var before = inventory.snapshot();

        assertThrows(IllegalArgumentException.class,
                () -> inventory.restore(java.util.List.of(java.util.Optional.empty()), 0));
        assertThrows(IllegalArgumentException.class,
                () -> inventory.restore(before, 9));

        assertEquals(before, inventory.snapshot());
    }
    @Test
    void rejectsMetadataForgeryInsideAtomicSlotChanges() {
        PlayerInventory inventory = new PlayerInventory(9);
        ItemStack enchanted = new ItemStack("minecraft:diamond_sword", 1, 1,
                java.util.Map.of("minecraft:sharpness", 5), 0);
        ItemStack plain = new ItemStack("minecraft:diamond_sword", 1, 1);
        inventory.setSlot(0, enchanted);
        var before = inventory.snapshot();

        assertFalse(inventory.applySlotChanges(java.util.List.of(
                new PlayerInventory.SlotChange(0, java.util.Optional.of(enchanted),
                        java.util.Optional.empty()),
                new PlayerInventory.SlotChange(1, java.util.Optional.empty(),
                        java.util.Optional.of(plain))
        )));
        assertEquals(before, inventory.snapshot());
    }

    @Test
    void rejectsRepairCostForgeryInsideAtomicSlotChanges() {
        PlayerInventory inventory = new PlayerInventory(9);
        ItemStack repaired = new ItemStack("minecraft:diamond_pickaxe", 1, 1,
                java.util.Map.of(), 7);
        ItemStack unrepaired = new ItemStack("minecraft:diamond_pickaxe", 1, 1,
                java.util.Map.of(), 0);
        inventory.setSlot(0, repaired);
        var before = inventory.snapshot();

        assertFalse(inventory.applySlotChanges(java.util.List.of(
                new PlayerInventory.SlotChange(0, java.util.Optional.of(repaired),
                        java.util.Optional.empty()),
                new PlayerInventory.SlotChange(1, java.util.Optional.empty(),
                        java.util.Optional.of(unrepaired))
        )));
        assertEquals(before, inventory.snapshot());
    }

}
