package ru.nexora.network.bedrock;

import ru.nexora.world.BlockType;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Protocol-2225 block network IDs: FNV-1a hashes of little-endian vanilla block-state NBT. */
public final class BedrockBlockRuntimeIds {
    private BedrockBlockRuntimeIds() {
    }

    public static int forBlock(BlockType type) {
        return switch (type) {
            case AIR -> hashState("minecraft:air");
            case STONE -> hashState("minecraft:stone");
            case DIRT -> hashState("minecraft:dirt");
            case GRASS_BLOCK -> hashState("minecraft:grass_block");
            case BEDROCK -> hashState("minecraft:bedrock", "infiniburn_bit", false);
        };
    }

    static int hashState(String name) {
        return hashStateNbt(name, null, false);
    }

    static int hashState(String name, String property, boolean value) {
        return hashStateNbt(name, property, value);
    }

    private static int hashStateNbt(String name, String property, boolean value) {
        ByteArrayOutputStream nbt = new ByteArrayOutputStream(96);
        nbt.write(10); // Root TAG_Compound
        writeString(nbt, "");
        nbt.write(8);  // TAG_String "name"
        writeString(nbt, "name");
        writeString(nbt, name);
        nbt.write(10); // TAG_Compound "states"
        writeString(nbt, "states");
        if (property != null) {
            nbt.write(1); // TAG_Byte for boolean block-state properties
            writeString(nbt, property);
            nbt.write(value ? 1 : 0);
        }
        nbt.write(0); // End states compound
        nbt.write(0); // End root compound

        int hash = 0x811c9dc5;
        for (byte element : nbt.toByteArray()) {
            hash ^= Byte.toUnsignedInt(element);
            hash *= 0x01000193;
        }
        return hash;
    }

    private static void writeString(ByteArrayOutputStream output, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 0xffff) throw new IllegalArgumentException("NBT string is too long");
        output.write(bytes.length & 0xff);
        output.write((bytes.length >>> 8) & 0xff);
        output.writeBytes(bytes);
    }
}
