package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class BedrockMovementCorrectionCodecTest {
    @Test
    void encodesPlayerCorrectionInProtocol2225WireOrder() {
        BedrockPacket packet = BedrockMovementCorrectionCodec.createPlayerCorrection(
                1.25, 65.0, -2.5, 0.5, -1.0, 0.25, true, 300);

        assertEquals(0xA1, packet.packetId());
        byte[] payload = packet.payload();
        assertEquals(37, payload.length);
        assertEquals(0, payload[0]); // Player prediction, not vehicle

        ByteBuffer values = ByteBuffer.wrap(payload, 1, 32).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(1.25f, values.getFloat());
        assertEquals(65.0f, values.getFloat());
        assertEquals(-2.5f, values.getFloat());
        assertEquals(0.5f, values.getFloat());
        assertEquals(-1.0f, values.getFloat());
        assertEquals(0.25f, values.getFloat());
        assertEquals(0.0f, values.getFloat()); // vehicle rotation X placeholder
        assertEquals(0.0f, values.getFloat()); // vehicle rotation Y placeholder
        assertEquals(0, Byte.toUnsignedInt(payload[33])); // no vehicle angular velocity
        assertEquals(1, Byte.toUnsignedInt(payload[34])); // on ground
        assertEquals(0xac, Byte.toUnsignedInt(payload[35])); // first byte of VarUInt64(300)
        assertEquals(0x02, Byte.toUnsignedInt(payload[36])); // second byte of VarUInt64(300)
    }

    @Test
    void rejectsInvalidCorrectionValues() {
        assertThrows(IllegalArgumentException.class, () ->
                BedrockMovementCorrectionCodec.createPlayerCorrection(
                        Double.NaN, 65, 0, 0, 0, 0, true, 1));
        assertThrows(IllegalArgumentException.class, () ->
                BedrockMovementCorrectionCodec.createPlayerCorrection(
                        0, 65, 0, 0, 0, 0, true, -1));
    }
}
