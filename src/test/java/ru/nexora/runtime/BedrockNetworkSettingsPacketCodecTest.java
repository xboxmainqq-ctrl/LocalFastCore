package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class BedrockNetworkSettingsPacketCodecTest {
    @Test
    void roundTripsAllProtocolFieldsAndUsesLittleEndian() {
        BedrockNetworkSettings settings = new BedrockNetworkSettings(
                512, BedrockNetworkSettings.CompressionAlgorithm.ZLIB,
                true, 7, 0.5f
        );

        ByteBuffer encoded = BedrockNetworkSettingsPacketCodec.encodePayload(settings);
        assertTrue(encoded.isReadOnly());
        assertEquals(10, encoded.remaining());
        assertEquals(0x00, Byte.toUnsignedInt(encoded.get(0)));
        assertEquals(0x02, Byte.toUnsignedInt(encoded.get(1)));
        assertEquals(0x00, Byte.toUnsignedInt(encoded.get(2)));
        assertEquals(0x00, Byte.toUnsignedInt(encoded.get(3)));

        assertEquals(settings, BedrockNetworkSettingsPacketCodec.decodePayload(encoded));
    }

    @Test
    void decodesUnsignedValuesAndNonzeroBoolean() {
        ByteBuffer payload = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN);
        payload.putShort((short) 0xFFFF);
        payload.putShort((short) 0xFFFF);
        payload.put((byte) 0x80);
        payload.put((byte) 0xFE);
        payload.putFloat(1.25f);
        payload.flip();

        BedrockNetworkSettings decoded = BedrockNetworkSettingsPacketCodec.decodePayload(payload);
        assertEquals(65535, decoded.compressionThreshold());
        assertEquals(BedrockNetworkSettings.CompressionAlgorithm.NONE, decoded.compressionAlgorithm());
        assertTrue(decoded.clientThrottleEnabled());
        assertEquals(254, decoded.clientThrottleThreshold());
        assertEquals(1.25f, decoded.clientThrottleScalar());
        assertFalse(payload.hasRemaining());
    }

    @Test
    void rejectsWrongPayloadLengthAndUnknownAlgorithm() {
        assertThrows(IllegalArgumentException.class,
                () -> BedrockNetworkSettingsPacketCodec.decodePayload(ByteBuffer.allocate(9)));

        ByteBuffer unknownAlgorithm = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN);
        unknownAlgorithm.putShort((short) 1).putShort((short) 9)
                .put((byte) 0).put((byte) 0).putFloat(0.0f).flip();
        assertThrows(IllegalArgumentException.class,
                () -> BedrockNetworkSettingsPacketCodec.decodePayload(unknownAlgorithm));
    }
}
