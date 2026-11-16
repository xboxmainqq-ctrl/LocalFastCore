package ru.nexora.runtime;

import java.util.Map;
import java.util.Objects;

/** Resolves the basic survival item produced by breaking a world block. */
public final class BlockDropTable {
    private static final Map<String, String> DROPS = Map.ofEntries(
            Map.entry("minecraft:stone", "minecraft:cobblestone"),
            Map.entry("minecraft:grass_block", "minecraft:dirt"),
            Map.entry("minecraft:dirt", "minecraft:dirt"),
            Map.entry("minecraft:coarse_dirt", "minecraft:coarse_dirt"),
            Map.entry("minecraft:podzol", "minecraft:podzol"),
            Map.entry("minecraft:cobblestone", "minecraft:cobblestone"),
            Map.entry("minecraft:deepslate", "minecraft:cobbled_deepslate"),
            Map.entry("minecraft:cobbled_deepslate", "minecraft:cobbled_deepslate"),
            Map.entry("minecraft:oak_log", "minecraft:oak_log"),
            Map.entry("minecraft:spruce_log", "minecraft:spruce_log"),
            Map.entry("minecraft:birch_log", "minecraft:birch_log"),
            Map.entry("minecraft:jungle_log", "minecraft:jungle_log"),
            Map.entry("minecraft:acacia_log", "minecraft:acacia_log"),
            Map.entry("minecraft:dark_oak_log", "minecraft:dark_oak_log"),
            Map.entry("minecraft:mangrove_log", "minecraft:mangrove_log"),
            Map.entry("minecraft:cherry_log", "minecraft:cherry_log"),
            Map.entry("minecraft:coal_ore", "minecraft:coal"),
            Map.entry("minecraft:deepslate_coal_ore", "minecraft:coal"),
            Map.entry("minecraft:iron_ore", "minecraft:raw_iron"),
            Map.entry("minecraft:deepslate_iron_ore", "minecraft:raw_iron"),
            Map.entry("minecraft:copper_ore", "minecraft:raw_copper"),
            Map.entry("minecraft:deepslate_copper_ore", "minecraft:raw_copper"),
            Map.entry("minecraft:gold_ore", "minecraft:raw_gold"),
            Map.entry("minecraft:deepslate_gold_ore", "minecraft:raw_gold"),
            Map.entry("minecraft:diamond_ore", "minecraft:diamond"),
            Map.entry("minecraft:deepslate_diamond_ore", "minecraft:diamond"),
            Map.entry("minecraft:emerald_ore", "minecraft:emerald"),
            Map.entry("minecraft:deepslate_emerald_ore", "minecraft:emerald"),
            Map.entry("minecraft:lapis_ore", "minecraft:lapis_lazuli"),
            Map.entry("minecraft:deepslate_lapis_ore", "minecraft:lapis_lazuli"),
            Map.entry("minecraft:redstone_ore", "minecraft:redstone"),
            Map.entry("minecraft:deepslate_redstone_ore", "minecraft:redstone")
    );

    private BlockDropTable() {
    }

    /** Returns the item identifier for a basic block drop, or null for no drop. */
    public static String dropFor(BlockState state) {
        Objects.requireNonNull(state, "state");
        return DROPS.get(state.identifier());
    }
}
