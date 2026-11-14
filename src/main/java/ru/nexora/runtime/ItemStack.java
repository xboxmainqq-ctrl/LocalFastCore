package ru.nexora.runtime;

import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable stack of one registered item identifier. */
public record ItemStack(String itemId, int count) {
    private static final Pattern ITEM_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    public static final int MAX_STACK_SIZE = 64;

    public ItemStack {
        Objects.requireNonNull(itemId, "itemId");
        if (!ITEM_ID.matcher(itemId).matches() || itemId.length() > 96) {
            throw new IllegalArgumentException("Item identifier must be namespaced and at most 96 bytes");
        }
        if (count < 1 || count > MAX_STACK_SIZE) {
            throw new IllegalArgumentException("Item stack count must be between 1 and 64");
        }
    }
}
