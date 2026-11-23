package ru.nexora.network.bedrock;

import ru.nexora.inventory.CraftingRecipe;
import ru.nexora.inventory.CraftingRecipeRegistry;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Objects;

/**
 * Protocol-2225 CraftingData encoder.
 *
 * Recipe ingredients use Bedrock's legacy descriptor variant (not the similarly
 * named ItemStackRequest descriptor), and each recipe type has its own tail layout.
 */
public final class BedrockCraftingDataCodec {
    public static final int CRAFTING_DATA_PACKET_ID = 0x34;
    private static final int ANY_META = 32_767;

    private BedrockCraftingDataCodec() {
    }

    public static BedrockPacket createPacket(
            CraftingRecipeRegistry recipes, BedrockItemRegistry itemRegistry
    ) {
        Objects.requireNonNull(recipes, "recipes");
        Objects.requireNonNull(itemRegistry, "itemRegistry");
        Writer out = new Writer();

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
        out.byteValue(0); // retain the client's other vanilla recipes; this registry is a subset

        return new BedrockPacket(CRAFTING_DATA_PACKET_ID, 0, 0, out.toByteArray());
    }

    private static void writeShapedRecipe(
            Writer out, CraftingRecipeRegistry registry, BedrockItemRegistry itemRegistry, CraftingRecipe recipe
    ) {
        out.string(recipe.id());
        out.varInt32(recipe.width());
        out.varInt32(recipe.height());
        out.varUInt32((long) recipe.width() * recipe.height());
        for (String row : recipe.pattern()) {
            for (int i = 0; i < row.length(); i++) {
                char symbol = row.charAt(i);
                if (symbol == ' ') {
                    writeEmptyIngredient(out);
                } else {
                    writeIngredient(out, itemRegistry, recipe.key().get(symbol), 1);
                }
            }
        }
        writeRecipeResultAndTail(out, registry, itemRegistry, recipe, true);
    }

    private static void writeShapelessRecipe(
            Writer out, CraftingRecipeRegistry registry, BedrockItemRegistry itemRegistry, CraftingRecipe recipe
    ) {
        out.string(recipe.id());
        out.varUInt32(recipe.ingredients().size());
        recipe.ingredients().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> writeIngredient(out, itemRegistry, entry.getKey(), entry.getValue()));
        writeRecipeResultAndTail(out, registry, itemRegistry, recipe, false);
    }

    private static void writeIngredient(Writer out, BedrockItemRegistry itemRegistry, String itemId, int count) {
        BedrockItemRegistry.Entry ingredient = itemRegistry.findByIdentifier(itemId);
        if (ingredient == null) {
            throw new IllegalArgumentException("Unregistered recipe ingredient: " + itemId);
        }
        writeNamedIngredient(out, ingredient.namespacedId(),
                Math.toIntExact(ingredient.networkDefinition().auxValue()), count);
    }

    private static void writeNamedIngredient(Writer out, String itemId, int auxValue, int count) {
        out.varUInt32(1); // ItemDescriptor variant DEFAULT
        out.string("name");
        out.string(itemId);
        out.varInt32(auxValue);
        out.varInt32(count);
    }

    private static void writeEmptyIngredient(Writer out) {
        out.varUInt32(0); // ItemDescriptor variant INVALID
        out.varInt32(ANY_META);
        out.varInt32(0);
    }

    private static void writeItemTagIngredient(Writer out, String tag, int count) {
        out.varUInt32(1); // ItemDescriptor variant DEFAULT
        out.string("item_tag");
        out.string(tag);
        out.varInt32(ANY_META);
        out.varInt32(count);
    }

    private static void writeRecipeResultAndTail(
            Writer out, CraftingRecipeRegistry registry, BedrockItemRegistry itemRegistry,
            CraftingRecipe recipe, boolean shaped
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
        if (shaped) out.byteValue(0); // shaped recipes alone carry the symmetry flag
        out.byteValue(1); // unlocking requirement optional: present
        out.varInt32(1); // RecipeUnlockingContext.ALWAYS_UNLOCKED
        out.byteValue(0); // unlocking ingredients optional: absent
        out.varUInt32(registry.networkId(recipe.id()));
    }

    private static void writeInstanceItem(
            Writer out, BedrockInventoryContentCodec.ItemDefinition definition, int count
    ) {
        out.varInt32(definition.runtimeItemId());
        out.int16(count);
        out.varUInt32(definition.auxValue());
        out.varInt32(Math.toIntExact(definition.blockRuntimeId()));
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
            if (value < 0 || value > 0xffff_ffffL) {
                throw new IllegalArgumentException("VarUInt32 out of range");
            }
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