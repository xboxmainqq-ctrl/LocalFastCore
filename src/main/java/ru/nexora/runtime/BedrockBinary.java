package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Bounded Bedrock wire primitives used by version-specific packet codecs.
 *
 * <p>Bedrock varint32 uses ZigZag encoding; varuint32 uses unsigned base-128.
 * Fixed-width int32 values use the byte order selected by the packet schema,
 * so this class exposes an explicit big-endian helper for LoginPacket.</p>
 */
public final class BedrockBinary {
    private BedrockBinary() {
    }

    public static int readVarInt32(ByteBuffer input) {
        int encoded = readVarUInt32(input);
        return (encoded >>> 1) ^ -(encoded & 1);
    }

    public static void writeVarInt32(ByteBuffer output, int value) {
        int encoded = (value << 1) ^ (value >> 31);
        writeVarUInt32(output, encoded);
    }

    /** Reads an unsigned 32-bit value as its raw Java int bit pattern. */
    public static int readVarUInt32(ByteBuffer input) {
        Objects.requireNonNull(input, "input");
        int result = 0;
        for (int index = 0; index < 5; index++) {
            requireRemaining(input, 1);
            int current = Byte.toUnsignedInt(input.get());
            if (index == 4 && (current & 0xF0) != 0) {
                throw new IllegalArgumentException("varuint32 exceeds 32 bits");
            }
            result |= (current & 0x7F) << (index * 7);
            if ((current & 0x80) == 0) {
                return result;
            }
        }
        throw new IllegalArgumentException("varuint32 is longer than 5 bytes");
    }

