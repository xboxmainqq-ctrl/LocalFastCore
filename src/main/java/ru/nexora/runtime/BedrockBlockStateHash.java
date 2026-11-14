package ru.nexora.runtime;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Computes Bedrock's FNV-1a 32-bit hash for a block-state NBT compound.
 *
 * <p>The hashed network ID is the signed int32 result of hashing the
 * little-endian NBT compound { name: string, states: compound }. Properties
 * are serialized in key order. Plain property values are NBT strings; typed
 * values may use b:, h:, i:, l:, f:, d:, or s: prefixes.</p>
 */
public final class BedrockBlockStateHash {
    private static final int FNV_OFFSET_BASIS = 0x811C9DC5;
    private static final int FNV_PRIME = 0x01000193;
    private static final int MAX_STATE_BYTES = 16 * 1024;

    private BedrockBlockStateHash() {
    }

    public static int hash(BlockState state) {
        Objects.requireNonNull(state, "state");
        if ("minecraft:unknown".equals(state.identifier())) {
            return -2; // Reserved Bedrock placeholder state.
        }

        ByteArrayOutputStream nbt = new ByteArrayOutputStream(128);
        writeByte(nbt, 10); // Root TAG_Compound.
        writeLittleEndianShort(nbt, 0); // Empty root name.
        writeNamedString(nbt, "name", state.identifier());

        writeByte(nbt, 10); // TAG_Compound "states".
        writeNbtString(nbt, "states");
        Map<String, String> properties = new TreeMap<>(state.properties());
        for (Map.Entry<String, String> property : properties.entrySet()) {
            writeProperty(nbt, property.getKey(), property.getValue());
            if (nbt.size() > MAX_STATE_BYTES) {
                throw new IllegalArgumentException("Block state NBT exceeds the size limit");
            }
        }
        writeByte(nbt, 0); // End "states".
        writeByte(nbt, 0); // End root compound.

        byte[] bytes = nbt.toByteArray();
        if (bytes.length > MAX_STATE_BYTES) {
            throw new IllegalArgumentException("Block state NBT exceeds the size limit");
        }
        int hash = FNV_OFFSET_BASIS;
        for (byte value : bytes) {
            hash ^= Byte.toUnsignedInt(value);
            hash *= FNV_PRIME; // Java int overflow is the required modulo 2^32 operation.
        }
        return hash;
    }

    private static void writeProperty(ByteArrayOutputStream out, String name, String encodedValue) {
        Objects.requireNonNull(name, "property name");
        Objects.requireNonNull(encodedValue, "property value");
        if (name.isBlank() || encodedValue.isBlank()) {
            throw new IllegalArgumentException("Block property names and values must not be blank");
        }

        if (encodedValue.length() >= 2 && encodedValue.charAt(1) == ':') {
            String value = encodedValue.substring(2);
            if (value.isEmpty()) {
                throw new IllegalArgumentException("Empty typed block property value: " + name);
            }
            switch (encodedValue.charAt(0)) {
                case 's' -> writeNamedString(out, name, value);
                case 'b' -> {
                    int parsed = parseInteger(value, Byte.MIN_VALUE, Byte.MAX_VALUE, name);
                    writeNamedTagHeader(out, 1, name);
                    writeByte(out, parsed);
                }
                case 'h' -> {
                    int parsed = parseInteger(value, Short.MIN_VALUE, Short.MAX_VALUE, name);
                    writeNamedTagHeader(out, 2, name);
                    writeLittleEndianShort(out, parsed);
                }
                case 'i' -> {
                    int parsed = parseInteger(value, Integer.MIN_VALUE, Integer.MAX_VALUE, name);
                    writeNamedTagHeader(out, 3, name);
                    writeLittleEndianInt(out, parsed);
                }
                case 'l' -> {
                    long parsed;
                    try {
                        parsed = Long.parseLong(value);
                    } catch (NumberFormatException malformed) {
                        throw new IllegalArgumentException("Invalid long block property: " + name, malformed);
                    }
                    writeNamedTagHeader(out, 4, name);
                    writeLittleEndianLong(out, parsed);
                }
                case 'f' -> {
                    float parsed;
                    try {
                        parsed = Float.parseFloat(value);
                    } catch (NumberFormatException malformed) {
                        throw new IllegalArgumentException("Invalid float block property: " + name, malformed);
                    }
                    if (!Float.isFinite(parsed)) {
                        throw new IllegalArgumentException("Non-finite float block property: " + name);
                    }
                    writeNamedTagHeader(out, 5, name);
                    writeLittleEndianInt(out, Float.floatToRawIntBits(parsed));
                }
                case 'd' -> {
                    double parsed;
                    try {
                        parsed = Double.parseDouble(value);
                    } catch (NumberFormatException malformed) {
                        throw new IllegalArgumentException("Invalid double block property: " + name, malformed);
                    }
                    if (!Double.isFinite(parsed)) {
                        throw new IllegalArgumentException("Non-finite double block property: " + name);
                    }
                    writeNamedTagHeader(out, 6, name);
                    writeLittleEndianLong(out, Double.doubleToRawLongBits(parsed));
                }
                default -> throw new IllegalArgumentException(
                        "Unknown typed block property prefix for " + name);
            }
        } else {
            writeNamedString(out, name, encodedValue);
        }
    }

    private static int parseInteger(String value, int min, int max, String name) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) {
                throw new NumberFormatException("out of range");
            }
            return parsed;
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException("Invalid integer block property: " + name, malformed);
        }
    }

    private static void writeNamedString(ByteArrayOutputStream out, String name, String value) {
        writeNamedTagHeader(out, 8, name);
        writeNbtString(out, value);
    }

    private static void writeNamedTagHeader(ByteArrayOutputStream out, int tagType, String name) {
        writeByte(out, tagType);
        writeNbtString(out, name);
    }

    private static void writeNbtString(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 0xFFFF) {
            throw new IllegalArgumentException("NBT string exceeds unsigned 16-bit length");
        }
        writeLittleEndianShort(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static void writeByte(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
    }

    private static void writeLittleEndianShort(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    private static void writeLittleEndianInt(ByteArrayOutputStream out, int value) {
        for (int i = 0; i < Integer.BYTES; i++) {
            out.write((value >>> (i * 8)) & 0xFF);
        }
    }

    private static void writeLittleEndianLong(ByteArrayOutputStream out, long value) {
        for (int i = 0; i < Long.BYTES; i++) {
            out.write((int) (value >>> (i * 8)) & 0xFF);
        }
    }
}
