package ru.nexora.runtime;

import org.junit.jupiter.api.Test;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.v2225.Bedrock_v2225;
import org.cloudburstmc.protocol.bedrock.data.PacketRecipient;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BedrockStartGamePacketCodecTest {
    @Test
    void serializesAllProtocol2225StartGameFieldsInSchemaOrder() throws Exception {
        var level = new BedrockLevelSettingsCodec.Settings(
                77L, 0,
                new BedrockStartGamePrimitivesCodec.BlockPosition(0, 64, 0),
                2, -1, 4, "1.26.60");
        var settings = new BedrockStartGamePacketCodec.Settings(
                -9L, 123L, 0,
                new BedrockStartGamePrimitivesCodec.Vec3(1.0f, 65.0f, -2.0f),
                new BedrockStartGamePrimitivesCodec.Vec2(90.0f, 0.0f),
                level, "nexora-level", "NEXORA", "", false,
                new BedrockStartGamePrimitivesCodec.SyncedPlayerMovementSettings(0, false),
                6000L, -3, "correlation", false, "NEXORA/0.1",
                BedrockStartGamePacketCodec.PlayerPropertyData.empty(), 0x0102030405060708L,
                0x1112131415161718L, 0x2122232425262728L,
                false, true, false,
                new BedrockStartGamePacketCodec.Telemetry("server", "scenario", "world", "owner"));

        ByteBuffer encoded = BedrockStartGamePacketCodec.encodePayload(settings);
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        var input = Unpooled.wrappedBuffer(bytes);
        try {
            StartGamePacket decoded = (StartGamePacket) Bedrock_v2225.CODEC.tryDecode(
                    Bedrock_v2225.CODEC.createHelper(), input,
                    BedrockStartGamePacketCodec.PACKET_ID, PacketRecipient.CLIENT);

            assertEquals(-9L, decoded.getUniqueEntityId());
            assertEquals(123L, decoded.getRuntimeEntityId());
            assertEquals("nexora-level", decoded.getLevelId());
            assertEquals("NEXORA", decoded.getLevelName());
            assertEquals(77L, decoded.getSeed());
            assertEquals(2, decoded.getGeneratorId());
            assertEquals(2, decoded.getDifficulty());
            assertEquals(0, decoded.getRewindHistorySize());
            assertFalse(decoded.isServerAuthoritativeBlockBreaking());
            assertEquals("correlation", decoded.getMultiplayerCorrelationId());
            assertFalse(decoded.isInventoriesServerAuthoritative());
            assertEquals("NEXORA/0.1", decoded.getServerEngine());
            assertTrue(decoded.getBlockProperties().isEmpty());
            assertTrue(decoded.getItemDefinitions().isEmpty());
            assertTrue(decoded.getPlayerPropertyData().isEmpty());
            assertEquals(0x0102030405060708L, decoded.getBlockRegistryChecksum());
            assertEquals(new java.util.UUID(0x1112131415161718L, 0x2122232425262728L),
                    decoded.getWorldTemplateId());
            assertFalse(decoded.isClientSideGenerationEnabled());
            assertTrue(decoded.isBlockNetworkIdsHashed());
            assertFalse(decoded.getNetworkPermissions().serverAuthSounds());
            assertFalse(decoded.isTickDeathSystemsEnabled());
            assertFalse(decoded.isHasServerJoinInformation());
            assertEquals("server", decoded.getServerId());
            assertEquals("scenario", decoded.getScenarioId());
            assertEquals("world", decoded.getWorldId());
            assertEquals("owner", decoded.getOwnerId());
            assertEquals(0, input.readableBytes());
        } finally {
            input.release();
        }
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
