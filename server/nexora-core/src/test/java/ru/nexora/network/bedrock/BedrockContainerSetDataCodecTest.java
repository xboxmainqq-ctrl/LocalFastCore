package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BedrockContainerSetDataCodecTest {
    @Test
    void encodesFurnaceProgressPropertiesAsZigZagVarInts() {
        BedrockPacket packet = BedrockContainerSetDataCodec.create(8, 0, 100);

        assertEquals(0x33, packet.packetId());
        assertArrayEquals(new byte[] {8, 0, (byte) 0xc8, 1}, packet.payload());
    }

    @Test
    void encodesNegativeValuesAndRejectsInvalidWindowIds() {
        BedrockPacket packet = BedrockContainerSetDataCodec.create(4, -1, -2);
        assertArrayEquals(new byte[] {4, 1, 3}, packet.payload());
        assertThrows(IllegalArgumentException.class, () ->
                BedrockContainerSetDataCodec.create(0, 0, 0));
    }
}
