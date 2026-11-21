package ru.nexora.network.bedrock;

import ru.nexora.world.BlockType;

import java.util.ArrayList;
import java.util.List;

/**
 * Curated protocol-2225 vanilla item subset with verified runtime IDs, including common stackable items, placeable blocks and armor. Component-based items are excluded until their real component NBT is available.
 *
 * IDs and ItemVersion values are transcribed from the protocol-2225 item-state
 * palette (runtime_item_states_2225.json, Nukkit-MOT reference revision
 * 7323f582e864782934d4c405505551f79f4ed10e). Only non-component items with no block-state payload are included. Armor and shields
 * use a maximum stack size of one. This is intentionally not a complete vanilla catalog.
 */
public final class BedrockVanillaItemCatalog {
    private static final byte[] EMPTY_NETWORK_NBT = {10, 0, 0};

    private BedrockVanillaItemCatalog() {
    }

    public static BedrockItemRegistry createRegistry() {
        List<BedrockItemRegistry.Entry> entries = new ArrayList<>();
        addBlock(entries, "stone", 1, BlockType.STONE);
        addBlock(entries, "grass_block", 2, BlockType.GRASS_BLOCK);
        addBlock(entries, "dirt", 3, BlockType.DIRT);
        addBlock(entries, "cobblestone", 4, BlockType.COBBLESTONE);
        addBlock(entries, "gravel", 13, BlockType.GRAVEL);
        addBlock(entries, "glass", 20, BlockType.GLASS);
        addBlock(entries, "obsidian", 49, BlockType.OBSIDIAN);
        addBlock(entries, "netherrack", 87, BlockType.NETHERRACK);
        addBlock(entries, "end_stone", 121, BlockType.END_STONE);
        addBlock(entries, "coal_block", 173, BlockType.COAL_BLOCK);
        addBlock(entries, "iron_block", 42, BlockType.IRON_BLOCK);
        addBlock(entries, "gold_block", 41, BlockType.GOLD_BLOCK);
        addBlock(entries, "diamond_block", 57, BlockType.DIAMOND_BLOCK);
        addBlock(entries, "emerald_block", 133, BlockType.EMERALD_BLOCK);
        addBlock(entries, "chest", 54, BlockType.CHEST);
        addBlock(entries, "oak_log", 17, BlockType.OAK_LOG);
        addBlock(entries, "oak_planks", 5, BlockType.OAK_PLANKS);
        addBlock(entries, "crafting_table", 58, BlockType.CRAFTING_TABLE);
        addBlock(entries, "furnace", 61, BlockType.FURNACE);
        addBlock(entries, "blast_furnace", -196, BlockType.BLAST_FURNACE);
        addBlock(entries, "smoker", -198, BlockType.SMOKER);
        add(entries, "diamond", 306, 2, 64);
        add(entries, "iron_ingot", 307, 2, 64);
        add(entries, "gold_ingot", 308, 2, 64);
        add(entries, "emerald", 523, 2, 64);
        add(entries, "stick", 323, 2, 64);
        add(entries, "lapis_lazuli", 417, 2, 64);
        add(entries, "redstone", 376, 2, 64);
        add(entries, "amethyst_shard", 639, 2, 64);
        add(entries, "quartz", 534, 2, 64);
        add(entries, "netherite_ingot", 624, 2, 64);
        add(entries, "copper_ingot", 515, 2, 64);
        add(entries, "wheat", 337, 2, 64);
        add(entries, "bread", 261, 0, 64);
        add(entries, "carrot", 279, 0, 64);
        add(entries, "potato", 280, 0, 64);
        add(entries, "baked_potato", 281, 0, 64);
        add(entries, "bone", 418, 2, 64);
        add(entries, "rotten_flesh", 277, 0, 64);
        add(entries, "leather", 384, 2, 64);
        add(entries, "paper", 389, 2, 64);
        add(entries, "book", 390, 2, 64);
        add(entries, "glass_bottle", 431, 2, 64);
        add(entries, "ender_pearl", 425, 2, 16);
        add(entries, "blaze_rod", 426, 2, 64);
        add(entries, "ghast_tear", 428, 2, 64);
        add(entries, "slime_ball", 391, 2, 64);
        add(entries, "magma_cream", 434, 2, 64);
        add(entries, "gunpowder", 331, 2, 64);
        add(entries, "feather", 330, 2, 64);
        add(entries, "flint", 359, 2, 64);
        add(entries, "raw_iron", 516, 2, 64);
        add(entries, "raw_gold", 517, 2, 64);
        add(entries, "raw_copper", 518, 2, 64);
        add(entries, "iron_nugget", 579, 2, 64);
        add(entries, "gold_nugget", 429, 2, 64);
        add(entries, "heart_of_the_sea", 581, 2, 64);
        add(entries, "nautilus_shell", 580, 2, 64);
        add(entries, "prismarine_shard", 575, 2, 64);
        add(entries, "prismarine_crystals", 559, 2, 64);
        add(entries, "phantom_membrane", 584, 2, 64);
        add(entries, "shulker_shell", 576, 2, 64);
        add(entries, "experience_bottle", 519, 2, 64);
        add(entries, "fire_charge", 520, 2, 64);
        add(entries, "firework_rocket", 529, 2, 64);
        add(entries, "cookie", 271, 0, 64);
        add(entries, "cooked_beef", 274, 0, 64);
        add(entries, "beef", 273, 0, 64);
        add(entries, "chicken", 275, 0, 64);
        add(entries, "cooked_chicken", 276, 0, 64);
        add(entries, "salmon", 265, 0, 64);
        add(entries, "cooked_salmon", 269, 0, 64);
        add(entries, "cod", 264, 0, 64);
        add(entries, "cooked_cod", 268, 0, 64);
        add(entries, "poisonous_potato", 282, 0, 64);
        add(entries, "pumpkin_pie", 284, 0, 64);
        add(entries, "melon_slice", 272, 0, 64);
        add(entries, "sweet_berries", 287, 0, 64);
        add(entries, "glow_berries", 884, 0, 64);
        add(entries, "honey_bottle", 604, 0, 16);
        add(entries, "honeycomb", 603, 0, 64);
        add(entries, "nether_wart", 294, 0, 64);
        add(entries, "rabbit_foot", 538, 2, 64);
        add(entries, "rabbit_hide", 539, 2, 64);
        add(entries, "rabbit", 288, 0, 64);
        add(entries, "cooked_rabbit", 289, 0, 64);
        add(entries, "mutton", 560, 0, 64);
        add(entries, "cooked_mutton", 561, 0, 64);
        add(entries, "ink_sac", 416, 2, 64);
        add(entries, "glow_ink_sac", 514, 2, 64);
        add(entries, "leather_helmet", 338, 2, 1);
        add(entries, "leather_chestplate", 339, 2, 1);
        add(entries, "leather_leggings", 340, 2, 1);
        add(entries, "leather_boots", 341, 2, 1);
        add(entries, "chainmail_helmet", 342, 2, 1);
        add(entries, "chainmail_chestplate", 343, 2, 1);
        add(entries, "chainmail_leggings", 344, 2, 1);
        add(entries, "chainmail_boots", 345, 2, 1);
        add(entries, "iron_helmet", 346, 2, 1);
        add(entries, "iron_chestplate", 347, 2, 1);
        add(entries, "iron_leggings", 348, 2, 1);
        add(entries, "iron_boots", 349, 2, 1);
        add(entries, "diamond_helmet", 350, 2, 1);
        add(entries, "diamond_chestplate", 351, 2, 1);
        add(entries, "diamond_leggings", 352, 2, 1);
        add(entries, "diamond_boots", 353, 2, 1);
        add(entries, "golden_helmet", 354, 2, 1);
        add(entries, "golden_chestplate", 355, 2, 1);
        add(entries, "golden_leggings", 356, 2, 1);
        add(entries, "golden_boots", 357, 2, 1);
        add(entries, "netherite_helmet", 625, 2, 1);
        add(entries, "netherite_chestplate", 626, 2, 1);
        add(entries, "netherite_leggings", 627, 2, 1);
        add(entries, "netherite_boots", 628, 2, 1);
        add(entries, "turtle_helmet", 583, 2, 1);
        add(entries, "elytra", 574, 2, 1);
        add(entries, "shield", 358, 2, 1);
        return new BedrockItemRegistry(entries);
    }

    private static void addBlock(List<BedrockItemRegistry.Entry> entries,
                                 String path, int runtimeId, BlockType blockType) {
        entries.add(new BedrockItemRegistry.Entry(
                "minecraft:" + path,
                new BedrockInventoryContentCodec.ItemDefinition(
                        runtimeId, 0, Integer.toUnsignedLong(BedrockBlockRuntimeIds.forBlock(blockType)), new byte[0]),
                64, false, 2, EMPTY_NETWORK_NBT
        ));
    }

    private static void add(List<BedrockItemRegistry.Entry> entries,
                            String path, int runtimeId, int itemVersion, int maxStackSize) {
        entries.add(new BedrockItemRegistry.Entry(
                "minecraft:" + path,
                new BedrockInventoryContentCodec.ItemDefinition(runtimeId, 0, 0, new byte[0]),
                maxStackSize,
                false,
                itemVersion,
                EMPTY_NETWORK_NBT
        ));
    }
}
