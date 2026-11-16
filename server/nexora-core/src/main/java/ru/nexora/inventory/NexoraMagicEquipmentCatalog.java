package ru.nexora.inventory;

import java.util.Map;

/** First-pass server-owned magical item definitions and additive RPG bonuses. */
public final class NexoraMagicEquipmentCatalog {
    private NexoraMagicEquipmentCatalog() {
    }

    public static EquipmentSlotRegistry createRegistry() {
        return EquipmentSlotRegistry.builder()
                .register("nexora:ember_staff", Map.of(
                        EquipmentAttribute.MAGIC_POWER, 12.0,
                        EquipmentAttribute.MAX_MANA, 20.0,
                        EquipmentAttribute.COOLDOWN_REDUCTION, 0.05),
                        EquipmentSlot.MAGIC_STAFF)
                .register("nexora:power_ring", Map.of(
                        EquipmentAttribute.MAGIC_POWER, 4.0,
                        EquipmentAttribute.MAX_MANA, 5.0,
                        EquipmentAttribute.FIRE_RESISTANCE, 0.10),
                        EquipmentSlot.RING_LEFT, EquipmentSlot.RING_RIGHT)
                .register("nexora:ward_ring", Map.of(
                        EquipmentAttribute.ARMOR, 3.0,
                        EquipmentAttribute.ICE_RESISTANCE, 0.15,
                        EquipmentAttribute.LIGHTNING_RESISTANCE, 0.10),
                        EquipmentSlot.RING_LEFT, EquipmentSlot.RING_RIGHT)
                .register("nexora:moon_amulet", Map.of(
                        EquipmentAttribute.MAX_MANA, 10.0,
                        EquipmentAttribute.MANA_REGENERATION, 2.0),
                        EquipmentSlot.AMULET)
                .register("nexora:luck_talisman", Map.of(
                        EquipmentAttribute.MANA_REGENERATION, 1.0,
                        EquipmentAttribute.COOLDOWN_REDUCTION, 0.05),
                        EquipmentSlot.TALISMAN)
                .register("nexora:ancient_eye", Map.of(
                        EquipmentAttribute.MAGIC_POWER, 8.0,
                        EquipmentAttribute.LIGHTNING_RESISTANCE, 0.15),
                        EquipmentSlot.ARTIFACT_LEFT, EquipmentSlot.ARTIFACT_RIGHT)
                .register("nexora:phoenix_core", Map.of(
                        EquipmentAttribute.MAX_HEALTH, 5.0,
                        EquipmentAttribute.FIRE_RESISTANCE, 0.20),
                        EquipmentSlot.ARTIFACT_LEFT, EquipmentSlot.ARTIFACT_RIGHT)
                .build();
    }
}
