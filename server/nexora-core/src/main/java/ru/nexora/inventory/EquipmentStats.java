package ru.nexora.inventory;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Immutable aggregate of additive attributes granted by the player's magical equipment. */
public final class EquipmentStats {
    private final Map<EquipmentAttribute, Double> values;

    public EquipmentStats(Map<EquipmentAttribute, Double> values) {
        Objects.requireNonNull(values, "values");
        EnumMap<EquipmentAttribute, Double> copy = new EnumMap<>(EquipmentAttribute.class);
        values.forEach((attribute, value) -> {
            Objects.requireNonNull(attribute, "attribute");
            Objects.requireNonNull(value, "value");
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Equipment stat must be finite");
            copy.put(attribute, value);
        });
        this.values = Collections.unmodifiableMap(copy);
    }

    public double value(EquipmentAttribute attribute) {
        return values.getOrDefault(Objects.requireNonNull(attribute, "attribute"), 0.0);
    }

    public Map<EquipmentAttribute, Double> values() {
        return values;
    }

    public static EquipmentStats empty() {
        return new EquipmentStats(Map.of());
    }

    static EquipmentStats aggregate(PlayerEquipment equipment, EquipmentSlotRegistry registry) {
        Objects.requireNonNull(equipment, "equipment");
        Objects.requireNonNull(registry, "registry");
        EnumMap<EquipmentAttribute, Double> totals = new EnumMap<>(EquipmentAttribute.class);
        for (var optional : equipment.magicSnapshot()) {
            if (optional.isEmpty()) continue;
            ItemStack stack = optional.orElseThrow();
            registry.attributes(stack.itemId()).forEach(
                    (attribute, value) -> totals.merge(attribute, value, Double::sum));
        }
        return new EquipmentStats(totals);
    }
}
