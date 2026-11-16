package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BedrockBiomeDefinitionCodecTest {
    @Test
    void loadsTheCapturedProtocol2225VanillaBiomeRegistry() {
        BedrockPacket packet = BedrockBiomeDefinitionCodec.createPacket();

        assertEquals(0x7a, packet.packetId());
        assertEquals(89, Byte.toUnsignedInt(packet.payload()[0]));
        assertTrue(packet.payload().length > 5_000);
    }
}
