package ru.nexora.inventory;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnvilServiceTest {
    private final AnvilService service = new AnvilService();

    @Test
    void combinesMatchingEnchantmentLevelsAndChargesExperience() {
        var base = item("minecraft:diamond_sword", Map.of("minecraft:sharpness", 2), 0);
        var book = item("minecraft:enchanted_book", Map.of("minecraft:sharpness", 2), 0);

        var result = service.combine(base, book, 10);

        assertTrue(result.successful());
        assertEquals(3, result.result().enchantments().get("minecraft:sharpness"));
        assertEquals(3, result.levelCost());
        assertEquals(1, result.result().repairCost());
    }

    @Test
    void doesNotConsumeOrProduceResultWhenExperienceIsInsufficient() {
        var base = item("minecraft:diamond_pickaxe", Map.of(), 0);
        var book = item("minecraft:enchanted_book", Map.of("minecraft:efficiency", 4), 0);

        var result = service.combine(base, book, 1);

        assertEquals(AnvilService.Status.INSUFFICIENT_EXPERIENCE, result.status());
        assertNull(result.result());
    }

    @Test
    void rejectsMutuallyExclusiveEnchantments() {
        var base = item("minecraft:diamond_pickaxe", Map.of("minecraft:fortune", 2), 0);
        var book = item("minecraft:enchanted_book", Map.of("minecraft:silk_touch", 1), 0);

        assertEquals(AnvilService.Status.CONFLICTING_ENCHANTMENTS,
                service.combine(base, book, 30).status());
    }

    @Test
    void rejectsEnchantmentsThatDoNotApplyToTargetItem() {
        var sword = item("minecraft:diamond_sword", Map.of(), 0);
        var book = item("minecraft:enchanted_book", Map.of("minecraft:efficiency", 3), 0);

        assertEquals(AnvilService.Status.INCOMPATIBLE_ENCHANTMENT,
                service.combine(sword, book, 30).status());
    }

    @Test
    void combinesSameToolsButRejectsDifferentItems() {
        var first = item("minecraft:iron_pickaxe", Map.of("minecraft:unbreaking", 1), 0);
        var second = item("minecraft:iron_pickaxe", Map.of("minecraft:unbreaking", 1), 0);
        assertEquals(2, service.combine(first, second, 10)
                .result().enchantments().get("minecraft:unbreaking"));

        var other = item("minecraft:diamond_pickaxe", Map.of("minecraft:unbreaking", 1), 0);
        assertEquals(AnvilService.Status.INCOMPATIBLE_ITEMS,
                service.combine(first, other, 10).status());
    }

    @Test
    void rejectsVanillaSurvivalCostAboveThirtyNineLevels() {
        var base = item("minecraft:netherite_sword", Map.of(), 20);
        var book = item("minecraft:enchanted_book", Map.of("minecraft:sharpness", 5), 20);

        assertEquals(AnvilService.Status.TOO_EXPENSIVE, service.combine(base, book, 100).status());
    }

    @Test
    void validatesEnchantmentLevelsAndRepairPenalty() {
        assertThrows(IllegalArgumentException.class,
                () -> item("minecraft:diamond_sword", Map.of("minecraft:sharpness", 6), 0));
        assertThrows(IllegalArgumentException.class,
                () -> item("minecraft:diamond_sword", Map.of(), 32));
    }

    @Test
    void supportsAdditionalVanillaEnchantmentsOnTheirCorrectItemGroups() {
        var sword = item("minecraft:diamond_sword", Map.of(), 0);
        var swordBook = item("minecraft:enchanted_book", Map.of("minecraft:looting", 3), 0);
        var helmet = item("minecraft:diamond_helmet", Map.of(), 0);
        var helmetBook = item("minecraft:enchanted_book", Map.of("minecraft:respiration", 3), 0);
        var rod = item("minecraft:fishing_rod", Map.of(), 0);
        var rodBook = item("minecraft:enchanted_book", Map.of("minecraft:luck_of_the_sea", 3), 0);

        assertTrue(service.combine(sword, swordBook, 39).successful());
        assertTrue(service.combine(helmet, helmetBook, 39).successful());
        assertTrue(service.combine(rod, rodBook, 39).successful());
        assertEquals(AnvilService.Status.INCOMPATIBLE_ENCHANTMENT,
                service.combine(sword, helmetBook, 39).status());
    }

    @Test
    void supportsQuickChargeSoulSpeedAndSwiftSneakOnCorrectEquipment() {
        var crossbow = item("minecraft:crossbow", Map.of(), 0);
        var quickCharge = item("minecraft:enchanted_book", Map.of("minecraft:quick_charge", 3), 0);
        var boots = item("minecraft:diamond_boots", Map.of(), 0);
        var soulSpeed = item("minecraft:enchanted_book", Map.of("minecraft:soul_speed", 3), 0);
        var leggings = item("minecraft:diamond_leggings", Map.of(), 0);
        var swiftSneak = item("minecraft:enchanted_book", Map.of("minecraft:swift_sneak", 3), 0);

        assertTrue(service.combine(crossbow, quickCharge, 39).successful());
        assertTrue(service.combine(boots, soulSpeed, 39).successful());
        assertTrue(service.combine(leggings, swiftSneak, 39).successful());
        assertEquals(AnvilService.Status.INCOMPATIBLE_ENCHANTMENT,
                service.combine(boots, swiftSneak, 39).status());
    }

    private static AnvilService.EnchantedItem item(String id, Map<String, Integer> enchantments, int penalty) {
        return new AnvilService.EnchantedItem(id, 1, enchantments, penalty);
    }
    @Test
    void combinesRealItemStacksAndReturnsPersistentMetadata() {
        ItemStack sword = new ItemStack("minecraft:diamond_sword", 1, 1,
                Map.of("minecraft:sharpness", 2), 0);
        ItemStack book = new ItemStack("minecraft:enchanted_book", 1, 1,
                Map.of("minecraft:sharpness", 2), 0);

        AnvilService.Outcome outcome = service.combine(sword, book, 10);

        assertTrue(outcome.successful());
        assertEquals(3, outcome.resultStack().enchantments().get("minecraft:sharpness"));
        assertEquals(1, outcome.resultStack().repairCost());
        assertEquals(3, outcome.levelCost());
    }

}
