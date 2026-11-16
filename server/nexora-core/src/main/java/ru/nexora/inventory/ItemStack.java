package ru.nexora.inventory;

import java.util.Objects;

/** Immutable server-side item stack; item identifiers are NEXORA namespaced identifiers. */
public record ItemStack(String itemId, int count, int maxStackSize) {
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
    }

    public ItemStack withCount(int newCount) {
        return new ItemStack(itemId, newCount, maxStackSize);
    }

    public boolean canStackWith(ItemStack other) {
        return other != null && itemId.equals(other.itemId)
                && maxStackSize == other.maxStackSize;
    }
}
