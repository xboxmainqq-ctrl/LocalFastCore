package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BedrockInteractPacketCodecTest {
    @Test
    void decodesProtocol2225OpenInventoryRequest() {
        var decoded = BedrockInteractPacketCodec.decode(
                new BedrockPacket(0x21, 0, 0, new byte[] {6, 42}));

        assertEquals(6, decoded.action());
        assertEquals(42L, decoded.targetRuntimeId());
    }

    @Test
    void decodesInteractUpdatePositionOnlyForItsAction() {
        byte[] payload = new byte[] {4, 1, 0, 0, 0, 0, 0, 0, (byte) 0x80, 0x3f,
                0, 0, 0, 0x40};
        var decoded = BedrockInteractPacketCodec.decode(new BedrockPacket(0x21, 0, 0, payload));

        assertEquals(4, decoded.action());
        assertEquals(1L, decoded.targetRuntimeId());
    }

    @Test
    void rejectsTruncatedAndTrailingInteractPayloads() {
        assertThrows(IllegalArgumentException.class, () ->
                BedrockInteractPacketCodec.decode(new BedrockPacket(0x21, 0, 0, new byte[] {6})));
        assertThrows(IllegalArgumentException.class, () ->
                BedrockInteractPacketCodec.decode(new BedrockPacket(0x21, 0, 0, new byte[] {6, 1, 0})));
        assertThrows(IllegalArgumentException.class, () ->
                BedrockInteractPacketCodec.decode(new BedrockPacket(0x22, 0, 0, new byte[] {6, 1})));
    }
}
