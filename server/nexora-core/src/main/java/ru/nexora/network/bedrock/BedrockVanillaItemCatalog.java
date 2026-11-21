package ru.nexora.network.bedrock;

import ru.nexora.world.BlockType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cloudburstmc.nbt.NBTInputStream;
import org.cloudburstmc.nbt.NBTOutputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Full vanilla item registry sourced from the same PowerNukkitX GameData
 * release used by the 1.26.60 / protocol-2225 reference server.
 *
 * Item runtime IDs, versions and component-based flags are never hand-copied:
 * they are read from the official item_palette.json. Component NBT is wrapped
 * and serialized with Cloudburst's network-NBT writer, matching PowerNukkitX.
 */
public final class BedrockVanillaItemCatalog {
    private static final String GAME_DATA_ROOT = "gamedata/kaooot/";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Map<String, BlockType> BLOCK_ITEMS = Map.ofEntries(
            Map.entry("minecraft:stone", BlockType.STONE),
            Map.entry("minecraft:grass_block", BlockType.GRASS_BLOCK),
            Map.entry("minecraft:dirt", BlockType.DIRT),
            Map.entry("minecraft:cobblestone", BlockType.COBBLESTONE),
            Map.entry("minecraft:gravel", BlockType.GRAVEL),
            Map.entry("minecraft:glass", BlockType.GLASS),
            Map.entry("minecraft:obsidian", BlockType.OBSIDIAN),
            Map.entry("minecraft:netherrack", BlockType.NETHERRACK),
            Map.entry("minecraft:end_stone", BlockType.END_STONE),
            Map.entry("minecraft:coal_block", BlockType.COAL_BLOCK),
            Map.entry("minecraft:iron_block", BlockType.IRON_BLOCK),
            Map.entry("minecraft:gold_block", BlockType.GOLD_BLOCK),
            Map.entry("minecraft:diamond_block", BlockType.DIAMOND_BLOCK),
            Map.entry("minecraft:emerald_block", BlockType.EMERALD_BLOCK),
            Map.entry("minecraft:chest", BlockType.CHEST),
            Map.entry("minecraft:oak_log", BlockType.OAK_LOG),
            Map.entry("minecraft:oak_planks", BlockType.OAK_PLANKS),
            Map.entry("minecraft:crafting_table", BlockType.CRAFTING_TABLE),
            Map.entry("minecraft:furnace", BlockType.FURNACE),
            Map.entry("minecraft:blast_furnace", BlockType.BLAST_FURNACE),
            Map.entry("minecraft:smoker", BlockType.SMOKER)
    );

    private BedrockVanillaItemCatalog() {
    }

    public static BedrockItemRegistry createRegistry() {
        ClassLoader loader = BedrockVanillaItemCatalog.class.getClassLoader();
        try (InputStream paletteStream = loader.getResourceAsStream(GAME_DATA_ROOT + "item_palette.json");
             InputStream componentStream = loader.getResourceAsStream(GAME_DATA_ROOT + "item_components.nbt")) {
            if (paletteStream == null || componentStream == null) {
                throw new IllegalStateException("Missing PowerNukkitX protocol-2225 GameData resources");
            }

            JsonNode palette = JSON.readTree(paletteStream);
            JsonNode items = palette.path("items");
            if (!items.isArray() || items.isEmpty()) {
                throw new IllegalStateException("GameData item_palette.json contains no item entries");
            }

            NbtMap componentRoot;
            try (NBTInputStream input = NbtUtils.createGZIPReader(componentStream)) {
                Object root = input.readTag();
                if (!(root instanceof NbtMap map)) {
                    throw new IllegalStateException("GameData item_components.nbt root is not a compound");
                }
                componentRoot = map;
            }

            List<BedrockItemRegistry.Entry> entries = new ArrayList<>(items.size());
            for (JsonNode item : items) {
                String identifier = item.path("name").asText();
                if (identifier.isBlank() || !item.path("id").canConvertToInt()
                        || !item.path("version").canConvertToInt()
                        || !item.path("component_based").isBoolean()) {
                    throw new IllegalStateException("Invalid GameData item palette entry: " + item);
                }

                int runtimeId = item.path("id").intValue();
                int itemVersion = item.path("version").intValue();
                boolean componentBased = item.path("component_based").booleanValue();

                NbtMap networkTag = NbtMap.EMPTY;
                if (componentRoot.containsKey(identifier)) {
                    NbtMap itemTag = componentRoot.getCompound(identifier);
                    NbtMap components = itemTag.getCompound("components");
                    networkTag = NbtMap.builder().putCompound("components", components).build();
                }

                long blockRuntimeId = 0L;
                BlockType blockType = BLOCK_ITEMS.get(identifier);
                if (blockType != null) {
                    blockRuntimeId = Integer.toUnsignedLong(BedrockBlockRuntimeIds.forBlock(blockType));
                }

                entries.add(new BedrockItemRegistry.Entry(
                        identifier,
                        new BedrockInventoryContentCodec.ItemDefinition(
                                runtimeId, 0, blockRuntimeId, new byte[0]),
                        maxStackSize(identifier),
                        componentBased,
                        itemVersion,
                        encodeNetworkNbt(networkTag)
                ));
            }
            return new BedrockItemRegistry(entries);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load protocol-2225 GameData item registry", exception);
        }
    }

    private static byte[] encodeNetworkNbt(NbtMap tag) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (NBTOutputStream writer = NbtUtils.createNetworkWriter(output)) {
            writer.writeTag(tag);
        }
        return output.toByteArray();
    }

    private static int maxStackSize(String identifier) {
        String path = identifier.startsWith("minecraft:")
                ? identifier.substring("minecraft:".length()) : identifier;
        if (path.endsWith("_helmet") || path.endsWith("_chestplate")
                || path.endsWith("_leggings") || path.endsWith("_boots")
                || path.endsWith("_sword") || path.endsWith("_pickaxe")
                || path.endsWith("_axe") || path.endsWith("_shovel")
                || path.endsWith("_hoe") || path.endsWith("_spear")
                || path.equals("shield") || path.equals("elytra")) {
            return 1;
        }
        if (path.equals("ender_pearl") || path.equals("honey_bottle")
                || path.equals("egg") || path.equals("snowball")) {
            return 16;
        }
        return 64;
    }
}
