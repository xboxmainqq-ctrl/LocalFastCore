package ru.nexora.inventory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** All-or-nothing crafting against the authoritative player inventory. */
public final class CraftingService {
    public static final int MAX_CRAFT_MULTIPLIER = 64;

    public Result craft(PlayerInventory inventory, CraftingRecipeRegistry registry,
                        String recipeId, int multiplier) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(registry, "registry");
        if (multiplier < 1 || multiplier > MAX_CRAFT_MULTIPLIER) return Result.INVALID_MULTIPLIER;

        CraftingRecipe recipe = registry.find(recipeId);
        if (recipe == null) return Result.UNKNOWN_RECIPE;

        synchronized (inventory) {
            List<Optional<ItemStack>> before = inventory.snapshot();
            int selectedSlot = inventory.selectedHotbarSlot();
            for (var ingredient : recipe.ingredients().entrySet()) {
                int required;
                try {
                    required = Math.multiplyExact(ingredient.getValue(), multiplier);
                } catch (ArithmeticException exception) {
                    return Result.INVALID_MULTIPLIER;
                }
                if (inventory.count(ingredient.getKey()) < required) {
                    return Result.INSUFFICIENT_INGREDIENTS;
                }
            }

            for (var ingredient : recipe.ingredients().entrySet()) {
                int required = ingredient.getValue() * multiplier;
                if (!inventory.remove(ingredient.getKey(), required)) {
                    inventory.restore(before, selectedSlot);
                    return Result.INSUFFICIENT_INGREDIENTS;
                }
            }

            int remaining = recipe.result().count() * multiplier;
            while (remaining > 0) {
                int stackCount = Math.min(remaining, recipe.result().maxStackSize());
                int notAdded = inventory.add(new ItemStack(
                        recipe.result().itemId(), stackCount, recipe.result().maxStackSize()));
                if (notAdded != 0) {
                    inventory.restore(before, selectedSlot);
                    return Result.INVENTORY_FULL;
                }
                remaining -= stackCount;
            }
            return Result.CRAFTED;
        }
    }

    /**
     * Settles one recipe from the legacy 2x2 player crafting grid (slots 28..31).
     * Kept for callers that explicitly use the inventory-screen grid.
     */
    public Result craftFromGrid(
            PlayerInventory player, ContainerInventory grid, int gridOffset, CraftingRecipe recipe
    ) {
        return craftFromGrid(player, grid, gridOffset, 2, 2, recipe);
    }

    /**
     * Settles one recipe from a server-owned crafting grid of up to 3x3 cells.
     * Shaped recipes must match their exact pattern and position; shapeless recipes
     * are matched by ingredient counts. Every input cell is checked, so extra items
     * can never be silently consumed or ignored.
     */
    public Result craftFromGrid(
            PlayerInventory player, ContainerInventory grid, int gridOffset,
            int gridWidth, int gridHeight, CraftingRecipe recipe
    ) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(grid, "grid");
        Objects.requireNonNull(recipe, "recipe");
        if (gridWidth < 1 || gridWidth > 3 || gridHeight < 1 || gridHeight > 3
                || gridOffset < 0 || gridOffset + gridWidth * gridHeight > grid.size()) {
            return Result.INVALID_GRID;
        }

        synchronized (player) {
            synchronized (grid) {
                List<Optional<ItemStack>> playerBefore = player.snapshot();
                int selectedSlot = player.selectedHotbarSlot();
                List<Optional<ItemStack>> gridBefore = grid.snapshot();

                if (!matchesGrid(grid, gridOffset, gridWidth, gridHeight, recipe)) {
                    return Result.INSUFFICIENT_INGREDIENTS;
                }

                for (int cell = 0; cell < gridWidth * gridHeight; cell++) {
                    int gridSlot = gridOffset + cell;
                    ItemStack item = grid.getSlot(gridSlot).orElse(null);
                    if (item == null) continue;
                    grid.setSlot(gridSlot, item.count() == 1 ? null
                            : item.withCount(item.count() - 1));
                }

                int remaining = recipe.result().count();
                while (remaining > 0) {
                    int stackCount = Math.min(remaining, recipe.result().maxStackSize());
                    int notAdded = player.add(new ItemStack(
                            recipe.result().itemId(), stackCount, recipe.result().maxStackSize()));
                    if (notAdded != 0) {
                        player.restore(playerBefore, selectedSlot);
                        grid.restore(gridBefore);
                        return Result.INVENTORY_FULL;
                    }
                    remaining -= stackCount;
                }
                return Result.CRAFTED;
            }
        }
    }

    private static boolean matchesGrid(
            ContainerInventory grid, int offset, int width, int height, CraftingRecipe recipe
    ) {
        if (!recipe.isShaped()) {
            java.util.Map<String, Integer> actual = new java.util.HashMap<>();
            for (int cell = 0; cell < width * height; cell++) {
                grid.getSlot(offset + cell).ifPresent(item ->
                        actual.merge(item.itemId(), 1, Integer::sum));
            }
            return actual.equals(recipe.ingredients());
        }

        int recipeWidth = recipe.width();
        int recipeHeight = recipe.height();
        if (recipeWidth > width || recipeHeight > height) return false;

        for (int top = 0; top <= height - recipeHeight; top++) {
            for (int left = 0; left <= width - recipeWidth; left++) {
                boolean matches = true;
                for (int row = 0; row < height && matches; row++) {
                    for (int column = 0; column < width; column++) {
                        String expected = null;
                        int recipeRow = row - top;
                        int recipeColumn = column - left;
                        if (recipeRow >= 0 && recipeRow < recipeHeight
                                && recipeColumn >= 0 && recipeColumn < recipeWidth) {
                            char symbol = recipe.pattern().get(recipeRow).charAt(recipeColumn);
                            if (symbol != ' ') expected = recipe.key().get(symbol);
                        }
                        ItemStack actual = grid.getSlot(offset + row * width + column).orElse(null);
                        if (expected == null ? actual != null
                                : actual == null || !expected.equals(actual.itemId())) {
                            matches = false;
                            break;
                        }
                    }
                }
                if (matches) return true;
            }
        }
        return false;
    }

    public enum Result {
        CRAFTED,
        UNKNOWN_RECIPE,
        INVALID_MULTIPLIER,
        INSUFFICIENT_INGREDIENTS,
        INVENTORY_FULL,
        INVALID_GRID
    }
}
