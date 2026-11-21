package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BedrockJigsawStructureDataCodecTest {
    @Test
    void loadsCapturedProtocol2225NetworkNbtBeforeStartGame() {
        BedrockPacket packet = BedrockJigsawStructureDataCodec.createPacket();

        assertEquals(0x139, packet.packetId());
        byte[] payload = packet.payload();
        assertTrue(payload.length > 100_000);
        assertEquals(0x0a, Byte.toUnsignedInt(payload[0]));
        assertEquals(0, Byte.toUnsignedInt(payload[1]));
        assertEquals(0, Byte.toUnsignedInt(payload[2]));
    }
}
