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
        return input.getInt();
    }

    /** Writes a schema-marked big-endian signed 32-bit integer. */
    public static void writeBigEndianInt32(ByteBuffer output, int value) {
        Objects.requireNonNull(output, "output");
        output.putInt(value);
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
