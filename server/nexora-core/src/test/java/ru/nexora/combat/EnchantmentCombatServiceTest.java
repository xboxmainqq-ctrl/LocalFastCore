package ru.nexora.combat;

import org.junit.jupiter.api.Test;
import ru.nexora.inventory.ItemStack;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EnchantmentCombatServiceTest {
    @Test
    void appliesSharpnessAndWeaponControlEnchantmentsToAttackResult() {
        ItemStack sword = new ItemStack("minecraft:diamond_sword", 1, 1, Map.of(
                "minecraft:sharpness", 3,
                "minecraft:knockback", 2,
                "minecraft:fire_aspect", 1));

        var effect = EnchantmentCombatService.resolveAttack(
                7.0f, sword, EnchantmentCombatService.TargetType.NORMAL);

        assertEquals(10.75f, effect.damage(), 0.0001f);
        assertEquals(2, effect.knockbackLevel());
        assertEquals(4, effect.fireDurationSeconds());
    }

    @Test
    void appliesSpecializedDamageOnlyToMatchingMobFamilies() {
        ItemStack sword = new ItemStack("minecraft:diamond_sword", 1, 1, Map.of(
                "minecraft:smite", 4,
                "minecraft:bane_of_arthropods", 3));

        assertEquals(10.0f, EnchantmentCombatService.resolveAttack(
                0, sword, EnchantmentCombatService.TargetType.UNDEAD).damage(), 0.0001f);
        assertEquals(7.5f, EnchantmentCombatService.resolveAttack(
                0, sword, EnchantmentCombatService.TargetType.ARTHROPOD).damage(), 0.0001f);
        assertEquals(0.0f, EnchantmentCombatService.resolveAttack(
                0, sword, EnchantmentCombatService.TargetType.NORMAL).damage(), 0.0001f);
    }

    @Test
    void rollsThornsPerArmorPieceAndReturnsCombinedReflectionDamage() {
        ItemStack thornsOne = new ItemStack("minecraft:diamond_chestplate", 1, 1,
                Map.of("minecraft:thorns", 2));
        ItemStack plain = new ItemStack("minecraft:diamond_helmet", 1, 1);
        ItemStack thornsThree = new ItemStack("minecraft:diamond_leggings", 1, 1,
                Map.of("minecraft:thorns", 3));

        int damage = EnchantmentCombatService.resolveThornsDamage(
                List.of(thornsOne, plain, thornsThree),
                new double[]{0.20, 0.0, 0.10},
                new int[]{3, 4, 2});

        assertEquals(5, damage);
    }

    @Test
    void appliesArmorToughnessAndProtectionToIncomingPlayerDamage() {
        List<ItemStack> diamondArmor = List.of(
                new ItemStack("minecraft:diamond_helmet", 1, 1),
                new ItemStack("minecraft:diamond_chestplate", 1, 1,
                        Map.of("minecraft:protection", 4)),
                new ItemStack("minecraft:diamond_leggings", 1, 1),
                new ItemStack("minecraft:diamond_boots", 1, 1));

        float reduced = EnchantmentCombatService.applyArmorReduction(10.0f, diamondArmor);
        assertEquals(1.68f, reduced, 0.001f);
        assertEquals(10.0f, EnchantmentCombatService.applyArmorReduction(10.0f, List.of()), 0.0001f);
    }

    @Test
    void rejectsInvalidArmorDamageAndNullPieces() {
        assertThrows(IllegalArgumentException.class, () ->
                EnchantmentCombatService.applyArmorReduction(Float.POSITIVE_INFINITY, List.of()));
        assertThrows(NullPointerException.class, () ->
                EnchantmentCombatService.applyArmorReduction(4.0f, java.util.Arrays.asList((ItemStack) null)));
    }

    @Test
    void rejectsInvalidDamageAndRandomInputs() {
        assertThrows(IllegalArgumentException.class, () ->
                EnchantmentCombatService.resolveAttack(Float.NaN, null,
                        EnchantmentCombatService.TargetType.NORMAL));
        assertThrows(IllegalArgumentException.class, () ->
                EnchantmentCombatService.resolveThornsDamage(List.of(), new double[]{0}, new int[]{1}));
        assertThrows(IllegalArgumentException.class, () ->
                EnchantmentCombatService.resolveThornsDamage(
                        List.of(new ItemStack("minecraft:diamond_chestplate", 1, 1)),
                        new double[]{1.0}, new int[]{1}));
    }
}
