package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BedrockTakeItemActorCodecTest {
    @Test
    void decodesPlayerThenItemRuntimeIdsAndEncodesSameWireOrder() {
        BedrockPacket packet = BedrockTakeItemActorCodec.createAcknowledgement(1234, 55);
        assertEquals(BedrockTakeItemActorCodec.TAKE_ITEM_ACTOR_PACKET_ID, packet.packetId());
        var decoded = BedrockTakeItemActorCodec.decode(packet);
        assertEquals(1234, decoded.itemRuntimeId());
        assertEquals(55, decoded.playerRuntimeId());
        assertArrayEquals(new byte[] {55, (byte) 0xd2, 0x09}, packet.payload());
    }

    @Test
    void rejectsZeroIDsAndTrailingBytes() {
        assertThrows(IllegalArgumentException.class, () ->
                BedrockTakeItemActorCodec.createAcknowledgement(0, 55));
        assertThrows(IllegalArgumentException.class, () ->
                BedrockTakeItemActorCodec.decode(new BedrockPacket(0x11, 0, 0, new byte[] {1, 1, 0})));
    }
}
