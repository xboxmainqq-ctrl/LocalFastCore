package ru.nexora.inventory;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable server-authoritative mapping from item identifiers to legal magical equipment slots.
 * Item definitions are registered by NEXORA content modules; clients cannot provide these rules.
 */
public final class EquipmentSlotRegistry {
    private final Map<String, Set<EquipmentSlot>> allowedSlots;

    private EquipmentSlotRegistry(Map<String, Set<EquipmentSlot>> allowedSlots) {
        Map<String, Set<EquipmentSlot>> copy = new HashMap<>();
        allowedSlots.forEach((itemId, slots) -> copy.put(itemId, Set.copyOf(slots)));
        this.allowedSlots = Collections.unmodifiableMap(copy);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Only single, registered items may occupy a magical equipment slot. */
    public boolean allows(ItemStack stack, EquipmentSlot slot) {
        if (stack == null || stack.count() != 1 || slot == null) return false;
        return allowedSlots.getOrDefault(stack.itemId(), Set.of()).contains(slot);
    }

    public boolean isRegistered(String itemId) {
        return allowedSlots.containsKey(Objects.requireNonNull(itemId, "itemId"));
    }

    public Set<EquipmentSlot> allowedSlots(String itemId) {
        return allowedSlots.getOrDefault(Objects.requireNonNull(itemId, "itemId"), Set.of());
    }

    public static final class Builder {
        private final Map<String, Set<EquipmentSlot>> entries = new HashMap<>();

        public Builder register(String itemId, EquipmentSlot... slots) {
            Objects.requireNonNull(itemId, "itemId");
            if (!itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                throw new IllegalArgumentException("Invalid namespaced equipment item ID");
            }
            Objects.requireNonNull(slots, "slots");
            if (slots.length == 0) throw new IllegalArgumentException("At least one equipment slot is required");
            EnumSet<EquipmentSlot> accepted = EnumSet.noneOf(EquipmentSlot.class);
            for (EquipmentSlot slot : slots) accepted.add(Objects.requireNonNull(slot, "slot"));
            if (entries.putIfAbsent(itemId, accepted) != null) {
                throw new IllegalArgumentException("Equipment item is already registered: " + itemId);
            }
            return this;
        }

        public EquipmentSlotRegistry build() {
            return new EquipmentSlotRegistry(entries);
        }
    }
}
