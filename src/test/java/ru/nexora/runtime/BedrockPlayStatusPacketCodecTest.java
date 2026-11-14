package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class BedrockPlayStatusPacketCodecTest {
    @Test
    void encodesLoginSuccessAsBigEndianInt32() {
        ByteBuffer encoded = BedrockPlayStatusPacketCodec.encodePayload(BedrockPlayStatus.LOGIN_SUCCESS);
        assertEquals(4, encoded.remaining());
        assertEquals(0, encoded.get(0));
        assertEquals(0, encoded.get(1));
        assertEquals(0, encoded.get(2));
        assertEquals(0, encoded.get(3));
        assertEquals(BedrockPlayStatus.LOGIN_SUCCESS,
                BedrockPlayStatusPacketCodec.decodePayload(encoded));
    }

    @Test
    void roundTripsEveryDocumentedStatusValue() {
        for (BedrockPlayStatus status : BedrockPlayStatus.values()) {
            assertEquals(status, BedrockPlayStatusPacketCodec.decodePayload(
                    BedrockPlayStatusPacketCodec.encodePayload(status)));
        }
    }

    @Test
    void rejectsTruncatedExtendedAndUnknownValues() {
        assertThrows(IllegalArgumentException.class,
                () -> BedrockPlayStatusPacketCodec.decodePayload(ByteBuffer.allocate(3)));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockPlayStatusPacketCodec.decodePayload(ByteBuffer.allocate(5)));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockPlayStatusPacketCodec.decodePayload(ByteBuffer.allocate(4).putInt(10).flip()));
    }
}
