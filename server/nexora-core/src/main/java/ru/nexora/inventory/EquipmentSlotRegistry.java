package ru.nexora.inventory;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable server-authoritative magical equipment definitions.
 * Clients cannot provide slot compatibility or attribute values.
 */
public final class EquipmentSlotRegistry {
    private final Map<String, Set<EquipmentSlot>> allowedSlots;
    private final Map<String, Map<EquipmentAttribute, Double>> attributes;

    private EquipmentSlotRegistry(Map<String, Set<EquipmentSlot>> allowedSlots,
                                  Map<String, Map<EquipmentAttribute, Double>> attributes) {
        Map<String, Set<EquipmentSlot>> slotsCopy = new HashMap<>();
        allowedSlots.forEach((itemId, slots) -> slotsCopy.put(itemId, Set.copyOf(slots)));
        this.allowedSlots = Collections.unmodifiableMap(slotsCopy);

        Map<String, Map<EquipmentAttribute, Double>> attributesCopy = new HashMap<>();
        attributes.forEach((itemId, values) -> {
            java.util.EnumMap<EquipmentAttribute, Double> itemValues =
                    new java.util.EnumMap<>(EquipmentAttribute.class);
            itemValues.putAll(values);
            attributesCopy.put(itemId, Collections.unmodifiableMap(itemValues));
        });
        this.attributes = Collections.unmodifiableMap(attributesCopy);
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

    public Map<EquipmentAttribute, Double> attributes(String itemId) {
        return attributes.getOrDefault(Objects.requireNonNull(itemId, "itemId"), Map.of());
    }

    public static final class Builder {
        private final Map<String, Set<EquipmentSlot>> entries = new HashMap<>();
        private final Map<String, Map<EquipmentAttribute, Double>> attributes = new HashMap<>();

        public Builder register(String itemId, EquipmentSlot... slots) {
            return register(itemId, Map.of(), slots);
        }

        public Builder register(String itemId, Map<EquipmentAttribute, Double> itemAttributes,
                                EquipmentSlot... slots) {
            Objects.requireNonNull(itemId, "itemId");
            if (!itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                throw new IllegalArgumentException("Invalid namespaced equipment item ID");
            }
            Objects.requireNonNull(slots, "slots");
            Objects.requireNonNull(itemAttributes, "itemAttributes");
            if (slots.length == 0) throw new IllegalArgumentException("At least one equipment slot is required");
            EnumSet<EquipmentSlot> accepted = EnumSet.noneOf(EquipmentSlot.class);
            for (EquipmentSlot slot : slots) accepted.add(Objects.requireNonNull(slot, "slot"));

            java.util.EnumMap<EquipmentAttribute, Double> checkedAttributes =
                    new java.util.EnumMap<>(EquipmentAttribute.class);
            itemAttributes.forEach((attribute, value) -> {
                Objects.requireNonNull(attribute, "attribute");
                Objects.requireNonNull(value, "attribute value");
                if (!Double.isFinite(value) || Math.abs(value) > 1_000_000.0) {
                    throw new IllegalArgumentException("Equipment attribute value is outside safe limits");
                }
                checkedAttributes.put(attribute, value);
            });

            if (entries.putIfAbsent(itemId, accepted) != null) {
                throw new IllegalArgumentException("Equipment item is already registered: " + itemId);
            }
            attributes.put(itemId, Map.copyOf(checkedAttributes));
            return this;
        }

        public EquipmentSlotRegistry build() {
            return new EquipmentSlotRegistry(entries, attributes);
        }
    }
}
