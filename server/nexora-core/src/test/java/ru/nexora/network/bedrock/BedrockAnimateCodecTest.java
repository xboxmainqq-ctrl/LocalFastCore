package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BedrockAnimateCodecTest {

    @Test
    void createsProtocol2225MainHandAttackSwing() {
        BedrockPacket packet = BedrockAnimateCodec.createSwing(300L, BedrockAnimateCodec.MAIN_HAND);

        assertEquals(0x2c, packet.packetId());
        assertArrayEquals(new byte[] {
                1, (byte) 0xac, 0x02, 0, 0, 0, 0, 6,
                'A', 't', 't', 'a', 'c', 'k', 0
        }, packet.payload());
    }

    @Test
    void supportsOffHandAndRejectsInvalidActorOrHand() {
        BedrockPacket packet = BedrockAnimateCodec.createSwing(7L, BedrockAnimateCodec.OFF_HAND);
        assertEquals(1, Byte.toUnsignedInt(packet.payload()[packet.payload().length - 1]));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockAnimateCodec.createSwing(0L, BedrockAnimateCodec.MAIN_HAND));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockAnimateCodec.createSwing(7L, 2));
    }
}
