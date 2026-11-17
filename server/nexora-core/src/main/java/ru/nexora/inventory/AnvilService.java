package ru.nexora.inventory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Server-authoritative first-stage anvil rules.
 *
 * This model intentionally stays separate from ItemStack until protocol-2225
 * item component/NBT round-tripping is implemented. Callers must not expose
 * these results as live inventory items before that integration is complete.
 */
public final class AnvilService {
    public static final int MAX_SURVIVAL_COST = 39;

    private static final Map<String, Enchantment> ENCHANTMENTS = createEnchantments();
    private static final Map<String, Set<String>> ITEM_GROUPS = createItemGroups();

    public Outcome combine(EnchantedItem base, EnchantedItem sacrifice, int availableLevels) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(sacrifice, "sacrifice");
        if (availableLevels < 0) throw new IllegalArgumentException("availableLevels cannot be negative");
        if (base.itemId().equals("minecraft:enchanted_book")) return Outcome.failure(Status.INVALID_BASE);
        if (!sacrifice.itemId().equals("minecraft:enchanted_book")
                && !base.itemId().equals(sacrifice.itemId())) {
            return Outcome.failure(Status.INCOMPATIBLE_ITEMS);
        }
        if (base.enchantments().isEmpty() && sacrifice.enchantments().isEmpty()) {
            return Outcome.failure(Status.NOTHING_TO_COMBINE);
        }

        Map<String, Integer> merged = new LinkedHashMap<>(base.enchantments());
        int enchantmentCost = 0;
        for (var entry : sacrifice.enchantments().entrySet()) {
            Enchantment enchantment = ENCHANTMENTS.get(entry.getKey());
            int incomingLevel = entry.getValue();
            if (enchantment == null || !enchantment.supports(base.itemId())) {
                return Outcome.failure(Status.INCOMPATIBLE_ENCHANTMENT);
            }
            for (String existingId : merged.keySet()) {
                if (!existingId.equals(entry.getKey())
                        && enchantment.conflictsWith().contains(existingId)) {
                    return Outcome.failure(Status.CONFLICTING_ENCHANTMENTS);
                }
            }

            int currentLevel = merged.getOrDefault(entry.getKey(), 0);
            int resultLevel = currentLevel == incomingLevel && currentLevel > 0
                    ? Math.min(enchantment.maxLevel(), currentLevel + 1)
                    : Math.max(currentLevel, incomingLevel);
            if (resultLevel > currentLevel) {
                merged.put(entry.getKey(), resultLevel);
                enchantmentCost += enchantment.anvilCost() * resultLevel;
            }
        }
        if (merged.equals(base.enchantments())) return Outcome.failure(Status.NO_CHANGE);

        int cost = base.repairCost() + sacrifice.repairCost() + enchantmentCost;
        if (cost > MAX_SURVIVAL_COST) return Outcome.failure(Status.TOO_EXPENSIVE);
        if (cost > availableLevels) return Outcome.failure(Status.INSUFFICIENT_EXPERIENCE);

