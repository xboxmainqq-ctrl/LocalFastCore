package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;
import ru.nexora.inventory.CraftingRecipe;
import ru.nexora.inventory.CraftingRecipeRegistry;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BedrockCraftingDataCodecTest {
    @Test
    void encodesProtocol2225ShapedAndShapelessRecipesAndRequiredSmithingTrimEntry() {
        var recipes = CraftingRecipeRegistry.vanillaSubset();
        var items = BedrockVanillaItemCatalog.createRegistry();
        BedrockPacket packet = BedrockCraftingDataCodec.createPacket(recipes, items);
        assertEquals(0x34, packet.packetId());

        Reader in = new Reader(packet.payload());
        var shapedRecipes = recipes.recipes().stream().filter(CraftingRecipe::isShaped).toList();
        assertEquals(2, in.varUInt()); // shaped recipes
        for (CraftingRecipe shaped : shapedRecipes) {
            assertEquals(shaped.id(), in.string());
            assertEquals(shaped.width(), in.varInt());
            assertEquals(shaped.height(), in.varInt());
            for (String row : shaped.pattern()) {
                for (int i = 0; i < row.length(); i++) {
                    char symbol = row.charAt(i);
                    if (symbol == ' ') {
                        assertEquals(0, in.varUInt()); // EMPTY ingredient descriptor
                    } else {
                        assertEquals(1, in.varUInt()); // DEFAULT ingredient descriptor
                        assertEquals("name", in.string());
                        assertEquals(shaped.key().get(symbol), in.string());
                        assertEquals(0, in.varInt());
                        assertEquals(1, in.varInt());
                    }
                }
            }
            assertEquals(1, in.varUInt()); // shaped result count
            var shapedOutput = items.findByIdentifier(shaped.result().itemId()).networkDefinition();
            assertEquals(shapedOutput.runtimeItemId(), in.varInt());
            assertEquals(shaped.result().count(), in.int16());
            assertEquals(shapedOutput.auxValue(), in.varUIntLong());
            assertEquals(shapedOutput.blockRuntimeId(), in.varInt());
            assertEquals(0, in.byteArray().length);
            assertEquals(shaped.uuid(), in.uuidLittleEndian());
            assertEquals("crafting_table", in.string());
            assertEquals(0, in.varInt());
            assertEquals(1, in.byteValue());
            assertEquals(1, in.varInt());
            assertEquals(0, in.byteValue());
            assertEquals(recipes.networkId(shaped.id()), in.varUInt());
        }

        assertEquals(3, in.varUInt()); // shapeless recipes
        for (CraftingRecipe recipe : recipes.recipes().stream().filter(r -> !r.isShaped()).toList()) {
            assertEquals(recipe.id(), in.string());
            assertEquals(1, in.varUInt());
            assertEquals(1, in.varUInt()); // DEFAULT item descriptor
            assertEquals("name", in.string());
            var ingredient = recipe.ingredients().keySet().iterator().next();
            assertEquals(ingredient, in.string());
            assertEquals(0, in.varInt());
            assertEquals(1, in.varInt());

            assertEquals(1, in.varUInt());
            var output = items.findByIdentifier(recipe.result().itemId()).networkDefinition();
            assertEquals(output.runtimeItemId(), in.varInt());
            assertEquals(recipe.result().count(), in.int16());
            assertEquals(output.auxValue(), in.varUIntLong());
            assertEquals(output.blockRuntimeId(), in.varInt());
            assertEquals(0, in.byteArray().length);
            assertEquals(recipe.uuid(), in.uuidLittleEndian());
            assertEquals("crafting_table", in.string());
            assertEquals(0, in.varInt());
            assertEquals(1, in.byteValue()); // requirementPresent
            assertEquals(1, in.varInt()); // ALWAYS_UNLOCKED
            assertEquals(0, in.byteValue()); // no ingredient list
            assertEquals(recipes.networkId(recipe.id()), in.varUInt());
        }

        assertEquals(0, in.varUInt()); // multi
        assertEquals(0, in.varUInt()); // user data
        assertEquals(0, in.varUInt()); // chemistry shapeless
        assertEquals(0, in.varUInt()); // chemistry shaped
        assertEquals(0, in.varUInt()); // smithing transform
        assertEquals(1, in.varUInt()); // smithing trim
        assertEquals("minecraft:smithing_armor_trim", in.string());
        for (String tag : new String[] {
                "minecraft:trim_templates", "minecraft:trimmable_armors", "minecraft:trim_materials"
        }) {
            assertEquals(1, in.varUInt());
            assertEquals("item_tag", in.string());
            assertEquals(tag, in.string());
            assertEquals(0, in.varInt());
            assertEquals(1, in.varInt());
        }
        assertEquals("smithing_table", in.string());
        assertEquals(1, in.varUInt());
        assertEquals(0, in.varUInt()); // potion
        assertEquals(0, in.varUInt()); // container
        assertEquals(0, in.varUInt()); // material reducers
        assertEquals(0, in.byteValue()); // merge with client vanilla recipes
        assertEquals(0, in.remaining());
    }

    @Test
    void rejectsRecipesWhoseItemsAreNotInTheBedrockRegistry() {
        var recipes = new CraftingRecipeRegistry(java.util.List.of(
                new CraftingRecipe("nexora:missing_item_recipe",
                        java.util.Map.of("minecraft:missing_item", 1),
                        new ru.nexora.inventory.ItemStack("minecraft:iron_nugget", 1, 64))));
        assertThrows(IllegalArgumentException.class, () ->
                BedrockCraftingDataCodec.createPacket(recipes, BedrockVanillaItemCatalog.createRegistry()));
    }

    static byte[] craftingEvent(UUID id, int inputRuntimeId, int inputCount,
                                int outputRuntimeId, int outputCount) {
        return craftingEvent(id, inputRuntimeId, inputCount, 0, outputRuntimeId, outputCount, 0);
    }

    static byte[] craftingEvent(UUID id, int inputRuntimeId, int inputCount, long inputBlockRuntimeId,
                                int outputRuntimeId, int outputCount, long outputBlockRuntimeId) {
        return craftingEventGrid(id, 0,
                new int[] {inputRuntimeId, 0, 0, 0},
                new int[] {inputCount, 0, 0, 0},
                new long[] {inputBlockRuntimeId, 0, 0, 0},
                outputRuntimeId, outputCount, outputBlockRuntimeId);
    }

    static byte[] craftingEventGrid(UUID id, int type, int[] inputRuntimeIds, int[] inputCounts,
                                    long[] inputBlockRuntimeIds, int outputRuntimeId, int outputCount,
                                    long outputBlockRuntimeId) {
        if (inputRuntimeIds.length != 4 || inputCounts.length != 4 || inputBlockRuntimeIds.length != 4) {
            throw new IllegalArgumentException("Expected four 2x2 crafting grid slots");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0); // inventory window
        writeVarInt(out, type);
        writeUuidLittleEndian(out, id);
        writeVarUInt(out, 4); // small inventory crafting grid
        for (int i = 0; i < 4; i++) {
            writeStack(out, inputRuntimeIds[i], inputCounts[i], inputBlockRuntimeIds[i]);
        }
        writeVarUInt(out, 1); // one output
        writeStack(out, outputRuntimeId, outputCount, outputBlockRuntimeId);
        return out.toByteArray();
    }

    private static void writeStack(ByteArrayOutputStream out, int runtimeId, int count, long blockRuntimeId) {
        writeShort(out, runtimeId);
        writeShort(out, count);
        writeVarUInt(out, 0); // aux
        out.write(0); // no stack net ID
        writeVarUInt(out, blockRuntimeId);
        writeVarUInt(out, 0); // user data
    }

    private static void writeUuidLittleEndian(ByteArrayOutputStream out, UUID uuid) {
        writeLongLittleEndian(out, uuid.getMostSignificantBits());
        writeLongLittleEndian(out, uuid.getLeastSignificantBits());
    }

    private static void writeLongLittleEndian(ByteArrayOutputStream out, long value) {
        for (int i = 0; i < 8; i++) out.write((int) (value >>> (i * 8)));
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        writeVarUInt(out, Integer.toUnsignedLong((value << 1) ^ (value >> 31)));
    }

    private static void writeVarUInt(ByteArrayOutputStream out, long value) {
        do {
            int next = (int) (value & 0x7f);
            value >>>= 7;
            if (value != 0) next |= 0x80;
            out.write(next);
        } while (value != 0);
    }

    private static final class Reader {
        private final byte[] data;
        private int offset;
        Reader(byte[] data) { this.data = data; }
        int byteValue() { return data[offset++] & 0xff; }
        int int16() { int value = byteValue() | (byteValue() << 8); return (short) value; }
        int varUInt() { return (int) varUIntLong(); }
        long varUIntLong() {
            long value = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                int next = byteValue();
                value |= (long) (next & 0x7f) << shift;
                if ((next & 0x80) == 0) return value;
            }
            throw new AssertionError("invalid test varuint");
        }
        int varInt() { long value = varUIntLong(); return (int) ((value >>> 1) ^ -(value & 1)); }
        String string() { return new String(byteArray(), StandardCharsets.UTF_8); }
        byte[] byteArray() {
            int length = varUInt();
            byte[] result = java.util.Arrays.copyOfRange(data, offset, offset + length);
            offset += length;
            return result;
        }
        UUID uuidLittleEndian() {
            return new UUID(longLittleEndian(), longLittleEndian());
        }
        long longLittleEndian() {
            long value = 0;
            for (int i = 0; i < 8; i++) value |= (long) byteValue() << (i * 8);
            return value;
        }
        int remaining() { return data.length - offset; }
    }
}
