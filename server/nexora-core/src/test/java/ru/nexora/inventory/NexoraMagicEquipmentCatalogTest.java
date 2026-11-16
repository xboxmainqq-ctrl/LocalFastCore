package ru.nexora.inventory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NexoraMagicEquipmentCatalogTest {
    @Test
    void registersPlayableVanillaPlaceholderItemsForEveryMagicalSlot() {
        EquipmentSlotRegistry registry = NexoraMagicEquipmentCatalog.createRegistry();
        assertTrue(registry.allows(new ItemStack("minecraft:blaze_rod", 1, 64),
                EquipmentSlot.MAGIC_STAFF));
        assertTrue(registry.allows(new ItemStack("minecraft:gold_nugget", 1, 64),
                EquipmentSlot.RING_LEFT));
        assertTrue(registry.allows(new ItemStack("minecraft:gold_nugget", 1, 64),
                EquipmentSlot.RING_RIGHT));
        assertTrue(registry.allows(new ItemStack("minecraft:nautilus_shell", 1, 64),
                EquipmentSlot.AMULET));
        assertTrue(registry.allows(new ItemStack("minecraft:amethyst_shard", 1, 64),
                EquipmentSlot.TALISMAN));
        assertTrue(registry.allows(new ItemStack("minecraft:heart_of_the_sea", 1, 64),
                EquipmentSlot.ARTIFACT_LEFT));
        assertTrue(registry.allows(new ItemStack("minecraft:ghast_tear", 1, 64),
                EquipmentSlot.ARTIFACT_RIGHT));
    }

    @Test
    void refusesStackedOrWrongCategoryItemsAndCalculatesTheirBonuses() {
        EquipmentSlotRegistry registry = NexoraMagicEquipmentCatalog.createRegistry();
        assertFalse(registry.allows(new ItemStack("minecraft:blaze_rod", 2, 64),
                EquipmentSlot.MAGIC_STAFF));
        assertFalse(registry.allows(new ItemStack("minecraft:blaze_rod", 1, 64),
                EquipmentSlot.AMULET));

        PlayerEquipment equipment = new PlayerEquipment();
        equipment.setMagicSlot(EquipmentSlot.MAGIC_STAFF,
                new ItemStack("minecraft:blaze_rod", 1, 64));
        equipment.setMagicSlot(EquipmentSlot.AMULET,
                new ItemStack("minecraft:nautilus_shell", 1, 64));

        EquipmentStats stats = EquipmentStats.calculate(equipment, registry);
        assertEquals(12.0, stats.value(EquipmentAttribute.MAGIC_POWER), 0.0001);
        assertEquals(30.0, stats.value(EquipmentAttribute.MAX_MANA), 0.0001);
        assertEquals(2.0, stats.value(EquipmentAttribute.MANA_REGENERATION), 0.0001);
    }
}
