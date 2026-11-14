package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BedrockStartGamePacketCodecTest {
    @Test
    void serializesAllProtocol2225StartGameFieldsInSchemaOrder() {
        var level = new BedrockLevelSettingsCodec.Settings(
                77L, 0,
                new BedrockStartGamePrimitivesCodec.BlockPosition(0, 64, 0),
                2, -1, 4, "1.26.60");
        var properties = new BedrockStartGamePacketCodec.PlayerPropertyData(
                List.of(new BedrockStartGamePacketCodec.IntProperty(2, -7)),
                List.of(new BedrockStartGamePacketCodec.FloatProperty(4, 1.25f)));
        var settings = new BedrockStartGamePacketCodec.Settings(
                -9L, 123L, 0,
                new BedrockStartGamePrimitivesCodec.Vec3(1.0f, 65.0f, -2.0f),
                new BedrockStartGamePrimitivesCodec.Vec2(90.0f, 0.0f),
                level, "nexora-level", "NEXORA", "", false,
                new BedrockStartGamePrimitivesCodec.SyncedPlayerMovementSettings(0, false),
                6000L, -3, "correlation", false, "NEXORA/0.1",
                properties, 0x0102030405060708L,
                0x1112131415161718L, 0x2122232425262728L,
                false, false, false,
                new BedrockStartGamePacketCodec.Telemetry("server", "scenario", "world", "owner"));

        ByteBuffer in = BedrockStartGamePacketCodec.encodePayload(settings);
        assertEquals(-9L, BedrockBinary.readVarInt64(in));
        assertEquals(123L, BedrockBinary.readVarUInt64(in));
        assertEquals(0, BedrockBinary.readVarInt32(in));
        assertEquals(new BedrockStartGamePrimitivesCodec.Vec3(1.0f, 65.0f, -2.0f),
                BedrockStartGamePrimitivesCodec.readVec3(in));
        assertEquals(new BedrockStartGamePrimitivesCodec.Vec2(90.0f, 0.0f),
                BedrockStartGamePrimitivesCodec.readVec2(in));

        ByteBuffer levelBytes = BedrockLevelSettingsCodec.encodeMinimalSurvivalFlat(level);
        byte[] expectedLevel = new byte[levelBytes.remaining()];
        levelBytes.get(expectedLevel);
        byte[] actualLevel = new byte[expectedLevel.length];
        in.get(actualLevel);
        assertArrayEquals(expectedLevel, actualLevel);

        assertEquals("nexora-level", BedrockBinary.readString(in, 4096));
        assertEquals("NEXORA", BedrockBinary.readString(in, 4096));
        assertEquals("", BedrockBinary.readString(in, 4096));
        assertFalse(readBoolean(in));
        assertEquals(new BedrockStartGamePrimitivesCodec.SyncedPlayerMovementSettings(0, false),
                BedrockStartGamePrimitivesCodec.readMovementSettings(in));
        assertEquals(6000L, BedrockBinary.readLittleEndianInt64(in));
        assertEquals(-3, BedrockBinary.readVarInt32(in));
        assertEquals(0, BedrockBinary.readVarUInt32(in)); // no server block properties
        assertEquals("correlation", BedrockBinary.readString(in, 128));
        assertFalse(readBoolean(in));
        assertEquals("NEXORA/0.1", BedrockBinary.readString(in, 128));

        assertEquals(1, BedrockBinary.readVarUInt32(in));
        assertEquals(2, BedrockBinary.readVarUInt32(in));
        assertEquals(-7, BedrockBinary.readVarInt32(in));
        assertEquals(1, BedrockBinary.readVarUInt32(in));
        assertEquals(4, BedrockBinary.readVarUInt32(in));
        assertEquals(1.25f, BedrockBinary.readLittleEndianFloat(in), 0.0001f);

        assertEquals(0x0102030405060708L, BedrockBinary.readLittleEndianInt64(in));
        assertEquals(0x1112131415161718L, BedrockBinary.readLittleEndianInt64(in));
        assertEquals(0x2122232425262728L, BedrockBinary.readLittleEndianInt64(in));
        assertFalse(readBoolean(in));
        assertFalse(readBoolean(in));
        assertFalse(readBoolean(in));
        assertFalse(readBoolean(in)); // server_config absent
        assertEquals("server", BedrockBinary.readString(in, 4096));
        assertEquals("scenario", BedrockBinary.readString(in, 4096));
        assertEquals("world", BedrockBinary.readString(in, 4096));
        assertEquals("owner", BedrockBinary.readString(in, 4096));
        assertFalse(in.hasRemaining());
    }

    @Test
    void emitsPacketElevenWithAValidBatchHeader() {
        var level = new BedrockLevelSettingsCodec.Settings(
                1, 0, new BedrockStartGamePrimitivesCodec.BlockPosition(0, 64, 0),
                2, -1, 4, "1.26.60");
        var settings = new BedrockStartGamePacketCodec.Settings(
                1, 1, 0,
                new BedrockStartGamePrimitivesCodec.Vec3(0, 65, 0),
                new BedrockStartGamePrimitivesCodec.Vec2(0, 0),
                level, "world", "NEXORA", "", false,
                new BedrockStartGamePrimitivesCodec.SyncedPlayerMovementSettings(0, false),
                0, 0, "session", false, "NEXORA/0.1",
                BedrockStartGamePacketCodec.PlayerPropertyData.empty(),
                0, 0, 0, false, false, false,
                BedrockStartGamePacketCodec.Telemetry.empty());

        BedrockGamePacket packet = BedrockStartGamePacketCodec.encodePacket(settings);
        assertEquals(11, packet.header().packetId());
        assertEquals(0, packet.header().senderSubClientId());
        assertEquals(0, packet.header().targetSubClientId());
        assertTrue(packet.payload().length > 0);
    }

    @Test
    void rejectsNonFinitePlayerPropertiesAndOversizedLists() {
        assertThrows(IllegalArgumentException.class,
                () -> new BedrockStartGamePacketCodec.FloatProperty(0, Float.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> new BedrockStartGamePacketCodec.IntProperty(-1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new BedrockStartGamePacketCodec.Telemetry("x".repeat(4097), "", "", ""));
    }

    private static boolean readBoolean(ByteBuffer input) {
        assertTrue(input.hasRemaining());
        int value = Byte.toUnsignedInt(input.get());
        assertTrue(value <= 1);
        return value != 0;
    }
}
