package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class BedrockLoginPacketCodecTest {
    @Test
    void roundTripsProtocolAndUtf8ConnectionRequest() {
        BedrockLoginPacket packet = new BedrockLoginPacket(
                BedrockProtocolVersion.PROTOCOL_VERSION,
                "{\"identity\":\"NEXORA-тест 🌍\"}"
        );

        ByteBuffer encoded = BedrockLoginPacketCodec.encodePayload(packet);
        assertTrue(encoded.isReadOnly());

        BedrockLoginPacket decoded = BedrockLoginPacketCodec.decodePayload(encoded);
        assertEquals(packet, decoded);
        assertTrue(decoded.isSupportedProtocol());
    }

    @Test
    void writesProtocolVersionAsBigEndianRegardlessOfCallerByteOrder() {
        ByteBuffer encoded = BedrockLoginPacketCodec.encodePayload(
                new BedrockLoginPacket(0x01020304, "{}")
        ).order(ByteOrder.LITTLE_ENDIAN);

        assertEquals(0x01, Byte.toUnsignedInt(encoded.get(0)));
        assertEquals(0x02, Byte.toUnsignedInt(encoded.get(1)));
        assertEquals(0x03, Byte.toUnsignedInt(encoded.get(2)));
        assertEquals(0x04, Byte.toUnsignedInt(encoded.get(3)));
    }

    @Test
    void reportsUnsupportedProtocolWithoutRejectingWellFormedPacket() {
        BedrockLoginPacket decoded = BedrockLoginPacketCodec.decodePayload(
                BedrockLoginPacketCodec.encodePayload(new BedrockLoginPacket(2224, "{}"))
        );

        assertFalse(decoded.isSupportedProtocol());
    }

    @Test
    void rejectsTruncatedProtocolAndRequest() {
        assertThrows(IllegalArgumentException.class,
                () -> BedrockLoginPacketCodec.decodePayload(ByteBuffer.allocate(3)));

        ByteBuffer truncatedRequest = ByteBuffer.allocate(5);
        truncatedRequest.putInt(2225).put((byte) 4).put((byte) '{').flip();
        assertThrows(IllegalArgumentException.class,
                () -> BedrockLoginPacketCodec.decodePayload(truncatedRequest));
    }

    @Test
    void rejectsTrailingBytes() {
        ByteBuffer encoded = BedrockLoginPacketCodec.encodePayload(
                new BedrockLoginPacket(2225, "{}")
        );
        ByteBuffer withTrailingByte = ByteBuffer.allocate(encoded.remaining() + 1);
        withTrailingByte.put(encoded).put((byte) 0).flip();

        assertThrows(IllegalArgumentException.class,
                () -> BedrockLoginPacketCodec.decodePayload(withTrailingByte));
    }

    @Test
    void rejectsOversizedConnectionRequestOnEncodeAndDecode() {
        String oversized = "x".repeat(BedrockLoginPacketCodec.MAX_CONNECTION_REQUEST_BYTES + 1);
        assertThrows(IllegalArgumentException.class,
                () -> BedrockLoginPacketCodec.encodePayload(new BedrockLoginPacket(2225, oversized)));

        ByteBuffer oversizedPayload = ByteBuffer.allocate(
                Integer.BYTES + 5 + BedrockLoginPacketCodec.MAX_CONNECTION_REQUEST_BYTES + 1
        );
        BedrockBinary.writeBigEndianInt32(oversizedPayload, 2225);
        BedrockBinary.writeVarUInt32(
                oversizedPayload, BedrockLoginPacketCodec.MAX_CONNECTION_REQUEST_BYTES + 1
        );
        oversizedPayload.position(oversizedPayload.limit());
        oversizedPayload.flip();

        assertThrows(IllegalArgumentException.class,
                () -> BedrockLoginPacketCodec.decodePayload(oversizedPayload));
    }
}
