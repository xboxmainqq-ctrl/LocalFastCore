package ru.nexora.inventory;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable recipe registry. The initial vanilla subset contains only verified items. */
public final class CraftingRecipeRegistry {
    private final Map<String, CraftingRecipe> byId;
    private final List<CraftingRecipe> ordered;

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
        List<CraftingRecipe> sorted = new ArrayList<>(index.values());
        sorted.sort(Comparator.comparing(CraftingRecipe::id));
        ordered = List.copyOf(sorted);
    }

    public static CraftingRecipeRegistry vanillaSubset() {
        return new CraftingRecipeRegistry(List.of(
                new CraftingRecipe("minecraft:iron_nuggets_from_iron_ingot",
                        Map.of("minecraft:iron_ingot", 1),
                        new ItemStack("minecraft:iron_nugget", 9, 64)),
                new CraftingRecipe("minecraft:gold_nuggets_from_gold_ingot",
                        Map.of("minecraft:gold_ingot", 1),
                        new ItemStack("minecraft:gold_nugget", 9, 64)),
                new CraftingRecipe("minecraft:oak_planks_from_oak_log",
                        Map.of("minecraft:oak_log", 1),
                        new ItemStack("minecraft:oak_planks", 4, 64)),
                CraftingRecipe.shaped("minecraft:sticks_from_oak_planks",
                        List.of("P", "P"), Map.of('P', "minecraft:oak_planks"),
                        new ItemStack("minecraft:stick", 4, 64))
        ));
    }

    public CraftingRecipe find(String id) {
        return byId.get(id);
    }

    public List<CraftingRecipe> recipes() {
        return ordered;
    }

    public CraftingRecipe findByUuid(UUID uuid) {
        if (uuid == null) return null;
        return ordered.stream().filter(recipe -> recipe.uuid().equals(uuid)).findFirst().orElse(null);
    }

    public int networkId(String recipeId) {
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).id().equals(recipeId)) return 1001 + i;
        }
        return 0;
    }

    public int size() {
        return byId.size();
    }
}
