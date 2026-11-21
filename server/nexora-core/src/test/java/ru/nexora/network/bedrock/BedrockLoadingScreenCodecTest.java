package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BedrockLoadingScreenCodecTest {
    @Test
    void decodesInitialJoinStartAndEndWithoutScreenId() {
        BedrockLoadingScreenCodec.LoadingScreen start = BedrockLoadingScreenCodec.decode(
                new BedrockPacket(0x138, 0, 0, new byte[] {2, 0}));
        BedrockLoadingScreenCodec.LoadingScreen end = BedrockLoadingScreenCodec.decode(
                new BedrockPacket(0x138, 0, 0, new byte[] {4, 0}));

        assertTrue(start.isStart());
        assertNull(start.screenId());
        assertTrue(end.isEnd());
        assertNull(end.screenId());
    }

    @Test
    void decodesOptionalLittleEndianScreenId() {
        BedrockLoadingScreenCodec.LoadingScreen decoded = BedrockLoadingScreenCodec.decode(
                new BedrockPacket(0x138, 0, 0,
                        new byte[] {2, 1, 0x78, 0x56, 0x34, 0x12}));

        assertTrue(decoded.isStart());
        assertEquals(0x12345678, decoded.screenId());
    }

    @Test
    void rejectsMalformedOrUnknownPackets() {
        assertThrows(IllegalArgumentException.class, () -> BedrockLoadingScreenCodec.decode(
                new BedrockPacket(0x138, 0, 0, new byte[] {2})));
        assertThrows(IllegalArgumentException.class, () -> BedrockLoadingScreenCodec.decode(
                new BedrockPacket(0x138, 0, 0, new byte[] {6, 0})));
        assertThrows(IllegalArgumentException.class, () -> BedrockLoadingScreenCodec.decode(
                new BedrockPacket(0x138, 0, 0, new byte[] {2, 1, 1})));
    }
}
