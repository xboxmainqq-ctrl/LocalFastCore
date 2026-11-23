package ru.nexora.network.bedrock;

import ru.nexora.inventory.CraftingRecipe;
import ru.nexora.inventory.CraftingRecipeRegistry;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Objects;

/** Protocol-2225 CraftingData encoder for supported shaped and shapeless recipes. */
public final class BedrockCraftingDataCodec {
    public static final int CRAFTING_DATA_PACKET_ID = 0x34;

    private BedrockCraftingDataCodec() {
    }

    public static BedrockPacket createPacket(
            CraftingRecipeRegistry recipes, BedrockItemRegistry itemRegistry
    ) {
        Objects.requireNonNull(recipes, "recipes");
        Objects.requireNonNull(itemRegistry, "itemRegistry");
        Writer out = new Writer();

        // Protocol 2225 (1.26.40+) uses the typed v2168 recipe arrays.
        var ordered = recipes.recipes().stream().sorted(Comparator.comparing(CraftingRecipe::id)).toList();
        var shaped = ordered.stream().filter(CraftingRecipe::isShaped).toList();
        var shapeless = ordered.stream().filter(recipe -> !recipe.isShaped()).toList();
        out.varUInt32(shaped.size());
        for (CraftingRecipe recipe : shaped) writeShapedRecipe(out, recipes, itemRegistry, recipe);
        out.varUInt32(shapeless.size());
        for (CraftingRecipe recipe : shapeless) writeShapelessRecipe(out, recipes, itemRegistry, recipe);

        out.varUInt32(0); // multi recipes
        out.varUInt32(0); // shapeless user-data recipes
        out.varUInt32(0); // shapeless chemistry recipes
        out.varUInt32(0); // shaped chemistry recipes
        out.varUInt32(0); // smithing transform recipes

        // Bedrock 1.26.40+ expects the built-in armor-trim recipe entry.
        out.varUInt32(1);
        out.string("minecraft:smithing_armor_trim");
        writeItemTagIngredient(out, "minecraft:trim_templates", 1);
        writeItemTagIngredient(out, "minecraft:trimmable_armors", 1);
        writeItemTagIngredient(out, "minecraft:trim_materials", 1);
        out.string("smithing_table");
        out.varUInt32(1); // reserved smithing trim network ID

        out.varUInt32(0); // potion mix recipes
        out.varUInt32(0); // container mix recipes
        out.varUInt32(0); // material reducers
        out.byteValue(0); // merge with the client's vanilla recipe catalog; this packet is only a subset

        return new BedrockPacket(CRAFTING_DATA_PACKET_ID, 0, 0, out.toByteArray());
    }

    private static void writeShapedRecipe(
            Writer out, CraftingRecipeRegistry registry, BedrockItemRegistry itemRegistry, CraftingRecipe recipe
    ) {
        out.string(recipe.id());
        out.varInt32(recipe.width());
        out.varInt32(recipe.height());
        for (String row : recipe.pattern()) {
            for (int i = 0; i < row.length(); i++) {
                char symbol = row.charAt(i);
                if (symbol == ' ') {
                    out.varUInt32(0); // EMPTY recipe ingredient descriptor
                } else {
                    writeIngredient(out, itemRegistry, recipe.key().get(symbol), 1);
                }
            }
        }
        writeRecipeResultAndTail(out, registry, itemRegistry, recipe);
    }

    private static void writeShapelessRecipe(
            Writer out, CraftingRecipeRegistry registry, BedrockItemRegistry itemRegistry, CraftingRecipe recipe
    ) {
        out.string(recipe.id());
        out.varUInt32(recipe.ingredients().size());
        recipe.ingredients().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> writeIngredient(out, itemRegistry, entry.getKey(), entry.getValue()));
        writeRecipeResultAndTail(out, registry, itemRegistry, recipe);
    }

    private static void writeIngredient(Writer out, BedrockItemRegistry itemRegistry, String itemId, int count) {
        BedrockItemRegistry.Entry ingredient = itemRegistry.findByIdentifier(itemId);
        if (ingredient == null) {
            throw new IllegalArgumentException("Unregistered recipe ingredient: " + itemId);
        }
        writeRecipeIngredient(out, ingredient.namespacedId(),
                ingredient.networkDefinition().auxValue(), count);
    }

    private static void writeRecipeResultAndTail(
            Writer out, CraftingRecipeRegistry registry, BedrockItemRegistry itemRegistry, CraftingRecipe recipe
    ) {
        out.varUInt32(1); // result count
        BedrockItemRegistry.Entry result = itemRegistry.findByIdentifier(recipe.result().itemId());
        if (result == null) {
            throw new IllegalArgumentException("Unregistered recipe result: " + recipe.result().itemId());
        }
        writeInstanceItem(out, result.networkDefinition(), recipe.result().count());
        out.uuidLittleEndian(recipe.uuid());
        out.string("crafting_table");
        out.varInt32(0); // priority
        out.byteValue(0); // assume symmetry
        out.byteValue(1); // unlocking requirement present
        out.varInt32(1); // ALWAYS_UNLOCKED; no ingredient list follows this context
        out.varUInt32(registry.networkId(recipe.id()));
    }

    private static void writeRecipeIngredient(Writer out, String itemId, long auxValue, int count) {
        out.byteValue(1); // ItemDescriptorType.ITEM_NAME
        out.string(itemId);
        out.varInt32((int) auxValue);
        out.varInt32(count);
    }

    private static void writeItemTagIngredient(Writer out, String tag, int count) {
        out.byteValue(3); // ItemDescriptorType.ITEM_TAG
        out.string(tag);
        out.varInt32(0);
        out.varInt32(count);
    }

    private static void writeInstanceItem(Writer out,
            BedrockInventoryContentCodec.ItemDefinition definition, int count) {
        out.varInt32(definition.runtimeItemId());
        out.int16(count);
        out.varUInt32(definition.auxValue());
        out.varInt32((int) definition.blockRuntimeId());
        out.byteArray(definition.userData());
    }

    private static final class Writer {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream(512);

        void byteValue(int value) {
            output.write(value & 0xff);
        }

        void int16(int value) {
            byteValue(value);
            byteValue(value >>> 8);
        }

        void varUInt32(long value) {
            if (value < 0 || value > 0xffff_ffffL) throw new IllegalArgumentException("VarUInt32 out of range");
            do {
                int next = (int) (value & 0x7f);
                value >>>= 7;
                if (value != 0) next |= 0x80;
                byteValue(next);
            } while (value != 0);
        }

        void varInt32(int value) {
            varUInt32(Integer.toUnsignedLong((value << 1) ^ (value >> 31)));
        }

        void string(String value) {
            byteArray(value.getBytes(StandardCharsets.UTF_8));
        }

        void byteArray(byte[] value) {
            varUInt32(value.length);
            output.writeBytes(value);
        }

        void uuidLittleEndian(java.util.UUID uuid) {
            long first = uuid.getMostSignificantBits();
            long second = uuid.getLeastSignificantBits();
            for (int i = 0; i < 8; i++) byteValue((int) (first >>> (i * 8)));
            for (int i = 0; i < 8; i++) byteValue((int) (second >>> (i * 8)));
        }

        byte[] toByteArray() {
            return output.toByteArray();
        }
    }
}