        int nextRepairCost = Math.min(31, Math.max(base.repairCost(), sacrifice.repairCost()) * 2 + 1);
        EnchantedItem result = new EnchantedItem(base.itemId(), 1, merged, nextRepairCost);
        return new Outcome(Status.SUCCESS, result, cost);
    }

    /** Combines actual inventory stacks; the caller must persist the returned stack and XP atomically. */
    public Outcome combine(ItemStack base, ItemStack sacrifice, int availableLevels) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(sacrifice, "sacrifice");
        if (base.count() != 1 || sacrifice.count() != 1) {
            return Outcome.failure(Status.INCOMPATIBLE_ITEMS);
        }
        Outcome outcome = combine(
                new EnchantedItem(base.itemId(), 1, base.enchantments(), base.repairCost()),
                new EnchantedItem(sacrifice.itemId(), 1, sacrifice.enchantments(), sacrifice.repairCost()),
                availableLevels);
        if (!outcome.successful()) return outcome;
        EnchantedItem result = outcome.result();
        return new Outcome(Status.SUCCESS,
                new EnchantedItem(result.itemId(), 1, result.enchantments(), result.repairCost()),
                outcome.levelCost());
    }

    public static Map<String, Enchantment> enchantments() {
        return ENCHANTMENTS;
    }

    private static Map<String, Enchantment> createEnchantments() {
        Map<String, Enchantment> values = new LinkedHashMap<>();
        register(values, "minecraft:sharpness", 5, 1, "sword", Set.of(
                "minecraft:smite", "minecraft:bane_of_arthropods"));
        register(values, "minecraft:smite", 5, 2, "sword", Set.of(
                "minecraft:sharpness", "minecraft:bane_of_arthropods"));
        register(values, "minecraft:bane_of_arthropods", 5, 2, "sword", Set.of(
                "minecraft:sharpness", "minecraft:smite"));
        register(values, "minecraft:efficiency", 5, 1, "tool", Set.of());
        register(values, "minecraft:unbreaking", 3, 1, "durable", Set.of());
        register(values, "minecraft:fortune", 3, 2, "tool", Set.of("minecraft:silk_touch"));
        register(values, "minecraft:silk_touch", 1, 4, "tool", Set.of("minecraft:fortune"));
        register(values, "minecraft:protection", 4, 1, "armor", Set.of(
                "minecraft:fire_protection", "minecraft:blast_protection", "minecraft:projectile_protection"));
        register(values, "minecraft:fire_protection", 4, 2, "armor", Set.of(
                "minecraft:protection", "minecraft:blast_protection", "minecraft:projectile_protection"));
        register(values, "minecraft:blast_protection", 4, 2, "armor", Set.of(
                "minecraft:protection", "minecraft:fire_protection", "minecraft:projectile_protection"));
        register(values, "minecraft:projectile_protection", 4, 2, "armor", Set.of(
                "minecraft:protection", "minecraft:fire_protection", "minecraft:blast_protection"));
        register(values, "minecraft:power", 5, 1, "bow", Set.of());
        register(values, "minecraft:punch", 2, 2, "bow", Set.of());
        register(values, "minecraft:flame", 1, 2, "bow", Set.of());
        register(values, "minecraft:infinity", 1, 4, "bow", Set.of("minecraft:mending"));
        register(values, "minecraft:mending", 1, 4, "durable", Set.of("minecraft:infinity"));
        register(values, "minecraft:loyalty", 3, 1, "trident", Set.of("minecraft:riptide"));
        register(values, "minecraft:riptide", 3, 2, "trident", Set.of(
                "minecraft:loyalty", "minecraft:channeling"));
        register(values, "minecraft:channeling", 1, 4, "trident", Set.of("minecraft:riptide"));
        register(values, "minecraft:multishot", 1, 2, "crossbow", Set.of("minecraft:piercing"));
        register(values, "minecraft:piercing", 4, 1, "crossbow", Set.of("minecraft:multishot"));
        register(values, "minecraft:feather_falling", 4, 1, "boots", Set.of());
        register(values, "minecraft:depth_strider", 3, 2, "boots", Set.of("minecraft:frost_walker"));
        register(values, "minecraft:frost_walker", 2, 2, "boots", Set.of("minecraft:depth_strider"));
        return Collections.unmodifiableMap(values);
    }

    private static void register(Map<String, Enchantment> values, String id, int maxLevel,
                                 int anvilCost, String group, Set<String> conflicts) {
        values.put(id, new Enchantment(id, maxLevel, anvilCost, group, conflicts));
    }

    private static Map<String, Set<String>> createItemGroups() {
        Map<String, Set<String>> groups = new LinkedHashMap<>();
        add(groups, "sword", "wooden_sword", "stone_sword", "iron_sword", "golden_sword",
                "diamond_sword", "netherite_sword");
        add(groups, "tool", "wooden_pickaxe", "stone_pickaxe", "iron_pickaxe", "golden_pickaxe",
                "diamond_pickaxe", "netherite_pickaxe", "wooden_axe", "stone_axe", "iron_axe",
                "golden_axe", "diamond_axe", "netherite_axe", "wooden_shovel", "stone_shovel",
                "iron_shovel", "golden_shovel", "diamond_shovel", "netherite_shovel",
                "wooden_hoe", "stone_hoe", "iron_hoe", "golden_hoe", "diamond_hoe", "netherite_hoe");
        add(groups, "armor", "leather_helmet", "leather_chestplate", "leather_leggings", "leather_boots",
                "chainmail_helmet", "chainmail_chestplate", "chainmail_leggings", "chainmail_boots",
                "iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots",
                "golden_helmet", "golden_chestplate", "golden_leggings", "golden_boots",
                "diamond_helmet", "diamond_chestplate", "diamond_leggings", "diamond_boots",
                "netherite_helmet", "netherite_chestplate", "netherite_leggings", "netherite_boots",
                "turtle_helmet");
        add(groups, "boots", "leather_boots", "chainmail_boots", "iron_boots", "golden_boots",
                "diamond_boots", "netherite_boots");
        add(groups, "bow", "bow");
        add(groups, "crossbow", "crossbow");
        add(groups, "trident", "trident");
        add(groups, "durable", "wooden_sword", "stone_sword", "iron_sword", "golden_sword",
                "diamond_sword", "netherite_sword", "wooden_pickaxe", "stone_pickaxe", "iron_pickaxe",
                "golden_pickaxe", "diamond_pickaxe", "netherite_pickaxe", "wooden_axe", "stone_axe",
                "iron_axe", "golden_axe", "diamond_axe", "netherite_axe", "wooden_shovel",
                "stone_shovel", "iron_shovel", "golden_shovel", "diamond_shovel", "netherite_shovel",
                "wooden_hoe", "stone_hoe", "iron_hoe", "golden_hoe", "diamond_hoe", "netherite_hoe",
                "bow", "crossbow", "trident", "shield", "elytra", "fishing_rod",
                "flint_and_steel", "shears", "carrot_on_a_stick", "warped_fungus_on_a_stick",
                "brush", "mace", "leather_helmet", "leather_chestplate", "leather_leggings",
                "leather_boots", "chainmail_helmet", "chainmail_chestplate", "chainmail_leggings",
                "chainmail_boots", "iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots",
                "golden_helmet", "golden_chestplate", "golden_leggings", "golden_boots",
                "diamond_helmet", "diamond_chestplate", "diamond_leggings", "diamond_boots",
                "netherite_helmet", "netherite_chestplate", "netherite_leggings", "netherite_boots",
                "turtle_helmet");
        return Collections.unmodifiableMap(groups);
    }

    private static void add(Map<String, Set<String>> groups, String group, String... itemPaths) {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        for (String path : itemPaths) ids.add("minecraft:" + path);
        groups.put(group, Set.copyOf(ids));
    }

    public record Enchantment(String id, int maxLevel, int anvilCost,
                              String itemGroup, Set<String> conflictsWith) {
        public Enchantment {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(itemGroup, "itemGroup");
            conflictsWith = Set.copyOf(Objects.requireNonNull(conflictsWith, "conflictsWith"));
            if (maxLevel < 1 || maxLevel > 10 || anvilCost < 1 || anvilCost > 10) {
                throw new IllegalArgumentException("Invalid enchantment definition");
            }
        }

        public boolean supports(String itemId) {
            return "minecraft:enchanted_book".equals(itemId)
                    || ITEM_GROUPS.getOrDefault(itemGroup, Set.of()).contains(itemId);
        }
    }

    public record EnchantedItem(String itemId, int count, Map<String, Integer> enchantments, int repairCost) {
        public EnchantedItem {
            Objects.requireNonNull(itemId, "itemId");
            if (!itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || count != 1
                    || repairCost < 0 || repairCost > 31) {
                throw new IllegalArgumentException("Invalid anvil item");
            }
            Map<String, Integer> copy = new LinkedHashMap<>();
            for (var entry : Objects.requireNonNull(enchantments, "enchantments").entrySet()) {
                Enchantment definition = ENCHANTMENTS.get(entry.getKey());
                if (definition == null || entry.getValue() == null
                        || entry.getValue() < 1 || entry.getValue() > definition.maxLevel()) {
                    throw new IllegalArgumentException("Unknown enchantment or invalid level: " + entry.getKey());
                }
                if (!definition.supports(itemId)) {
                    throw new IllegalArgumentException("Enchantment does not apply to " + itemId + ": " + entry.getKey());
                }
                copy.put(entry.getKey(), entry.getValue());
            }
            enchantments = Collections.unmodifiableMap(copy);
        }
    }

    public enum Status {
        SUCCESS, INVALID_BASE, INCOMPATIBLE_ITEMS, NOTHING_TO_COMBINE,
        INCOMPATIBLE_ENCHANTMENT, CONFLICTING_ENCHANTMENTS, NO_CHANGE,
        TOO_EXPENSIVE, INSUFFICIENT_EXPERIENCE
    }

    public record Outcome(Status status, EnchantedItem result, int levelCost) {
        public Outcome {
            Objects.requireNonNull(status, "status");
            if (levelCost < 0 || (status == Status.SUCCESS) != (result != null)) {
                throw new IllegalArgumentException("Invalid anvil outcome");
            }
        }

        static Outcome failure(Status status) {
            return new Outcome(status, null, 0);
        }

        public boolean successful() {
            return status == Status.SUCCESS;
        }
    }
}
