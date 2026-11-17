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
    void consumesCraftingGridItemsAndReturnsOutputToPlayerInventoryAtomically() {
        PlayerInventory player = new PlayerInventory();
        ContainerInventory ui = new ContainerInventory(51);
        ui.setSlot(28, new ItemStack("minecraft:oak_planks", 2, 64));
        ui.setSlot(30, new ItemStack("minecraft:oak_planks", 1, 64));
        var recipe = recipes.find("minecraft:sticks_from_oak_planks");

        assertEquals(CraftingService.Result.CRAFTED,
                service.craftFromGrid(player, ui, 28, recipe));
        assertEquals(1, ui.getSlot(28).orElseThrow().count());
        assertTrue(ui.getSlot(30).isEmpty());
        assertEquals(4, player.count("minecraft:stick"));
    }

    @Test
    void restoresCraftingGridAndPlayerWhenGridOutputCannotFit() {
        PlayerInventory player = new PlayerInventory();
        ContainerInventory ui = new ContainerInventory(51);
        ui.setSlot(28, new ItemStack("minecraft:oak_planks", 1, 64));
        ui.setSlot(30, new ItemStack("minecraft:oak_planks", 1, 64));
        for (int slot = 0; slot < player.size(); slot++) {
            player.setSlot(slot, new ItemStack("minecraft:diamond", 64, 64));
        }
        var beforePlayer = player.snapshot();
        var beforeUi = ui.snapshot();

        assertEquals(CraftingService.Result.INVENTORY_FULL,
                service.craftFromGrid(player, ui, 28, recipes.find("minecraft:sticks_from_oak_planks")));
        assertEquals(beforePlayer, player.snapshot());
        assertEquals(beforeUi, ui.snapshot());
    }

    @Test
    void craftsCraftingTableFromFourOakPlanks() {
        PlayerInventory inventory = new PlayerInventory();
        inventory.setSlot(0, new ItemStack("minecraft:oak_planks", 4, 64));

        assertEquals(CraftingService.Result.CRAFTED,
                service.craft(inventory, recipes, "minecraft:crafting_table_from_oak_planks", 1));
        assertEquals(0, inventory.count("minecraft:oak_planks"));
        assertEquals(1, inventory.count("minecraft:crafting_table"));
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

    @Test
    void craftsShapedThreeByThreeRecipeFromOffsetGrid() {
        PlayerInventory player = new PlayerInventory();
        ContainerInventory ui = new ContainerInventory(51);
        CraftingRecipe recipe = CraftingRecipe.shaped(
                "minecraft:test_three_by_three",
                java.util.List.of("PPP", " S ", " S "),
                java.util.Map.of('P', "minecraft:oak_planks", 'S', "minecraft:stick"),
                new ItemStack("minecraft:oak_boat", 1, 1));
        ui.setSlot(10, new ItemStack("minecraft:oak_planks", 1, 64));
        ui.setSlot(11, new ItemStack("minecraft:oak_planks", 1, 64));
        ui.setSlot(12, new ItemStack("minecraft:oak_planks", 1, 64));
        ui.setSlot(14, new ItemStack("minecraft:stick", 1, 64));
        ui.setSlot(17, new ItemStack("minecraft:stick", 1, 64));

        assertEquals(CraftingService.Result.CRAFTED,
                service.craftFromGrid(player, ui, 10, 3, 3, recipe));
        assertEquals(1, player.count("minecraft:oak_boat"));
        for (int slot = 10; slot < 19; slot++) assertTrue(ui.getSlot(slot).isEmpty());
    }

    @Test
    void rejectsIncorrectShapedThreeByThreePlacementWithoutConsumingItems() {
        PlayerInventory player = new PlayerInventory();
        ContainerInventory ui = new ContainerInventory(51);
        CraftingRecipe recipe = CraftingRecipe.shaped(
                "minecraft:test_three_by_three",
                java.util.List.of("PPP", " S ", " S "),
                java.util.Map.of('P', "minecraft:oak_planks", 'S', "minecraft:stick"),
                new ItemStack("minecraft:oak_boat", 1, 1));
        ui.setSlot(10, new ItemStack("minecraft:oak_planks", 1, 64));
        ui.setSlot(11, new ItemStack("minecraft:oak_planks", 1, 64));
        ui.setSlot(12, new ItemStack("minecraft:oak_planks", 1, 64));
        ui.setSlot(14, new ItemStack("minecraft:stick", 1, 64));
        ui.setSlot(16, new ItemStack("minecraft:stick", 1, 64));
        var before = ui.snapshot();

        assertEquals(CraftingService.Result.INSUFFICIENT_INGREDIENTS,
                service.craftFromGrid(player, ui, 10, 3, 3, recipe));
        assertEquals(before, ui.snapshot());
        assertEquals(0, player.count("minecraft:oak_boat"));
    }

    @Test
    void rejectsThreeByThreeGridOutsideContainerBounds() {
        PlayerInventory player = new PlayerInventory();
        ContainerInventory ui = new ContainerInventory(32);
        var recipe = recipes.find("minecraft:crafting_table_from_oak_planks");

        assertEquals(CraftingService.Result.INVALID_GRID,
                service.craftFromGrid(player, ui, 28, 3, 3, recipe));
        assertEquals(CraftingService.Result.INVALID_GRID,
                service.craftFromGrid(player, ui, 0, 4, 3, recipe));
    }

    @Test
    void craftsArmorAndShieldFromThreeByThreeWorkbenchGrid() {
        PlayerInventory player = new PlayerInventory();
        ContainerInventory grid = new ContainerInventory(9);
        var helmet = recipes.find("minecraft:iron_helmet");
        grid.setSlot(0, new ItemStack("minecraft:iron_ingot", 1, 64));
        grid.setSlot(1, new ItemStack("minecraft:iron_ingot", 1, 64));
        grid.setSlot(2, new ItemStack("minecraft:iron_ingot", 1, 64));
        grid.setSlot(3, new ItemStack("minecraft:iron_ingot", 1, 64));
        grid.setSlot(5, new ItemStack("minecraft:iron_ingot", 1, 64));

        assertEquals(CraftingService.Result.CRAFTED,
                service.craftFromGrid(player, grid, 0, 3, 3, helmet));
        assertEquals(1, player.count("minecraft:iron_helmet"));
        assertTrue(grid.snapshot().stream().allMatch(java.util.Optional::isEmpty));

        grid.setSlot(0, new ItemStack("minecraft:oak_planks", 1, 64));
        grid.setSlot(1, new ItemStack("minecraft:iron_ingot", 1, 64));
        grid.setSlot(2, new ItemStack("minecraft:oak_planks", 1, 64));
        grid.setSlot(3, new ItemStack("minecraft:oak_planks", 1, 64));
        grid.setSlot(4, new ItemStack("minecraft:oak_planks", 1, 64));
        grid.setSlot(5, new ItemStack("minecraft:oak_planks", 1, 64));
        grid.setSlot(7, new ItemStack("minecraft:oak_planks", 1, 64));

        assertEquals(CraftingService.Result.CRAFTED,
                service.craftFromGrid(player, grid, 0, 3, 3, recipes.find("minecraft:shield")));
        assertEquals(1, player.count("minecraft:shield"));
    }

    @Test
    void mirroredThreeByThreeShapedRecipesAreAccepted() {
        PlayerInventory player = new PlayerInventory();
        ContainerInventory grid = new ContainerInventory(9);
        var recipe = CraftingRecipe.shaped(
                "nexora:mirrored_pattern_test",
                java.util.List.of("AB ", " A "),
                java.util.Map.of('A', "minecraft:oak_planks", 'B', "minecraft:stick"),
                new ItemStack("minecraft:shield", 1, 1));
        grid.setSlot(1, new ItemStack("minecraft:stick", 1, 64));
        grid.setSlot(2, new ItemStack("minecraft:oak_planks", 1, 64));
        grid.setSlot(4, new ItemStack("minecraft:oak_planks", 1, 64));

        assertEquals(CraftingService.Result.CRAFTED,
                service.craftFromGrid(player, grid, 0, 3, 3, recipe));
        assertEquals(1, player.count("minecraft:shield"));
    }
}
