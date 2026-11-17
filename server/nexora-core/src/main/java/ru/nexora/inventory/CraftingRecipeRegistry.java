package ru.nexora.inventory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable recipe registry. The initial vanilla subset contains only verified items. */
public final class CraftingRecipeRegistry {
    private final Map<String, CraftingRecipe> byId;

    public CraftingRecipeRegistry(List<CraftingRecipe> recipes) {
        Objects.requireNonNull(recipes, "recipes");
        Map<String, CraftingRecipe> index = new LinkedHashMap<>();
        for (CraftingRecipe recipe : recipes) {
            Objects.requireNonNull(recipe, "recipe");
            if (index.putIfAbsent(recipe.id(), recipe) != null) {
                throw new IllegalArgumentException("Duplicate crafting recipe ID: " + recipe.id());
            }
        }
        byId = Map.copyOf(index);
    }

    public static CraftingRecipeRegistry vanillaSubset() {
        return new CraftingRecipeRegistry(List.of(
                new CraftingRecipe("minecraft:iron_nuggets_from_iron_ingot",
                        Map.of("minecraft:iron_ingot", 1),
                        new ItemStack("minecraft:iron_nugget", 9, 64)),
                new CraftingRecipe("minecraft:gold_nuggets_from_gold_ingot",
                        Map.of("minecraft:gold_ingot", 1),
                        new ItemStack("minecraft:gold_nugget", 9, 64))
        ));
    }

    public CraftingRecipe find(String id) {
        return byId.get(id);
    }

    public List<CraftingRecipe> recipes() {
        return List.copyOf(byId.values());
    }

    public int size() {
        return byId.size();
    }
}
