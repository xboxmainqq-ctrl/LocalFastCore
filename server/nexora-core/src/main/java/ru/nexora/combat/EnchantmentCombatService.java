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
        if (!Float.isFinite(baseDamage) || baseDamage < 0) {
            throw new IllegalArgumentException("baseDamage must be finite and non-negative");
        }
        Objects.requireNonNull(targetType, "targetType");
        int sharpness = level(weapon, "minecraft:sharpness");
        int smite = targetType == TargetType.UNDEAD ? level(weapon, "minecraft:smite") : 0;
        int bane = targetType == TargetType.ARTHROPOD
                ? level(weapon, "minecraft:bane_of_arthropods") : 0;

        // Bedrock damage bonuses: Sharpness +1.25/level; Smite and Bane +2.5/level.
        float bonus = sharpness * 1.25f + Math.max(smite, bane) * 2.5f;
        return new AttackEffect(baseDamage + bonus,
                level(weapon, "minecraft:knockback"),
                level(weapon, "minecraft:fire_aspect") * 4);
    }

    /**
     * Applies vanilla armor protection enchantments to incoming damage.
     * Protection contributes 4% per level; the matching specialized protection
     * enchantment contributes 8% per level. Combined reduction is capped at 80%.
     */
    public static float resolveIncomingDamage(float baseDamage, List<ItemStack> armor, DamageType damageType) {
        if (!Float.isFinite(baseDamage) || baseDamage < 0) {
            throw new IllegalArgumentException("baseDamage must be finite and non-negative");
        }
        Objects.requireNonNull(armor, "armor");
        Objects.requireNonNull(damageType, "damageType");

        int protectionPoints = 0;
        for (ItemStack piece : armor) {
            protectionPoints += level(piece, "minecraft:protection");
            String specialized = switch (damageType) {
                case FIRE -> "minecraft:fire_protection";
                case EXPLOSION -> "minecraft:blast_protection";
                case PROJECTILE -> "minecraft:projectile_protection";
                case FALL -> "minecraft:feather_falling";
                case NORMAL -> null;
            };
            if (specialized != null) protectionPoints += 2 * level(piece, specialized);
        }

        int cappedPoints = Math.min(20, protectionPoints);
        return baseDamage * (1.0f - cappedPoints * 0.04f);
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

    private static int level(ItemStack stack, String enchantment) {
        return stack == null ? 0 : stack.enchantments().getOrDefault(enchantment, 0);
    }

    public enum TargetType {
        NORMAL,
        UNDEAD,
        ARTHROPOD
    }

    public enum DamageType {
        NORMAL,
        FIRE,
        EXPLOSION,
        PROJECTILE,
        FALL
    }

    public record AttackEffect(float damage, int knockbackLevel, int fireDurationSeconds) {
        public AttackEffect {
            if (!Float.isFinite(damage) || damage < 0 || knockbackLevel < 0 || fireDurationSeconds < 0) {
                throw new IllegalArgumentException("Invalid combat effect");
            }
        }
    }
}
