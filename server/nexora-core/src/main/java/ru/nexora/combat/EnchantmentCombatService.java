package ru.nexora.combat;

import ru.nexora.inventory.ItemStack;

import java.util.List;
import java.util.Objects;

/**
 * Deterministic combat calculations for vanilla weapon and armor enchantments.
 *
 * This class deliberately contains no packet or entity lifecycle code: callers must
 * invoke it only after validating a real server-side hit. Keeping the calculation
 * isolated makes enchantment values testable and prevents client-provided damage.
 */
public final class EnchantmentCombatService {
    private EnchantmentCombatService() {
    }

    public static AttackEffect resolveAttack(float baseDamage, ItemStack weapon, TargetType targetType) {
        return resolveAttack(baseDamage, weapon, targetType, 0.0f);
    }

    /**
     * Resolves attack bonuses using the server-measured distance fallen by the attacker.
     * Density adds 0.5 damage per fallen block and enchantment level, and is mace-only.
     */
    public static AttackEffect resolveAttack(
            float baseDamage, ItemStack weapon, TargetType targetType, float fallDistance
    ) {
        if (!Float.isFinite(baseDamage) || baseDamage < 0) {
            throw new IllegalArgumentException("baseDamage must be finite and non-negative");
        }
        if (!Float.isFinite(fallDistance) || fallDistance < 0) {
            throw new IllegalArgumentException("fallDistance must be finite and non-negative");
        }
        Objects.requireNonNull(targetType, "targetType");
        int sharpness = level(weapon, "minecraft:sharpness");
        int smite = targetType == TargetType.UNDEAD ? level(weapon, "minecraft:smite") : 0;
        int bane = targetType == TargetType.ARTHROPOD
                ? level(weapon, "minecraft:bane_of_arthropods") : 0;

        // Bedrock damage bonuses: Sharpness +1.25/level; Smite and Bane +2.5/level.
        float bonus = sharpness * 1.25f + Math.max(smite, bane) * 2.5f;
        int density = isMace(weapon) ? level(weapon, "minecraft:density") : 0;
        bonus += fallDistance * 0.5f * density;
        return new AttackEffect(baseDamage + bonus,
                level(weapon, "minecraft:knockback"),
                level(weapon, "minecraft:fire_aspect") * 4,
                level(weapon, "minecraft:breach"));
    }

    /** Applies armor and enchantment reduction, with Breach weakening armor points only. */
    public static float applyIncomingDamage(
            float incomingDamage, List<ItemStack> armor, DamageType damageType, int breachLevel
    ) {
        if (breachLevel < 0 || breachLevel > 4) {
            throw new IllegalArgumentException("breachLevel must be between 0 and 4");
        }
        if (!Float.isFinite(incomingDamage) || incomingDamage < 0.0f) {
            throw new IllegalArgumentException("incomingDamage must be finite and non-negative");
        }
        Objects.requireNonNull(armor, "armor");
        Objects.requireNonNull(damageType, "damageType");
        int armorPoints = 0;
        for (ItemStack piece : armor) {
            Objects.requireNonNull(piece, "armor piece");
            armorPoints += armorPointsForPiece(piece.itemId());
        }
        double breachMultiplier = 1.0 - 0.15 * breachLevel;
        double armorReduction = damageType == DamageType.FIRE || damageType == DamageType.FALL
                ? 0.0 : Math.min(0.80, armorPoints * breachMultiplier * 0.04);
        float afterArmor = (float) (incomingDamage * (1.0 - armorReduction));
        return applyEnchantmentProtection(afterArmor, armor, damageType);
    }

    private static boolean isMace(ItemStack weapon) {
        return weapon != null && "minecraft:mace".equals(weapon.itemId());
    }

    /**
     * Resolves Thorns independently for each worn armor piece.
     * Rolls must contain one value in [0,1) per armor slot; damage rolls are 1..4.
     */
    public static int resolveThornsDamage(List<ItemStack> armor, double[] rolls, int[] damageRolls) {
        Objects.requireNonNull(armor, "armor");
        Objects.requireNonNull(rolls, "rolls");
        Objects.requireNonNull(damageRolls, "damageRolls");
        if (rolls.length != armor.size() || damageRolls.length != armor.size()) {
            throw new IllegalArgumentException("One random roll is required per armor slot");
        }
        int total = 0;
        for (int i = 0; i < armor.size(); i++) {
            ItemStack piece = armor.get(i);
            double roll = rolls[i];
            int damageRoll = damageRolls[i];
            if (!Double.isFinite(roll) || roll < 0 || roll >= 1 || damageRoll < 1 || damageRoll > 4) {
                throw new IllegalArgumentException("Invalid Thorns random roll");
            }
            int level = level(piece, "minecraft:thorns");
            if (level == 0 || roll >= Math.min(1.0, 0.15 * level)) continue;
            total += level > 10 ? level - 10 : damageRoll;
        }
        return total;
    }

