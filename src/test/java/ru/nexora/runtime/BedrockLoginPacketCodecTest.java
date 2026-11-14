package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class BedrockLoginPacketCodecTest {
    @Test
    void roundTripsProtocolAuthenticationPayloadAndClientDataJwt() {
        BedrockLoginPacket packet = new BedrockLoginPacket(
                BedrockProtocolVersion.PROTOCOL_VERSION,
                "{\"identity\":\"NEXORA-тест 🌍\"}",
                "eyJhbGciOiJFUzM4NCJ9.eyJjbGllbnQiOiJ0ZXN0In0.c2ln"
        );

        ByteBuffer encoded = BedrockLoginPacketCodec.encodePayload(packet);
        assertTrue(encoded.isReadOnly());

        BedrockLoginPacket decoded = BedrockLoginPacketCodec.decodePayload(encoded);
        assertEquals(packet, decoded);
        assertTrue(decoded.isSupportedProtocol());
    }

    @Test
    void writesProtocolBigEndianAndJwtLengthsLittleEndian() {
        BedrockLoginPacket packet = new BedrockLoginPacket(0x01020304, "{}", "jwt");
        ByteBuffer encoded = BedrockLoginPacketCodec.encodePayload(packet);

        assertEquals(0x01, Byte.toUnsignedInt(encoded.get(0)));
        assertEquals(0x02, Byte.toUnsignedInt(encoded.get(1)));
        assertEquals(0x03, Byte.toUnsignedInt(encoded.get(2)));
        assertEquals(0x04, Byte.toUnsignedInt(encoded.get(3)));

        ByteBuffer view = encoded.duplicate();
        BedrockBinary.readBigEndianInt32(view);
        int blockLength = BedrockBinary.readVarUInt32(view);
        assertEquals(blockLength, view.remaining());
        view.order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(2, view.getInt());
        view.position(view.position() + 2);
        assertEquals(3, view.getInt());
    }

    @Test
    void reportsUnsupportedProtocolWithoutRejectingWellFormedPacket() {
        BedrockLoginPacket decoded = BedrockLoginPacketCodec.decodePayload(
                BedrockLoginPacketCodec.encodePayload(new BedrockLoginPacket(2224, "{}", "jwt"))
        );

        assertFalse(decoded.isSupportedProtocol());
    }

    @Test
    void rejectsTruncatedProtocolAndJwtBlock() {
        assertThrows(IllegalArgumentException.class,
                () -> BedrockLoginPacketCodec.decodePayload(ByteBuffer.allocate(3)));

        ByteBuffer truncatedBlock = ByteBuffer.allocate(6);
        truncatedBlock.putInt(2225).put((byte) 12).put((byte) 0).flip();
        assertThrows(IllegalArgumentException.class,
                () -> BedrockLoginPacketCodec.decodePayload(truncatedBlock));
    }

    @Test
    void rejectsTrailingBytes() {
        ByteBuffer encoded = BedrockLoginPacketCodec.encodePayload(
                new BedrockLoginPacket(2225, "{}", "jwt")
        );
        ByteBuffer withTrailingByte = ByteBuffer.allocate(encoded.remaining() + 1);
        withTrailingByte.put(encoded).put((byte) 0).flip();

        assertThrows(IllegalArgumentException.class,
                () -> BedrockLoginPacketCodec.decodePayload(withTrailingByte));
    }

    @Test
    void rejectsOversizedAuthenticationAndClientJwtOnEncode() {
        String oversizedAuth = "x".repeat(BedrockLoginPacketCodec.MAX_CONNECTION_REQUEST_BYTES + 1);
        assertThrows(IllegalArgumentException.class,
                () -> BedrockLoginPacketCodec.encodePayload(
                        new BedrockLoginPacket(2225, oversizedAuth, "jwt")));

        String oversizedClient = "x".repeat(BedrockLoginPacketCodec.MAX_CLIENT_DATA_JWT_BYTES + 1);
        assertThrows(IllegalArgumentException.class,
                () -> BedrockLoginPacketCodec.encodePayload(
                        new BedrockLoginPacket(2225, "{}", oversizedClient)));
    }
}
