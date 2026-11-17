package ru.nexora.inventory;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;

/** Vanilla-style durability limits and Unbreaking-aware server-side wear. */
public final class ItemDurabilityService {
    private static final Map<String, Integer> MATERIAL_DURABILITY = Map.of(
            "wooden", 59,
            "golden", 32,
            "stone", 131,
            "copper", 190,
            "iron", 250,
            "diamond", 1561,
            "netherite", 2031
    );
    private static final Map<String, Integer> ARMOR_BASE = Map.of(
            "leather", 5,
            "chainmail", 15,
            "copper", 15,
            "iron", 15,
            "golden", 7,
            "diamond", 33,
            "netherite", 37
    );

    private ItemDurabilityService() {
    }

    /** Returns the maximum damage for supported damageable vanilla items. */
    public static OptionalIntValue maximumDurability(ItemStack stack) {
        if (stack == null) return OptionalIntValue.NONE;
        String id = stack.itemId();
        String name = id.substring(id.indexOf(':') + 1);

        if (name.equals("turtle_helmet")) return OptionalIntValue.of(275);
        if (name.equals("elytra")) return OptionalIntValue.of(432);
        if (name.equals("shield")) return OptionalIntValue.of(336);
        if (name.equals("bow")) return OptionalIntValue.of(384);
        if (name.equals("crossbow")) return OptionalIntValue.of(465);
        if (name.equals("trident")) return OptionalIntValue.of(250);
        if (name.equals("fishing_rod") || name.equals("flint_and_steel")) return OptionalIntValue.of(64);
        if (name.equals("shears")) return OptionalIntValue.of(238);
        if (name.equals("brush")) return OptionalIntValue.of(64);
        if (name.equals("mace")) return OptionalIntValue.of(500);

        for (var material : MATERIAL_DURABILITY.entrySet()) {
            String prefix = material.getKey() + "_";
            if (name.startsWith(prefix)) {
                String tool = name.substring(prefix.length());
                if (tool.equals("spear") || tool.equals("sword") || tool.equals("pickaxe")
                        || tool.equals("axe") || tool.equals("shovel") || tool.equals("hoe")) {
                    return OptionalIntValue.of(material.getValue());
                }
            }
        }

        for (var material : ARMOR_BASE.entrySet()) {
            String prefix = material.getKey() + "_";
            if (!name.startsWith(prefix)) continue;
            String piece = name.substring(prefix.length());
            int multiplier = switch (piece) {
                case "helmet" -> 11;
                case "chestplate" -> 16;
                case "leggings" -> 15;
                case "boots" -> 13;
                default -> 0;
            };
            if (multiplier != 0) return OptionalIntValue.of(material.getValue() * multiplier);
        }
        return OptionalIntValue.NONE;
    }

    /**
     * Applies wear with the Unbreaking probability. Empty means the item broke;
     * a present unchanged stack means the item is not damageable or Unbreaking prevented wear.
     */
    public static Optional<ItemStack> applyWear(ItemStack stack, int points, Random random) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(random, "random");
        if (points < 1 || points > 65_535) throw new IllegalArgumentException("points must be 1..65535");
        OptionalIntValue maximum = maximumDurability(stack);
        if (!maximum.present()) return Optional.of(stack);

        int unbreaking = stack.enchantments().getOrDefault("minecraft:unbreaking", 0);
        int applied = 0;
        for (int i = 0; i < points; i++) {
            if (unbreaking == 0 || random.nextInt(unbreaking + 1) == 0) applied++;
        }
        if (applied == 0) return Optional.of(stack);
        int nextDamage = stack.durabilityDamage() + applied;
        if (nextDamage >= maximum.value()) return Optional.empty();
        return Optional.of(stack.withDurabilityDamage(nextDamage));
    }

    /** Tiny value type avoids exposing OptionalInt's mutable-looking primitive API to callers. */
    public record OptionalIntValue(boolean present, int value) {
        private static final OptionalIntValue NONE = new OptionalIntValue(false, 0);
        public OptionalIntValue {
            if (present && value < 1) throw new IllegalArgumentException("Durability must be positive");
            if (!present && value != 0) throw new IllegalArgumentException("Absent durability must be zero");
        }
        public static OptionalIntValue of(int value) {
            return new OptionalIntValue(true, value);
        }
    }
}
