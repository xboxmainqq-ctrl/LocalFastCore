package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BedrockContainerPacketCodecTest {
    @Test
    void encodesChestOpenWithSignedBlockCoordinatesAndBlockEntitySentinel() {
        BedrockPacket packet = BedrockContainerPacketCodec.createChestOpen(7, -1, 64, -17);

        assertEquals(0x2e, packet.packetId());
        assertArrayEquals(new byte[] {
                7, 0,       // window ID, CONTAINER type
                1,          // ZigZag(-1)
                (byte) 0x80, 1, // ZigZag(64) = 128
                33,         // ZigZag(-17)
                1           // ZigZag(-1) entity unique ID
        }, packet.payload());
    }

    @Test
    void encodesFurnaceWindowWithProtocolContainerType() {
        BedrockPacket packet = BedrockContainerPacketCodec.createOpen(
                8, BedrockContainerPacketCodec.FURNACE_TYPE, 12, 64, -4,
                BedrockContainerPacketCodec.BLOCK_CONTAINER_ENTITY_ID);

        assertEquals(0x2e, packet.packetId());
        assertEquals(8, Byte.toUnsignedInt(packet.payload()[0]));
        assertEquals(2, Byte.toUnsignedInt(packet.payload()[1]));
    }

    @Test
    void encodesCloseWithProtocol2225TypeAndServerFlag() {
        BedrockPacket packet = BedrockContainerPacketCodec.createClose(7, 0, true);

        assertEquals(0x2f, packet.packetId());
        assertArrayEquals(new byte[] {7, 0, 1}, packet.payload());
    }

    @Test
    void decodesClientInitiatedClose() {
        var decoded = BedrockContainerPacketCodec.decodeClose(
                new BedrockPacket(0x2f, 0, 0, new byte[] {9, 0, 0}));

        assertEquals(9, decoded.windowId());
        assertEquals(0, decoded.type());
        assertFalse(decoded.serverInitiated());
    }

    @Test
    void rejectsMalformedClosePacketsAndInvalidWindowIds() {
        assertThrows(IllegalArgumentException.class, () ->
                BedrockContainerPacketCodec.decodeClose(new BedrockPacket(0x31, 0, 0, new byte[3])));
        assertThrows(IllegalArgumentException.class, () ->
                BedrockContainerPacketCodec.decodeClose(new BedrockPacket(0x2f, 0, 0, new byte[2])));
        assertThrows(IllegalArgumentException.class, () ->
                BedrockContainerPacketCodec.decodeClose(new BedrockPacket(0x2f, 0, 0, new byte[] {0, 0, 0})));
        assertThrows(IllegalArgumentException.class, () ->
                BedrockContainerPacketCodec.decodeClose(new BedrockPacket(0x2f, 0, 0, new byte[] {1, 0, 2})));
        assertThrows(IllegalArgumentException.class, () ->
                BedrockContainerPacketCodec.createChestOpen(0, 0, 64, 0));
    }
}
