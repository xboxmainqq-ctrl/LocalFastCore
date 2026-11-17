package ru.nexora.combat;

import ru.nexora.inventory.ItemStack;

import java.util.Map;
import java.util.Optional;

/** Single source of truth for Bedrock spear jab and charge tuning. */
public final class SpearCombatStats {
    private static final Map<String, Profile> PROFILES = Map.of(
            "minecraft:wooden_spear", new Profile(2.0f, 650_000_000L, 0.70, 2.0, 4.5),
            "minecraft:golden_spear", new Profile(2.0f, 950_000_000L, 0.70, 2.0, 4.5),
            "minecraft:stone_spear", new Profile(3.0f, 750_000_000L, 0.82, 2.0, 4.5),
            "minecraft:copper_spear", new Profile(3.0f, 850_000_000L, 0.82, 2.0, 4.5),
            "minecraft:iron_spear", new Profile(4.0f, 950_000_000L, 0.95, 2.0, 4.5),
            "minecraft:diamond_spear", new Profile(5.0f, 1_050_000_000L, 1.075, 2.0, 4.5),
            "minecraft:netherite_spear", new Profile(6.0f, 1_150_000_000L, 1.20, 2.0, 4.5)
    );

    private SpearCombatStats() {
    }

    public static Optional<Profile> forItem(ItemStack item) {
        return item == null ? Optional.empty() : Optional.ofNullable(PROFILES.get(item.itemId()));
    }

    public record Profile(
            float jabDamage,
            long jabCooldownNanos,
            double chargeDamageMultiplier,
            double minimumReach,
            double maximumReach
    ) {
        public Profile {
            if (!Float.isFinite(jabDamage) || jabDamage <= 0.0f
                    || jabCooldownNanos <= 0
                    || !Double.isFinite(chargeDamageMultiplier) || chargeDamageMultiplier <= 0.0
                    || !Double.isFinite(minimumReach) || minimumReach < 0.0
                    || !Double.isFinite(maximumReach) || maximumReach < minimumReach) {
                throw new IllegalArgumentException("Invalid spear combat profile");
            }
        }

        public boolean isWithinReach(double distanceSquared) {
            return Double.isFinite(distanceSquared)
                    && distanceSquared >= minimumReach * minimumReach
                    && distanceSquared <= maximumReach * maximumReach;
        }
    }
}
