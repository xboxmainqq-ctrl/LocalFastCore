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

    public enum Result {
        CRAFTED,
        UNKNOWN_RECIPE,
        INVALID_MULTIPLIER,
        INSUFFICIENT_INGREDIENTS,
        INVENTORY_FULL
    }
}