    /** Writes an unsigned 32-bit value from its raw Java int bit pattern. */
    public static void writeVarUInt32(ByteBuffer output, int value) {
        Objects.requireNonNull(output, "output");
        while ((value & 0xFFFFFF80) != 0) {
            output.put((byte) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        output.put((byte) value);
    }

    /** Reads a schema-marked big-endian signed 32-bit integer. */
    public static int readBigEndianInt32(ByteBuffer input) {
        Objects.requireNonNull(input, "input");
        requireRemaining(input, Integer.BYTES);
        return (Byte.toUnsignedInt(input.get()) << 24)
                | (Byte.toUnsignedInt(input.get()) << 16)
                | (Byte.toUnsignedInt(input.get()) << 8)
                | Byte.toUnsignedInt(input.get());
    }

    /** Writes a schema-marked big-endian signed 32-bit integer. */
    public static void writeBigEndianInt32(ByteBuffer output, int value) {
        Objects.requireNonNull(output, "output");
        output.put((byte) (value >>> 24));
        output.put((byte) (value >>> 16));
        output.put((byte) (value >>> 8));
        output.put((byte) value);
    }

    /** Reads a ZigZag-encoded signed 64-bit Bedrock varint. */
    public static long readVarInt64(ByteBuffer input) {
        long encoded = readVarUInt64(input);
        return (encoded >>> 1) ^ -(encoded & 1L);
    }

    /** Writes a ZigZag-encoded signed 64-bit Bedrock varint. */
    public static void writeVarInt64(ByteBuffer output, long value) {
        writeVarUInt64(output, (value << 1) ^ (value >> 63));
    }

    /** Reads an unsigned 64-bit base-128 integer as its raw Java long bit pattern. */
    public static long readVarUInt64(ByteBuffer input) {
        Objects.requireNonNull(input, "input");
        long result = 0;
        for (int index = 0; index < 10; index++) {
            requireRemaining(input, 1);
            int current = Byte.toUnsignedInt(input.get());
            if (index == 9 && (current & 0xFE) != 0) {
                throw new IllegalArgumentException("varuint64 exceeds 64 bits");
            }
            result |= (long) (current & 0x7F) << (index * 7);
            if ((current & 0x80) == 0) {
                return result;
            }
        }
        throw new IllegalArgumentException("varuint64 is longer than 10 bytes");
    }

    /** Writes an unsigned 64-bit base-128 integer from its raw Java long bit pattern. */
    public static void writeVarUInt64(ByteBuffer output, long value) {
        Objects.requireNonNull(output, "output");
        while ((value & ~0x7FL) != 0) {
            output.put((byte) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        output.put((byte) value);
    }

    /** Reads a schema-native-order signed 32-bit integer (little-endian for Bedrock). */
    public static int readLittleEndianInt32(ByteBuffer input) {
        Objects.requireNonNull(input, "input");
        requireRemaining(input, Integer.BYTES);
        return Byte.toUnsignedInt(input.get())
                | (Byte.toUnsignedInt(input.get()) << 8)
                | (Byte.toUnsignedInt(input.get()) << 16)
                | (input.get() << 24);
    }

    /** Writes a schema-native-order signed 32-bit integer (little-endian for Bedrock). */
    public static void writeLittleEndianInt32(ByteBuffer output, int value) {
        Objects.requireNonNull(output, "output");
        output.put((byte) value);
        output.put((byte) (value >>> 8));
        output.put((byte) (value >>> 16));
        output.put((byte) (value >>> 24));
    }

    /** Reads a schema-native-order 64-bit integer as its raw Java long bit pattern. */
    public static long readLittleEndianInt64(ByteBuffer input) {
        Objects.requireNonNull(input, "input");
        requireRemaining(input, Long.BYTES);
        long value = 0;
        for (int index = 0; index < Long.BYTES; index++) {
            value |= (long) Byte.toUnsignedInt(input.get()) << (index * 8);
        }
        return value;
    }

    /** Writes a schema-native-order 64-bit integer. */
    public static void writeLittleEndianInt64(ByteBuffer output, long value) {
        Objects.requireNonNull(output, "output");
        for (int index = 0; index < Long.BYTES; index++) {
            output.put((byte) (value >>> (index * 8)));
        }
    }

    /** Reads a schema-native-order IEEE-754 float. */
    public static float readLittleEndianFloat(ByteBuffer input) {
        return Float.intBitsToFloat(readLittleEndianInt32(input));
    }

    /** Writes a schema-native-order IEEE-754 float. */
    public static void writeLittleEndianFloat(ByteBuffer output, float value) {
        writeLittleEndianInt32(output, Float.floatToRawIntBits(value));
    }

    /**
     * Reads a UTF-8 string whose byte length is a varuint32.
     * Both the declared length and remaining input are checked before allocation.
     */
    public static String readString(ByteBuffer input, int maxBytes) {
        Objects.requireNonNull(input, "input");
        if (maxBytes < 0) {
            throw new IllegalArgumentException("maxBytes must not be negative");
        }
        int length = readVarUInt32(input);
        if (length < 0 || length > maxBytes) {
            throw new IllegalArgumentException("String byte length is outside the allowed range");
        }
        requireRemaining(input, length);
        ByteBuffer bytes = input.slice();
        bytes.limit(length);
        input.position(input.position() + length);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(bytes)
                    .toString();
        } catch (CharacterCodingException malformedUtf8) {
            throw new IllegalArgumentException("String contains malformed UTF-8", malformedUtf8);
        }
    }

    /** Writes a UTF-8 string prefixed by its byte length as varuint32. */
    public static void writeString(ByteBuffer output, String value, int maxBytes) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(value, "value");
        if (maxBytes < 0) {
            throw new IllegalArgumentException("maxBytes must not be negative");
        }
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > maxBytes) {
            throw new IllegalArgumentException("String exceeds the configured byte limit");
        }
        writeVarUInt32(output, encoded.length);
        output.put(encoded);
    }

    private static void requireRemaining(ByteBuffer input, int required) {
        if (required < 0 || input.remaining() < required) {
            throw new IllegalArgumentException("Truncated Bedrock packet data");
        }
    }
}
