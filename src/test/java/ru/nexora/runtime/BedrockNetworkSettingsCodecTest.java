package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class BedrockNetworkSettingsCodecTest {
    @Test
    void roundTripsEveryCompressionAlgorithm() {
        for (BedrockNetworkSettings.CompressionAlgorithm algorithm
                : BedrockNetworkSettings.CompressionAlgorithm.values()) {
            BedrockNetworkSettings settings = new BedrockNetworkSettings(
                    512, algorithm, true, 127, 0.75f
            );

            ByteBuffer encoded = BedrockNetworkSettingsCodec.encodePayload(settings);
            assertTrue(encoded.isReadOnly());
            assertEquals(BedrockNetworkSettingsCodec.PAYLOAD_BYTES, encoded.remaining());
            assertEquals(settings, BedrockNetworkSettingsCodec.decodePayload(encoded));
            assertFalse(encoded.hasRemaining());
        }
    }

    @Test
    void usesProtocol2225NoneValue65535InLittleEndianOrder() {
        ByteBuffer encoded = BedrockNetworkSettingsCodec.encodePayload(
                new BedrockNetworkSettings(
                        0x1234,
                        BedrockNetworkSettings.CompressionAlgorithm.NONE,
                        false,
                        0,
                        0.0f
                )
        );

        assertEquals(0x34, Byte.toUnsignedInt(encoded.get(0)));
        assertEquals(0x12, Byte.toUnsignedInt(encoded.get(1)));
        assertEquals(0xFF, Byte.toUnsignedInt(encoded.get(2)));
        assertEquals(0xFF, Byte.toUnsignedInt(encoded.get(3)));
    }

    @Test
    void readsBooleanAsAnyNonZeroByteAndAdvancesInput() {
        ByteBuffer encoded = ByteBuffer.allocate(11).order(ByteOrder.BIG_ENDIAN);
        encoded.put((byte) 0x55);
        encoded.putShort((short) 256);
        encoded.putShort((short) 0);
        encoded.put((byte) 0x7F);
        encoded.put((byte) 200);
        encoded.putFloat(1.25f);
        encoded.flip();
        encoded.position(1);

        BedrockNetworkSettings decoded = BedrockNetworkSettingsCodec.decodePayload(encoded);

        assertTrue(decoded.clientThrottleEnabled());
        assertEquals(200, decoded.clientThrottleThreshold());
        assertEquals(1.25f, decoded.clientThrottleScalar());
        assertEquals(11, encoded.position());
    }

    @Test
    void rejectsWrongPayloadSizeAndUnknownAlgorithm() {
        assertThrows(IllegalArgumentException.class,
                () -> BedrockNetworkSettingsCodec.decodePayload(ByteBuffer.allocate(9)));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockNetworkSettingsCodec.decodePayload(ByteBuffer.allocate(11)));

        ByteBuffer unknownAlgorithm = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN);
        unknownAlgorithm.putShort((short) 0);
        unknownAlgorithm.putShort((short) 2);
        unknownAlgorithm.put((byte) 0).put((byte) 0).putFloat(0.0f).flip();

        assertThrows(IllegalArgumentException.class,
                () -> BedrockNetworkSettingsCodec.decodePayload(unknownAlgorithm));
    }

    @Test
    void validatesUnsignedFieldRanges() {
        assertThrows(IllegalArgumentException.class,
                () -> new BedrockNetworkSettings(-1,
                        BedrockNetworkSettings.CompressionAlgorithm.NONE, false, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new BedrockNetworkSettings(65536,
                        BedrockNetworkSettings.CompressionAlgorithm.NONE, false, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new BedrockNetworkSettings(0,
                        BedrockNetworkSettings.CompressionAlgorithm.NONE, false, 256, 0));
    }
}
