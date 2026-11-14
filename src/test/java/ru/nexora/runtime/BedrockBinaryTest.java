package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class BedrockBinaryTest {
    @Test
    void roundTripsSignedVarInt32Boundaries() {
        int[] values = {Integer.MIN_VALUE, -1_000_000, -1, 0, 1, 64, 1_000_000, Integer.MAX_VALUE};
        for (int value : values) {
            ByteBuffer buffer = ByteBuffer.allocate(5);
            BedrockBinary.writeVarInt32(buffer, value);
            buffer.flip();
            assertEquals(value, BedrockBinary.readVarInt32(buffer));
            assertFalse(buffer.hasRemaining());
        }
    }

    @Test
    void roundTripsUnsignedVarInt32BitPatterns() {
        int[] values = {0, 1, 127, 128, 255, 16384, Integer.MAX_VALUE, -1};
        for (int value : values) {
            ByteBuffer buffer = ByteBuffer.allocate(5);
            BedrockBinary.writeVarUInt32(buffer, value);
            buffer.flip();
            assertEquals(value, BedrockBinary.readVarUInt32(buffer));
            assertFalse(buffer.hasRemaining());
        }
    }

    @Test
    void readsLoginProtocolAsBigEndianInt32() {
        ByteBuffer buffer = ByteBuffer.allocate(4);
        BedrockBinary.writeBigEndianInt32(buffer, 2225);
        buffer.flip();
        assertEquals(2225, BedrockBinary.readBigEndianInt32(buffer));
    }

    @Test
    void roundTripsUtf8StringsByByteLength() {
        String value = "NEXORA — мир 🌍";
        ByteBuffer buffer = ByteBuffer.allocate(128);
        BedrockBinary.writeString(buffer, value, 100);
        buffer.flip();
        assertEquals(value, BedrockBinary.readString(buffer, 100));
        assertFalse(buffer.hasRemaining());
    }

    @Test
    void rejectsTruncatedAndOversizedValues() {
        assertThrows(IllegalArgumentException.class,
                () -> BedrockBinary.readVarUInt32(ByteBuffer.wrap(new byte[] {(byte) 0x80})));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockBinary.readVarUInt32(ByteBuffer.wrap(
                        new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x10})));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockBinary.readString(ByteBuffer.wrap(new byte[] {5, 1, 2}), 10));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockBinary.writeString(ByteBuffer.allocate(8), "too long", 2));
    }

    @Test
    void rejectsMalformedUtf8() {
        ByteBuffer invalid = ByteBuffer.wrap(new byte[] {2, (byte) 0xC3, 0x28});
        assertThrows(IllegalArgumentException.class, () -> BedrockBinary.readString(invalid, 10));
    }
}
