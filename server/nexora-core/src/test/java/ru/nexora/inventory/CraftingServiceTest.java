package ru.nexora.inventory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CraftingServiceTest {
    private final CraftingService service = new CraftingService();
    private final CraftingRecipeRegistry recipes = CraftingRecipeRegistry.vanillaSubset();

    @Test
    void craftsIronAndGoldNuggetsFromRegisteredIngots() {
        PlayerInventory inventory = new PlayerInventory();
        inventory.setSlot(0, new ItemStack("minecraft:iron_ingot", 2, 64));
        inventory.setSlot(1, new ItemStack("minecraft:gold_ingot", 1, 64));

        assertEquals(CraftingService.Result.CRAFTED,
                service.craft(inventory, recipes, "minecraft:iron_nuggets_from_iron_ingot", 2));
        assertEquals(CraftingService.Result.CRAFTED,
                service.craft(inventory, recipes, "minecraft:gold_nuggets_from_gold_ingot", 1));

        assertEquals(0, inventory.count("minecraft:iron_ingot"));
        assertEquals(0, inventory.count("minecraft:gold_ingot"));
        assertEquals(18, inventory.count("minecraft:iron_nugget"));
        assertEquals(9, inventory.count("minecraft:gold_nugget"));
    }

    @Test
    void craftsFourOakPlanksFromOneOakLog() {
        PlayerInventory inventory = new PlayerInventory();
        inventory.setSlot(0, new ItemStack("minecraft:oak_log", 1, 64));

        assertEquals(CraftingService.Result.CRAFTED,
                service.craft(inventory, recipes, "minecraft:oak_planks_from_oak_log", 1));
        assertEquals(0, inventory.count("minecraft:oak_log"));
        assertEquals(4, inventory.count("minecraft:oak_planks"));
    }

    @Test
    void settlesIngredientsForShapedStickRecipe() {
        PlayerInventory inventory = new PlayerInventory();
        inventory.setSlot(0, new ItemStack("minecraft:oak_planks", 4, 64));

        assertEquals(CraftingService.Result.CRAFTED,
                service.craft(inventory, recipes, "minecraft:sticks_from_oak_planks", 1));
        assertEquals(2, inventory.count("minecraft:oak_planks"));
        assertEquals(4, inventory.count("minecraft:stick"));
    }

    @Test
    void consumesIngredientsAcrossMultipleSlots() {
        PlayerInventory inventory = new PlayerInventory();
        inventory.setSlot(0, new ItemStack("minecraft:iron_ingot", 1, 64));
        inventory.setSlot(1, new ItemStack("minecraft:iron_ingot", 1, 64));

        assertEquals(CraftingService.Result.CRAFTED,
                service.craft(inventory, recipes, "minecraft:iron_nuggets_from_iron_ingot", 2));
        assertEquals(0, inventory.count("minecraft:iron_ingot"));
        assertEquals(18, inventory.count("minecraft:iron_nugget"));
    }

    @Test
    void rejectsInsufficientIngredientsWithoutChangingInventory() {
        PlayerInventory inventory = new PlayerInventory();
        inventory.setSlot(0, new ItemStack("minecraft:iron_ingot", 1, 64));
        var before = inventory.snapshot();

        assertEquals(CraftingService.Result.INSUFFICIENT_INGREDIENTS,
                service.craft(inventory, recipes, "minecraft:iron_nuggets_from_iron_ingot", 2));
        assertEquals(before, inventory.snapshot());
    }

    @Test
    void rollsBackIngredientsWhenOutputCannotFit() {
        PlayerInventory inventory = new PlayerInventory();
        inventory.setSlot(0, new ItemStack("minecraft:iron_ingot", 64, 64));
        for (int slot = 1; slot < inventory.size(); slot++) {
            inventory.setSlot(slot, new ItemStack("minecraft:diamond", 64, 64));
        }
        var before = inventory.snapshot();
        int selected = inventory.selectedHotbarSlot();

        assertEquals(CraftingService.Result.INVENTORY_FULL,
                service.craft(inventory, recipes, "minecraft:iron_nuggets_from_iron_ingot", 64));
        assertEquals(before, inventory.snapshot());
        assertEquals(selected, inventory.selectedHotbarSlot());
    }

    @Test
    void splitsLargeCraftOutputIntoValidStacks() {
        PlayerInventory inventory = new PlayerInventory();
        inventory.setSlot(0, new ItemStack("minecraft:iron_ingot", 64, 64));

        assertEquals(CraftingService.Result.CRAFTED,
                service.craft(inventory, recipes, "minecraft:iron_nuggets_from_iron_ingot", 64));
        assertEquals(576, inventory.count("minecraft:iron_nugget"));
        for (var slot : inventory.snapshot()) {
            slot.ifPresent(stack -> assertTrue(stack.count() <= stack.maxStackSize()));
        }
    }

    @Test
    void rejectsUnknownRecipesInvalidMultipliersAndDuplicateRecipeIds() {
        PlayerInventory inventory = new PlayerInventory();
        assertEquals(CraftingService.Result.UNKNOWN_RECIPE,
                service.craft(inventory, recipes, "minecraft:missing_recipe", 1));
        assertEquals(CraftingService.Result.INVALID_MULTIPLIER,
                service.craft(inventory, recipes, "minecraft:iron_nuggets_from_iron_ingot", 0));
        assertEquals(CraftingService.Result.INVALID_MULTIPLIER,
                service.craft(inventory, recipes, "minecraft:iron_nuggets_from_iron_ingot", 65));

        var recipe = recipes.find("minecraft:iron_nuggets_from_iron_ingot");
        assertThrows(IllegalArgumentException.class,
                () -> new CraftingRecipeRegistry(java.util.List.of(recipe, recipe)));
    }
}
