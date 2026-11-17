package ru.nexora.inventory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable shapeless ingredient-count recipe used by the server crafting engine. */
public record CraftingRecipe(String id, Map<String, Integer> ingredients, ItemStack result) {
    public CraftingRecipe {
        if (id == null || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Crafting recipe ID must be namespaced");
        }
        Objects.requireNonNull(ingredients, "ingredients");
        Objects.requireNonNull(result, "result");
        if (ingredients.isEmpty() || ingredients.size() > 9) {
            throw new IllegalArgumentException("Recipe must have 1..9 ingredient types");
        }
        Map<String, Integer> validated = new LinkedHashMap<>();
        for (var entry : ingredients.entrySet()) {
            String itemId = entry.getKey();
            Integer count = entry.getValue();
            if (itemId == null || !itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                    || count == null || count < 1 || count > 64) {
                throw new IllegalArgumentException("Invalid crafting ingredient");
            }
            validated.put(itemId, count);
        }
        ingredients = Map.copyOf(validated);
    }
}
