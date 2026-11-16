package ru.nexora.inventory;

/** Verified vanilla armor item-to-slot compatibility for protocol 2225. */
public final class VanillaArmorSlotCatalog {
    private VanillaArmorSlotCatalog() {
    }

    public static ArmorSlotRegistry createRegistry() {
        ArmorSlotRegistry.Builder builder = ArmorSlotRegistry.builder();
        for (String material : new String[] {"leather", "chainmail", "iron", "golden", "diamond", "netherite"}) {
            builder.register("minecraft:" + material + "_helmet", ArmorSlot.HEAD);
            builder.register("minecraft:" + material + "_chestplate", ArmorSlot.CHEST);
            builder.register("minecraft:" + material + "_leggings", ArmorSlot.LEGS);
            builder.register("minecraft:" + material + "_boots", ArmorSlot.FEET);
        }
        builder.register("minecraft:turtle_helmet", ArmorSlot.HEAD);
        builder.register("minecraft:elytra", ArmorSlot.CHEST);
        return builder.build();
    }
}
