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

    public static EquipmentStats calculate(PlayerEquipment equipment, EquipmentSlotRegistry registry) {
        Objects.requireNonNull(equipment, "equipment");
        Objects.requireNonNull(registry, "registry");
        EnumMap<EquipmentAttribute, Double> totals = new EnumMap<>(EquipmentAttribute.class);
        for (var optional : equipment.armorSnapshot()) {
            optional.ifPresent(stack -> addAttributes(totals, registry, stack));
        }
        equipment.offhand().ifPresent(stack -> addAttributes(totals, registry, stack));
        for (var optional : equipment.magicSnapshot()) {
            optional.ifPresent(stack -> addAttributes(totals, registry, stack));
        }
        return new EquipmentStats(totals);
    }

    private static void addAttributes(EnumMap<EquipmentAttribute, Double> totals,
                                      EquipmentSlotRegistry registry, ItemStack stack) {
        registry.attributes(stack.itemId()).forEach(
                (attribute, value) -> totals.merge(attribute, value, Double::sum));
    }
}
