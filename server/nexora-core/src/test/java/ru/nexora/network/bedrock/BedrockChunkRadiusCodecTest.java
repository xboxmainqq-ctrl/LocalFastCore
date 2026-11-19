package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BedrockChunkRadiusCodecTest {
    @Test
    void decodesRequestAndNegotiatesAgainstClientAndServerLimits() {
        BedrockPacket request = new BedrockPacket(
                BedrockChunkRadiusCodec.REQUEST_PACKET_ID, 0, 0,
                new byte[] {24, 6}); // VarInt32(12), uint8(6)

        BedrockChunkRadiusCodec.Request decoded = BedrockChunkRadiusCodec.decodeRequest(request);

        assertEquals(12, decoded.radius());
        assertEquals(6, decoded.clientMaximumRadius());
        assertEquals(6, BedrockChunkRadiusCodec.negotiateRadius(decoded));
    }

    @Test
    void clampsLargeClientRequestsToTheServerBudget() {
        BedrockChunkRadiusCodec.Request request =
                new BedrockChunkRadiusCodec.Request(200, 255);

        assertEquals(BedrockChunkRadiusCodec.MAX_SERVER_RADIUS,
                BedrockChunkRadiusCodec.negotiateRadius(request));
    }

    @Test
    void enforcesMinimumOneChunkEvenWhenClientRequestsZero() {
        assertEquals(1, BedrockChunkRadiusCodec.negotiateRadius(
                new BedrockChunkRadiusCodec.Request(0, 0)));
    }

    @Test
    void rejectsNegativeAndMalformedRequests() {
        assertThrows(IllegalArgumentException.class, () ->
                BedrockChunkRadiusCodec.decodeRequest(new BedrockPacket(
                        BedrockChunkRadiusCodec.REQUEST_PACKET_ID, 0, 0,
                        new byte[] {1, 8}))); // VarInt32(-1)

        assertThrows(IllegalArgumentException.class, () ->
                BedrockChunkRadiusCodec.decodeRequest(new BedrockPacket(
                        BedrockChunkRadiusCodec.REQUEST_PACKET_ID, 0, 0,
                        new byte[] {(byte) 0x80, 8})));

        assertThrows(IllegalArgumentException.class, () ->
                BedrockChunkRadiusCodec.decodeRequest(new BedrockPacket(
                        BedrockChunkRadiusCodec.REQUEST_PACKET_ID, 0, 0,
                        new byte[] {4, 8, 9})));
    }

    @Test
    void createsChunkRadiusUpdatedPacketWithNegotiatedRadius() {
        BedrockPacket packet = BedrockChunkRadiusCodec.createUpdatedPacket(8);

        assertEquals(0x46, packet.packetId());
        assertArrayEquals(new byte[] {16}, packet.payload());
        assertThrows(IllegalArgumentException.class,
                () -> BedrockChunkRadiusCodec.createUpdatedPacket(0));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockChunkRadiusCodec.createUpdatedPacket(9));
    }
}
