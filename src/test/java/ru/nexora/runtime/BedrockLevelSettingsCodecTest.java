package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class BedrockLevelSettingsCodecTest {
    @Test
    void serializesAllProtocol2225LevelSettingsFieldsInSchemaOrder() {
        var settings = new BedrockLevelSettingsCodec.Settings(
                0x0102030405060708L, 0,
                new BedrockStartGamePrimitivesCodec.BlockPosition(0, 64, 0),
                2, -1, 4, "1.26.60");
        ByteBuffer data = BedrockLevelSettingsCodec.encodeMinimalSurvivalFlat(settings);

        assertEquals(0x0102030405060708L, BedrockBinary.readLittleEndianInt64(data));
        assertEquals(0, readInt16LE(data)); // default spawn biome
        assertEquals("", BedrockBinary.readString(data, 256));
        assertEquals(0, BedrockBinary.readVarInt32(data)); // overworld
        assertEquals(2, BedrockBinary.readVarInt32(data)); // flat generator
        assertEquals(0, BedrockBinary.readVarInt32(data)); // survival
        assertFalse(readBoolean(data)); // hardcore
        assertEquals(2, BedrockBinary.readVarInt32(data)); // normal difficulty
        assertEquals(new BedrockStartGamePrimitivesCodec.BlockPosition(0, 64, 0),
                BedrockStartGamePrimitivesCodec.readBlockPosition(data));
        assertTrue(readBoolean(data)); // achievements disabled
        assertEquals(0, BedrockBinary.readVarInt32(data)); // non-editor
        assertFalse(readBoolean(data));
        assertFalse(readBoolean(data));
        assertEquals(0, data.get()); // editor migration version
        assertEquals(-1, BedrockBinary.readVarInt32(data)); // day-cycle stop
        assertEquals(0, BedrockBinary.readVarUInt32(data)); // education offer
        assertFalse(readBoolean(data));
        assertEquals("", BedrockBinary.readString(data, 256));
        assertEquals(0.0f, BedrockBinary.readLittleEndianFloat(data));
        assertEquals(0.0f, BedrockBinary.readLittleEndianFloat(data));
        assertFalse(readBoolean(data));
        assertTrue(readBoolean(data)); // multiplayer intent
        assertFalse(readBoolean(data)); // LAN intent
        assertEquals(0, BedrockBinary.readVarInt32(data));
        assertEquals(0, BedrockBinary.readVarInt32(data));
        assertTrue(readBoolean(data)); // commands
        assertFalse(readBoolean(data)); // texture packs
        assertEquals(0, BedrockBinary.readVarUInt32(data)); // empty rules
        assertEquals(0, BedrockBinary.readLittleEndianInt32(data)); // empty experiments
        assertFalse(readBoolean(data));
        assertFalse(readBoolean(data));
        assertFalse(readBoolean(data));
        assertEquals(1, data.get()); // member permissions
        assertEquals(4, BedrockBinary.readLittleEndianInt32(data));
        for (int i = 31; i <= 40; i++) assertFalse(readBoolean(data));
        assertEquals("1.26.60", BedrockBinary.readString(data, 64));
        assertEquals(0, BedrockBinary.readLittleEndianInt32(data));
        assertEquals(0, BedrockBinary.readLittleEndianInt32(data));
        assertFalse(readBoolean(data));
        assertEquals("", BedrockBinary.readString(data, 256));
        assertEquals("", BedrockBinary.readString(data, 2048));
        assertFalse(readBoolean(data)); // optional override absent
        assertEquals(0, data.get()); // chat restriction none
        assertFalse(readBoolean(data));
        assertEquals(0, BedrockBinary.readVarInt32(data));
        assertFalse(readBoolean(data)); // anonymous editor drops
        assertFalse(data.hasRemaining());
    }

    @Test
    void rejectsInvalidSettingsBeforeEncoding() {
        var spawn = new BedrockStartGamePrimitivesCodec.BlockPosition(0, 64, 0);
        assertThrows(IllegalArgumentException.class,
                () -> new BedrockLevelSettingsCodec.Settings(1, 9, spawn, 2, -1, 4, "1.26.60"));
        assertThrows(IllegalArgumentException.class,
                () -> new BedrockLevelSettingsCodec.Settings(1, 0, spawn, 5, -1, 4, "1.26.60"));
        assertThrows(IllegalArgumentException.class,
                () -> new BedrockLevelSettingsCodec.Settings(1, 0, spawn, 2, -1, -1, "1.26.60"));
        assertThrows(IllegalArgumentException.class,
                () -> new BedrockLevelSettingsCodec.Settings(1, 0, spawn, 2, -1, 4, " "));
    }

    private static boolean readBoolean(ByteBuffer input) {
        assertTrue(input.hasRemaining());
        return input.get() != 0;
    }

    private static int readInt16LE(ByteBuffer input) {
        return Byte.toUnsignedInt(input.get()) | (input.get() << 8);
    }
}
