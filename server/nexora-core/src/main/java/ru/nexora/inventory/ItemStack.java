package ru.nexora.inventory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable server-side item stack with persistent enchantment, anvil and durability metadata. */
public record ItemStack(String itemId, int count, int maxStackSize,
                        Map<String, Integer> enchantments, int repairCost, int durabilityDamage) {
    public ItemStack(String itemId, int count, int maxStackSize) {
        this(itemId, count, maxStackSize, Map.of(), 0, 0);
    }

    public ItemStack(String itemId, int count, int maxStackSize, Map<String, Integer> enchantments) {
        this(itemId, count, maxStackSize, enchantments, 0, 0);
    }

    public ItemStack(String itemId, int count, int maxStackSize,
                     Map<String, Integer> enchantments, int repairCost) {
        this(itemId, count, maxStackSize, enchantments, repairCost, 0);
    }

    public ItemStack {
        Objects.requireNonNull(itemId, "itemId");
        if (!itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("itemId must be a namespaced identifier");
        }
        if (maxStackSize < 1 || maxStackSize > 99) {
            throw new IllegalArgumentException("maxStackSize must be 1..99");
        }
        if (count < 1 || count > maxStackSize) {
            throw new IllegalArgumentException("count must be 1..maxStackSize");
        }
        if (repairCost < 0 || repairCost > 31) {
            throw new IllegalArgumentException("repairCost must be 0..31");
        }
        if (durabilityDamage < 0 || durabilityDamage > 65_535) {
            throw new IllegalArgumentException("durabilityDamage must be 0..65535");
        }
        Map<String, Integer> copy = new LinkedHashMap<>();
        for (var entry : Objects.requireNonNull(enchantments, "enchantments").entrySet()) {
            Objects.requireNonNull(entry.getKey(), "enchantment id");
            Integer level = entry.getValue();
            if (!entry.getKey().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                    || level == null || level < 1 || level > 10) {
                throw new IllegalArgumentException("Invalid enchantment entry: " + entry.getKey());
            }
            copy.put(entry.getKey(), level);
        }
        if (copy.size() > 32) throw new IllegalArgumentException("Too many enchantments on item");
        enchantments = Collections.unmodifiableMap(copy);
    }

    public ItemStack withCount(int newCount) {
        return new ItemStack(itemId, newCount, maxStackSize, enchantments, repairCost, durabilityDamage);
    }

    public ItemStack withEnchantments(Map<String, Integer> newEnchantments, int newRepairCost) {
        return new ItemStack(itemId, count, maxStackSize, newEnchantments, newRepairCost, durabilityDamage);
    }

    public ItemStack withDurabilityDamage(int newDamage) {
        return new ItemStack(itemId, count, maxStackSize, enchantments, repairCost, newDamage);
    }

    public boolean canStackWith(ItemStack other) {
        return other != null && itemId.equals(other.itemId)
                && maxStackSize == other.maxStackSize
                && enchantments.equals(other.enchantments)
                && repairCost == other.repairCost
                && durabilityDamage == other.durabilityDamage;
    }
}
