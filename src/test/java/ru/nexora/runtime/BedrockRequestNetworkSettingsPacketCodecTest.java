package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class BedrockRequestNetworkSettingsPacketCodecTest {
    @Test
    void encodesProtocol2225AsFourBigEndianBytes() {
        ByteBuffer encoded = BedrockRequestNetworkSettingsPacketCodec.encodePayload(
                new BedrockRequestNetworkSettingsPacket(2225)
        );

        assertTrue(encoded.isReadOnly());
        assertEquals(4, encoded.remaining());
        assertEquals(0x00, Byte.toUnsignedInt(encoded.get(0)));
        assertEquals(0x00, Byte.toUnsignedInt(encoded.get(1)));
        assertEquals(0x08, Byte.toUnsignedInt(encoded.get(2)));
        assertEquals(0xB1, Byte.toUnsignedInt(encoded.get(3)));
    }

    @Test
    void roundTripsVersionAndRecognizesPinnedProtocol() {
        BedrockRequestNetworkSettingsPacket packet =
                new BedrockRequestNetworkSettingsPacket(BedrockProtocolVersion.PROTOCOL_VERSION);

        BedrockRequestNetworkSettingsPacket decoded =
                BedrockRequestNetworkSettingsPacketCodec.decodePayload(
                        BedrockRequestNetworkSettingsPacketCodec.encodePayload(packet)
                );

        assertEquals(packet, decoded);
        assertTrue(decoded.isSupportedProtocol());
    }

    @Test
    void reportsOtherProtocolAsUnsupportedWithoutRejectingPacket() {
        BedrockRequestNetworkSettingsPacket decoded =
                BedrockRequestNetworkSettingsPacketCodec.decodePayload(
                        BedrockRequestNetworkSettingsPacketCodec.encodePayload(
                                new BedrockRequestNetworkSettingsPacket(2224)
                        )
                );

        assertFalse(decoded.isSupportedProtocol());
    }

    @Test
    void ignoresCallerByteOrderAndAdvancesInputByFourBytes() {
        ByteBuffer input = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN);
        input.put((byte) 0x55);
        input.putInt(2225);
        input.put((byte) 0x66);
        input.flip();
        input.position(1);
        input.limit(5);

        BedrockRequestNetworkSettingsPacket decoded =
                BedrockRequestNetworkSettingsPacketCodec.decodePayload(input);

        assertEquals(2225, decoded.clientNetworkVersion());
        assertEquals(5, input.position());
    }

    @Test
    void rejectsTruncatedAndTrailingPayloadBytes() {
        assertThrows(IllegalArgumentException.class,
                () -> BedrockRequestNetworkSettingsPacketCodec.decodePayload(
                        ByteBuffer.allocate(3)
                ));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockRequestNetworkSettingsPacketCodec.decodePayload(
                        ByteBuffer.allocate(5)
                ));
    }
}
