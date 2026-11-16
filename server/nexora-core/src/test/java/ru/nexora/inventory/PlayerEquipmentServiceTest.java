package ru.nexora.inventory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlayerEquipmentServiceTest {
    private final PlayerEquipmentService service = new PlayerEquipmentService();

    private final EquipmentSlotRegistry registry = EquipmentSlotRegistry.builder()
            .register("nexora:ember_staff", EquipmentSlot.MAGIC_STAFF)
            .register("nexora:power_ring", EquipmentSlot.RING_LEFT, EquipmentSlot.RING_RIGHT)
            .register("nexora:moon_amulet", EquipmentSlot.AMULET)
            .register("nexora:luck_talisman", EquipmentSlot.TALISMAN)
            .register("nexora:ancient_eye", EquipmentSlot.ARTIFACT_LEFT, EquipmentSlot.ARTIFACT_RIGHT)
            .build();

    @Test
    void equipsOnlyRegisteredItemIntoCompatibleSlotAndConservesIt() {
        PlayerInventory inventory = new PlayerInventory();
        PlayerEquipment equipment = new PlayerEquipment();
        ItemStack staff = new ItemStack("nexora:ember_staff", 1, 1);
        inventory.setSlot(4, staff);

        assertEquals(PlayerEquipmentService.Result.EQUIPPED,
                service.equipFromInventory(inventory, equipment, EquipmentSlot.MAGIC_STAFF, 4, registry));
        assertTrue(inventory.getSlot(4).isEmpty());
        assertEquals(staff, equipment.magicSlot(EquipmentSlot.MAGIC_STAFF).orElseThrow());
        assertEquals(1, equipment.magicSnapshot().stream().filter(java.util.Optional::isPresent).count());
    }

    @Test
    void rejectsWrongSlotUnknownItemAndStackedEquipmentWithoutMutation() {
        PlayerInventory inventory = new PlayerInventory();
        PlayerEquipment equipment = new PlayerEquipment();
        inventory.setSlot(0, new ItemStack("nexora:ember_staff", 1, 1));
        inventory.setSlot(1, new ItemStack("nexora:unknown_relic", 1, 1));
        inventory.setSlot(2, new ItemStack("nexora:power_ring", 2, 16));

        assertEquals(PlayerEquipmentService.Result.INCOMPATIBLE_ITEM,
                service.equipFromInventory(inventory, equipment, EquipmentSlot.RING_LEFT, 0, registry));
        assertEquals(PlayerEquipmentService.Result.INCOMPATIBLE_ITEM,
                service.equipFromInventory(inventory, equipment, EquipmentSlot.TALISMAN, 1, registry));
        assertEquals(PlayerEquipmentService.Result.NON_SINGLE_ITEM,
                service.equipFromInventory(inventory, equipment, EquipmentSlot.RING_LEFT, 2, registry));
        assertTrue(equipment.magicSnapshot().stream().allMatch(java.util.Optional::isEmpty));
        assertTrue(inventory.getSlot(0).isPresent());
        assertTrue(inventory.getSlot(1).isPresent());
        assertTrue(inventory.getSlot(2).isPresent());
    }

    @Test
    void refusesOccupiedSlotAndInvalidSourceWithoutChangingEquipment() {
        PlayerInventory inventory = new PlayerInventory();
        PlayerEquipment equipment = new PlayerEquipment();
        ItemStack first = new ItemStack("nexora:ember_staff", 1, 1);
        inventory.setSlot(0, first);
        equipment.setMagicSlot(EquipmentSlot.MAGIC_STAFF, first);

        assertEquals(PlayerEquipmentService.Result.EQUIPMENT_SLOT_OCCUPIED,
                service.equipFromInventory(inventory, equipment, EquipmentSlot.MAGIC_STAFF, 0, registry));
        assertEquals(PlayerEquipmentService.Result.INVALID_INVENTORY_SLOT,
                service.equipFromInventory(inventory, equipment, EquipmentSlot.MAGIC_STAFF, 99, registry));
        assertEquals(PlayerEquipmentService.Result.EMPTY_INVENTORY_SLOT,
                service.equipFromInventory(inventory, equipment, EquipmentSlot.MAGIC_STAFF, 1, registry));
        assertEquals(first, equipment.magicSlot(EquipmentSlot.MAGIC_STAFF).orElseThrow());
        assertEquals(first, inventory.getSlot(0).orElseThrow());
    }

    @Test
    void unequipsIntoInventoryAndKeepsEquipmentWhenInventoryIsFull() {
        PlayerInventory inventory = new PlayerInventory(9);
        PlayerEquipment equipment = new PlayerEquipment();
        ItemStack ring = new ItemStack("nexora:power_ring", 1, 1);
        equipment.setMagicSlot(EquipmentSlot.RING_LEFT, ring);

        assertEquals(PlayerEquipmentService.Result.UNEQUIPPED,
                service.unequipToInventory(inventory, equipment, EquipmentSlot.RING_LEFT));
        assertTrue(equipment.magicSlot(EquipmentSlot.RING_LEFT).isEmpty());
        assertEquals(ring, inventory.getSlot(0).orElseThrow());

        equipment.setMagicSlot(EquipmentSlot.RING_LEFT, ring);
        for (int i = 0; i < inventory.size(); i++) {
            inventory.setSlot(i, new ItemStack("nexora:filler_" + i, 1, 1));
        }
        assertEquals(PlayerEquipmentService.Result.INVENTORY_FULL,
                service.unequipToInventory(inventory, equipment, EquipmentSlot.RING_LEFT));
        assertEquals(ring, equipment.magicSlot(EquipmentSlot.RING_LEFT).orElseThrow());
        assertEquals(9, inventory.snapshot().stream().filter(java.util.Optional::isPresent).count());
    }

    @Test
    void rejectsDuplicateItemRegistrationAndEmptySlotLists() {
        assertThrows(IllegalArgumentException.class, () -> EquipmentSlotRegistry.builder()
                .register("nexora:duplicate", EquipmentSlot.RING_LEFT)
                .register("nexora:duplicate", EquipmentSlot.RING_RIGHT));
        assertThrows(IllegalArgumentException.class,
                () -> EquipmentSlotRegistry.builder().register("nexora:empty"));
        assertThrows(IllegalArgumentException.class,
                () -> EquipmentSlotRegistry.builder().register("bad-id", EquipmentSlot.AMULET));
    }
}
