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
                        new ItemStack("minecraft:stick", 4, 64)),

                CraftingRecipe.shaped("minecraft:crafting_table_from_oak_planks",
                        List.of("PP", "PP"), Map.of('P', "minecraft:oak_planks"),
                        new ItemStack("minecraft:crafting_table", 1, 64)),

                // Leather armor.
                CraftingRecipe.shaped("minecraft:leather_helmet", List.of("LLL", "L L"),
                        Map.of('L', "minecraft:leather"), new ItemStack("minecraft:leather_helmet", 1, 1)),
                CraftingRecipe.shaped("minecraft:leather_chestplate", List.of("L L", "LLL", "LLL"),
                        Map.of('L', "minecraft:leather"), new ItemStack("minecraft:leather_chestplate", 1, 1)),
                CraftingRecipe.shaped("minecraft:leather_leggings", List.of("LLL", "L L", "L L"),
                        Map.of('L', "minecraft:leather"), new ItemStack("minecraft:leather_leggings", 1, 1)),
                CraftingRecipe.shaped("minecraft:leather_boots", List.of("L L", "L L"),
                        Map.of('L', "minecraft:leather"), new ItemStack("minecraft:leather_boots", 1, 1)),

                // Iron armor.
                CraftingRecipe.shaped("minecraft:iron_helmet", List.of("III", "I I"),
                        Map.of('I', "minecraft:iron_ingot"), new ItemStack("minecraft:iron_helmet", 1, 1)),
                CraftingRecipe.shaped("minecraft:iron_chestplate", List.of("I I", "III", "III"),
                        Map.of('I', "minecraft:iron_ingot"), new ItemStack("minecraft:iron_chestplate", 1, 1)),
                CraftingRecipe.shaped("minecraft:iron_leggings", List.of("III", "I I", "I I"),
                        Map.of('I', "minecraft:iron_ingot"), new ItemStack("minecraft:iron_leggings", 1, 1)),
                CraftingRecipe.shaped("minecraft:iron_boots", List.of("I I", "I I"),
                        Map.of('I', "minecraft:iron_ingot"), new ItemStack("minecraft:iron_boots", 1, 1)),

                // Golden armor.
                CraftingRecipe.shaped("minecraft:golden_helmet", List.of("GGG", "G G"),
                        Map.of('G', "minecraft:gold_ingot"), new ItemStack("minecraft:golden_helmet", 1, 1)),
                CraftingRecipe.shaped("minecraft:golden_chestplate", List.of("G G", "GGG", "GGG"),
                        Map.of('G', "minecraft:gold_ingot"), new ItemStack("minecraft:golden_chestplate", 1, 1)),
                CraftingRecipe.shaped("minecraft:golden_leggings", List.of("GGG", "G G", "G G"),
                        Map.of('G', "minecraft:gold_ingot"), new ItemStack("minecraft:golden_leggings", 1, 1)),
                CraftingRecipe.shaped("minecraft:golden_boots", List.of("G G", "G G"),
                        Map.of('G', "minecraft:gold_ingot"), new ItemStack("minecraft:golden_boots", 1, 1)),

                // Diamond armor.
                CraftingRecipe.shaped("minecraft:diamond_helmet", List.of("DDD", "D D"),
                        Map.of('D', "minecraft:diamond"), new ItemStack("minecraft:diamond_helmet", 1, 1)),
                CraftingRecipe.shaped("minecraft:diamond_chestplate", List.of("D D", "DDD", "DDD"),
                        Map.of('D', "minecraft:diamond"), new ItemStack("minecraft:diamond_chestplate", 1, 1)),
                CraftingRecipe.shaped("minecraft:diamond_leggings", List.of("DDD", "D D", "D D"),
                        Map.of('D', "minecraft:diamond"), new ItemStack("minecraft:diamond_leggings", 1, 1)),
                CraftingRecipe.shaped("minecraft:diamond_boots", List.of("D D", "D D"),
                        Map.of('D', "minecraft:diamond"), new ItemStack("minecraft:diamond_boots", 1, 1)),

                CraftingRecipe.shaped("minecraft:shield", List.of("PIP", "PPP", " P "),
                        Map.of('P', "minecraft:oak_planks", 'I', "minecraft:iron_ingot"),
                        new ItemStack("minecraft:shield", 1, 1)),
                CraftingRecipe.shaped("minecraft:bread", List.of("WWW"),
                        Map.of('W', "minecraft:wheat"), new ItemStack("minecraft:bread", 1, 64))
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
