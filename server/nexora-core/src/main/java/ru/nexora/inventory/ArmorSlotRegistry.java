package ru.nexora.inventory;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable authoritative mapping from item identifiers to legal armor positions. */
public final class ArmorSlotRegistry {
    private final Map<String, ArmorSlot> slots;

    private ArmorSlotRegistry(Map<String, ArmorSlot> slots) {
        this.slots = Collections.unmodifiableMap(new HashMap<>(slots));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static ArmorSlotRegistry empty() {
        return new ArmorSlotRegistry(Map.of());
    }

    public boolean allows(ItemStack stack, ArmorSlot slot) {
        return stack != null && stack.count() == 1 && slot != null
                && slots.get(stack.itemId()) == slot;
    }

    public static final class Builder {
        private final Map<String, ArmorSlot> slots = new HashMap<>();

        public Builder register(String itemId, ArmorSlot slot) {
            Objects.requireNonNull(itemId, "itemId");
            Objects.requireNonNull(slot, "slot");
            if (!itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                throw new IllegalArgumentException("Invalid namespaced armor item ID");
            }
            if (slots.putIfAbsent(itemId, slot) != null) {
                throw new IllegalArgumentException("Armor item is already registered: " + itemId);
            }
            return this;
        }

        public ArmorSlotRegistry build() {
            return new ArmorSlotRegistry(slots);
        }
    }
}
