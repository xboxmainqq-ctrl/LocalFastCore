package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

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
    void roundTripsSignedVarInt64Boundaries() {
        long[] values = {Long.MIN_VALUE, -9_223_372_036_854_775L, -1_000_000L,
                -1L, 0L, 1L, 1_000_000L, 9_223_372_036_854_775L, Long.MAX_VALUE};
        for (long value : values) {
            ByteBuffer buffer = ByteBuffer.allocate(10);
            BedrockBinary.writeVarInt64(buffer, value);
            buffer.flip();
            assertEquals(value, BedrockBinary.readVarInt64(buffer));
            assertFalse(buffer.hasRemaining());
        }
    }

    @Test
    void roundTripsUnsignedVarInt64BitPatterns() {
        long[] values = {0L, 1L, 127L, 128L, 255L, 16_384L,
                Long.MAX_VALUE, Long.MIN_VALUE, -1L};
        for (long value : values) {
            ByteBuffer buffer = ByteBuffer.allocate(10);
            BedrockBinary.writeVarUInt64(buffer, value);
            buffer.flip();
            assertEquals(value, BedrockBinary.readVarUInt64(buffer));
            assertFalse(buffer.hasRemaining());
        }
    }

    @Test
    void readsAndWritesNativeOrderIntegersAndFloatsIndependentlyOfBufferOrder() {
        ByteBuffer buffer = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN);
        BedrockBinary.writeLittleEndianInt32(buffer, 0x01020304);
        BedrockBinary.writeLittleEndianInt64(buffer, 0x0102030405060708L);
        BedrockBinary.writeLittleEndianFloat(buffer, 3.5f);
        assertArrayEquals(new byte[] {
                4, 3, 2, 1,
                8, 7, 6, 5, 4, 3, 2, 1,
                0, 0, 0x60, 0x40
        }, buffer.array());
        buffer.flip();
        assertEquals(0x01020304, BedrockBinary.readLittleEndianInt32(buffer));
        assertEquals(0x0102030405060708L, BedrockBinary.readLittleEndianInt64(buffer));
        assertEquals(3.5f, BedrockBinary.readLittleEndianFloat(buffer));
        assertFalse(buffer.hasRemaining());
    }

    @Test
    void rejectsOverflowingVarInt64AndTruncatedNativeOrderValues() {
        assertThrows(IllegalArgumentException.class, () -> BedrockBinary.readVarUInt64(
                ByteBuffer.wrap(new byte[] {
                        (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80,
                        (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x02
                })));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockBinary.readLittleEndianInt64(ByteBuffer.wrap(new byte[7])));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockBinary.readLittleEndianInt32(ByteBuffer.wrap(new byte[3])));
    }

    @Test
    void readsLoginProtocolAsBigEndianInt32() {
        ByteBuffer buffer = ByteBuffer.allocate(4);
        BedrockBinary.writeBigEndianInt32(buffer, 2225);
        buffer.flip();
        assertEquals(2225, BedrockBinary.readBigEndianInt32(buffer));
    }

    @Test
    void bigEndianInt32IgnoresBufferByteOrder() {
        ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        BedrockBinary.writeBigEndianInt32(buffer, 0x01020304);
        assertArrayEquals(new byte[] {1, 2, 3, 4}, buffer.array());
        buffer.flip();
        assertEquals(0x01020304, BedrockBinary.readBigEndianInt32(buffer));
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
