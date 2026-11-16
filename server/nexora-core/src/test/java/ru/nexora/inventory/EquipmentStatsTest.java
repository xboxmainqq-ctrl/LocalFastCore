package ru.nexora.inventory;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EquipmentStatsTest {
    @Test
    void sumsAttributesFromAllEquippedMagicalItems() {
        EquipmentSlotRegistry registry = EquipmentSlotRegistry.builder()
                .register("nexora:ember_staff", Map.of(
                        EquipmentAttribute.MAGIC_POWER, 12.5,
                        EquipmentAttribute.MAX_MANA, 20.0,
                        EquipmentAttribute.COOLDOWN_REDUCTION, 0.05),
                        EquipmentSlot.MAGIC_STAFF)
                .register("nexora:power_ring", Map.of(
                        EquipmentAttribute.MAGIC_POWER, 4.0,
                        EquipmentAttribute.MAX_MANA, 5.0,
                        EquipmentAttribute.FIRE_RESISTANCE, 0.15),
                        EquipmentSlot.RING_LEFT, EquipmentSlot.RING_RIGHT)
                .register("nexora:moon_amulet", Map.of(
                        EquipmentAttribute.MANA_REGENERATION, 2.0),
                        EquipmentSlot.AMULET)
                .build();

        PlayerEquipment equipment = new PlayerEquipment();
        equipment.setMagicSlot(EquipmentSlot.MAGIC_STAFF,
                new ItemStack("nexora:ember_staff", 1, 1));
        equipment.setMagicSlot(EquipmentSlot.RING_LEFT,
                new ItemStack("nexora:power_ring", 1, 1));
        equipment.setMagicSlot(EquipmentSlot.AMULET,
                new ItemStack("nexora:moon_amulet", 1, 1));

        EquipmentStats stats = EquipmentStats.calculate(equipment, registry);
        assertEquals(16.5, stats.value(EquipmentAttribute.MAGIC_POWER), 0.0001);
        assertEquals(25.0, stats.value(EquipmentAttribute.MAX_MANA), 0.0001);
        assertEquals(2.0, stats.value(EquipmentAttribute.MANA_REGENERATION), 0.0001);
        assertEquals(0.05, stats.value(EquipmentAttribute.COOLDOWN_REDUCTION), 0.0001);
        assertEquals(0.15, stats.value(EquipmentAttribute.FIRE_RESISTANCE), 0.0001);
        assertEquals(0.0, stats.value(EquipmentAttribute.ARMOR), 0.0001);
    }

    @Test
    void removingAnItemRemovesItsAttributesFromTheAggregate() {
        EquipmentSlotRegistry registry = EquipmentSlotRegistry.builder()
                .register("nexora:ward_talisman",
                        Map.of(EquipmentAttribute.ARMOR, 3.0,
                                EquipmentAttribute.ICE_RESISTANCE, 0.2),
                        EquipmentSlot.TALISMAN)
                .build();
        PlayerEquipment equipment = new PlayerEquipment();
        equipment.setMagicSlot(EquipmentSlot.TALISMAN,
                new ItemStack("nexora:ward_talisman", 1, 1));

        assertEquals(3.0, EquipmentStats.calculate(equipment, registry)
                .value(EquipmentAttribute.ARMOR), 0.0001);
        equipment.setMagicSlot(EquipmentSlot.TALISMAN, null);
        assertEquals(0.0, EquipmentStats.calculate(equipment, registry)
                .value(EquipmentAttribute.ARMOR), 0.0001);
    }

    @Test
    void defaultNexoraCatalogProvidesServerOwnedMagicBonuses() {
        PlayerEquipment equipment = new PlayerEquipment();
        equipment.setMagicSlot(EquipmentSlot.MAGIC_STAFF,
                new ItemStack("nexora:ember_staff", 1, 1));
        equipment.setMagicSlot(EquipmentSlot.RING_LEFT,
                new ItemStack("nexora:power_ring", 1, 1));
        equipment.setMagicSlot(EquipmentSlot.AMULET,
                new ItemStack("nexora:moon_amulet", 1, 1));
        equipment.setMagicSlot(EquipmentSlot.TALISMAN,
                new ItemStack("nexora:luck_talisman", 1, 1));

        EquipmentStats stats = EquipmentStats.calculate(equipment, NexoraMagicEquipmentCatalog.createRegistry());
        assertEquals(16.0, stats.value(EquipmentAttribute.MAGIC_POWER), 0.0001);
        assertEquals(35.0, stats.value(EquipmentAttribute.MAX_MANA), 0.0001);
        assertEquals(3.0, stats.value(EquipmentAttribute.MANA_REGENERATION), 0.0001);
        assertEquals(0.10, stats.value(EquipmentAttribute.COOLDOWN_REDUCTION), 0.0001);
        assertEquals(0.10, stats.value(EquipmentAttribute.FIRE_RESISTANCE), 0.0001);
    }

    @Test
    void rejectsNonFiniteOrUnreasonablyLargeRegisteredBonuses() {
        assertThrows(IllegalArgumentException.class, () -> EquipmentSlotRegistry.builder()
                .register("nexora:bad_item",
                        Map.of(EquipmentAttribute.MAGIC_POWER, Double.NaN),
                        EquipmentSlot.MAGIC_STAFF));
        assertThrows(IllegalArgumentException.class, () -> EquipmentSlotRegistry.builder()
                .register("nexora:bad_item",
                        Map.of(EquipmentAttribute.MAGIC_POWER, 1_000_001.0),
                        EquipmentSlot.MAGIC_STAFF));
        assertThrows(IllegalArgumentException.class, () -> new EquipmentStats(
                Map.of(EquipmentAttribute.MAGIC_POWER, Double.POSITIVE_INFINITY)));
    }

    @Test
    void includesArmorAndOffhandBonusesInDerivedStats() {
        EquipmentSlotRegistry registry = NexoraMagicEquipmentCatalog.createRegistry();
        PlayerEquipment equipment = new PlayerEquipment();
        equipment.setArmor(0, new ItemStack("minecraft:iron_helmet", 1, 1));
        equipment.setArmor(1, new ItemStack("minecraft:iron_chestplate", 1, 1));
        equipment.setArmor(2, new ItemStack("minecraft:iron_leggings", 1, 1));
        equipment.setArmor(3, new ItemStack("minecraft:iron_boots", 1, 1));
        equipment.setOffhand(new ItemStack("minecraft:shield", 1, 1));

        EquipmentStats stats = EquipmentStats.calculate(equipment, registry);
        assertEquals(15.0, stats.value(EquipmentAttribute.ARMOR), 0.0001);
    }

    @Test
    void rejectsDuplicateAttributeDefinitionsAcrossMagicalAndArmorItems() {
        assertThrows(IllegalArgumentException.class, () -> EquipmentSlotRegistry.builder()
                .registerAttributes("minecraft:iron_helmet",
                        Map.of(EquipmentAttribute.ARMOR, 2.0))
                .register("minecraft:iron_helmet", EquipmentSlot.MAGIC_STAFF));
        assertThrows(IllegalArgumentException.class, () -> EquipmentSlotRegistry.builder()
                .register("nexora:duplicate", EquipmentSlot.MAGIC_STAFF)
                .registerAttributes("nexora:duplicate",
                        Map.of(EquipmentAttribute.MAGIC_POWER, 1.0)));
    }
}
