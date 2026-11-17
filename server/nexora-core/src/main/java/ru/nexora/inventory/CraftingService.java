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
     * Settles one recipe from the server-owned 2x2 crafting grid into the player inventory.
     * The grid offset is 28 for the inventory crafting grid and the grid itself is not persisted.
     */
    public Result craftFromGrid(
            PlayerInventory player, ContainerInventory grid, int gridOffset, CraftingRecipe recipe
    ) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(grid, "grid");
        Objects.requireNonNull(recipe, "recipe");
        if (gridOffset < 0 || gridOffset + 4 > grid.size()) return Result.INVALID_GRID;

        synchronized (player) {
            synchronized (grid) {
                List<Optional<ItemStack>> playerBefore = player.snapshot();
                int selectedSlot = player.selectedHotbarSlot();
                List<Optional<ItemStack>> gridBefore = grid.snapshot();

                java.util.Map<String, Integer> actual = new java.util.HashMap<>();
                for (int slot = 0; slot < 4; slot++) {
                    Optional<ItemStack> item = grid.getSlot(gridOffset + slot);
                    if (item.isPresent()) actual.merge(item.orElseThrow().itemId(), 1, Integer::sum);
                }
                if (!actual.equals(recipe.ingredients())) return Result.INSUFFICIENT_INGREDIENTS;

                for (int slot = 0; slot < 4; slot++) {
                    int gridSlot = gridOffset + slot;
                    ItemStack item = grid.getSlot(gridSlot).orElse(null);
                    if (item == null) continue;
                    grid.setSlot(gridSlot, item.count() == 1 ? null
                            : new ItemStack(item.itemId(), item.count() - 1, item.maxStackSize()));
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

    public enum Result {
        CRAFTED,
        UNKNOWN_RECIPE,
        INVALID_MULTIPLIER,
        INSUFFICIENT_INGREDIENTS,
        INVENTORY_FULL,
        INVALID_GRID
    }
}
