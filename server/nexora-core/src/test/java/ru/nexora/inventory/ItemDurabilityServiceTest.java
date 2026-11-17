package ru.nexora.inventory;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ItemDurabilityServiceTest {

    @Test
    void resolvesWeaponArmorAndSpearDurabilityForAllMaterials() {
        assertEquals(59, ItemDurabilityService.maximumDurability(stack("wooden_spear")).value());
        assertEquals(32, ItemDurabilityService.maximumDurability(stack("golden_spear")).value());
        assertEquals(131, ItemDurabilityService.maximumDurability(stack("stone_spear")).value());
        assertEquals(190, ItemDurabilityService.maximumDurability(stack("copper_spear")).value());
        assertEquals(250, ItemDurabilityService.maximumDurability(stack("iron_spear")).value());
        assertEquals(1561, ItemDurabilityService.maximumDurability(stack("diamond_spear")).value());
        assertEquals(2031, ItemDurabilityService.maximumDurability(stack("netherite_spear")).value());
        assertEquals(528, ItemDurabilityService.maximumDurability(stack("diamond_chestplate")).value());
        assertEquals(481, ItemDurabilityService.maximumDurability(stack("netherite_boots")).value());
        assertFalse(ItemDurabilityService.maximumDurability(
                new ItemStack("minecraft:stone", 1, 64)).present());
    }

    @Test
    void appliesWearAndBreaksItemAtItsMaximumDamage() {
        ItemStack sword = new ItemStack("minecraft:wooden_sword", 1, 1)
                .withDurabilityDamage(58);
        ItemStack damaged = ItemDurabilityService.applyWear(sword, 1, new Random(1)).orElseThrow();
        assertEquals(59, damaged.durabilityDamage());
        assertTrue(ItemDurabilityService.applyWear(damaged, 1, new Random(1)).isEmpty());
    }

    @Test
    void unbreakingCanPreventWearAndDamageIsPreserved() {
        ItemStack sword = new ItemStack("minecraft:diamond_sword", 1, 1,
                Map.of("minecraft:unbreaking", 3), 0, 17);
        Random preventWear = new Random(1) {
            @Override public int nextInt(int bound) { return bound - 1; }
        };
        ItemStack unchanged = ItemDurabilityService.applyWear(sword, 1, preventWear).orElseThrow();
        assertEquals(17, unchanged.durabilityDamage());

        Random applyWear = new Random(1) {
            @Override public int nextInt(int bound) { return 0; }
        };
        ItemStack damaged = ItemDurabilityService.applyWear(sword, 1, applyWear).orElseThrow();
        assertEquals(18, damaged.durabilityDamage());
    }

    @Test
    void damagePreventsStacksFromMergingAndSurvivesCountChanges() {
        ItemStack pristine = new ItemStack("minecraft:diamond_sword", 1, 1);
        ItemStack worn = pristine.withDurabilityDamage(1);
        assertFalse(pristine.canStackWith(worn));
        assertEquals(1, worn.withCount(1).durabilityDamage());
    }

    private static ItemStack stack(String id) {
        return new ItemStack("minecraft:" + id, 1, 1);
    }
}