    /** Applies Bedrock armor points and generic Protection enchantments. */
    public static float applyArmorReduction(float incomingDamage, List<ItemStack> armor) {
        return applyIncomingDamage(incomingDamage, armor, DamageType.GENERIC);
    }

    /**
     * Applies armor points and the matching Protection enchantments in one authoritative
     * calculation. Armor points reduce 4% each (capped at 80%); the Bedrock enchantment
     * protection factor is then applied to the remaining damage (also capped at 80%).
     */
    public static float applyIncomingDamage(
            float incomingDamage, List<ItemStack> armor, DamageType damageType
    ) {
        if (!Float.isFinite(incomingDamage) || incomingDamage < 0.0f) {
            throw new IllegalArgumentException("incomingDamage must be finite and non-negative");
        }
        Objects.requireNonNull(armor, "armor");
        Objects.requireNonNull(damageType, "damageType");
        int armorPoints = 0;
        for (ItemStack piece : armor) {
            Objects.requireNonNull(piece, "armor piece");
            armorPoints += armorPointsForPiece(piece.itemId());
        }
        // Armor points do not mitigate fire or fall damage; their matching enchantments still do.
        double armorReduction = damageType == DamageType.FIRE || damageType == DamageType.FALL
                ? 0.0 : Math.min(0.80, armorPoints * 0.04);
        float afterArmor = (float) (incomingDamage * (1.0 - armorReduction));
        return applyEnchantmentProtection(afterArmor, armor, damageType);
    }

    /** Applies the Bedrock Enchantment Protection Factor for a specific damage type. */
    public static float applyEnchantmentProtection(
            float incomingDamage, List<ItemStack> armor, DamageType damageType
    ) {
        if (!Float.isFinite(incomingDamage) || incomingDamage < 0.0f) {
            throw new IllegalArgumentException("incomingDamage must be finite and non-negative");
        }
        Objects.requireNonNull(armor, "armor");
        Objects.requireNonNull(damageType, "damageType");
        int epf = 0;
        for (ItemStack piece : armor) {
            Objects.requireNonNull(piece, "armor piece");
            epf += level(piece, "minecraft:protection");
            epf += switch (damageType) {
                case FIRE -> 2 * level(piece, "minecraft:fire_protection");
                case FALL -> 3 * level(piece, "minecraft:feather_falling");
                case EXPLOSION -> 2 * level(piece, "minecraft:blast_protection");
                case PROJECTILE -> 2 * level(piece, "minecraft:projectile_protection");
                case GENERIC -> 0;
            };
        }
        return incomingDamage * (1.0f - Math.min(20, epf) / 25.0f);
    }

    public enum DamageType {
        GENERIC,
        FIRE,
        FALL,
        EXPLOSION,
        PROJECTILE
    }

    private static int armorPointsForPiece(String itemId) {
        int helmet;
        int chestplate;
        int leggings;
        int boots;
        if (itemId.contains("leather")) {
            helmet = 1; chestplate = 3; leggings = 2; boots = 1;
        } else if (itemId.contains("chainmail")) {
            helmet = 2; chestplate = 5; leggings = 4; boots = 1;
        } else if (itemId.contains("iron")) {
            helmet = 2; chestplate = 6; leggings = 5; boots = 2;
        } else if (itemId.contains("golden")) {
            helmet = 2; chestplate = 5; leggings = 3; boots = 1;
        } else if (itemId.contains("diamond") || itemId.contains("netherite")) {
            helmet = 3; chestplate = 8; leggings = 6; boots = 3;
        } else {
            return 0;
        }
        if (itemId.endsWith("_helmet")) return helmet;
        if (itemId.endsWith("_chestplate")) return chestplate;
        if (itemId.endsWith("_leggings")) return leggings;
        if (itemId.endsWith("_boots")) return boots;
        return 0;
    }

    private static int level(ItemStack stack, String enchantment) {
        return stack == null ? 0 : stack.enchantments().getOrDefault(enchantment, 0);
    }

    public enum TargetType {
        NORMAL,
        UNDEAD,
        ARTHROPOD
    }

    public record AttackEffect(
            float damage, int knockbackLevel, int fireDurationSeconds, int breachLevel
    ) {
        public AttackEffect(float damage, int knockbackLevel, int fireDurationSeconds) {
            this(damage, knockbackLevel, fireDurationSeconds, 0);
        }

        public AttackEffect {
            if (!Float.isFinite(damage) || damage < 0 || knockbackLevel < 0
                    || fireDurationSeconds < 0 || breachLevel < 0 || breachLevel > 4) {
                throw new IllegalArgumentException("Invalid combat effect");
            }
        }
    }
}
