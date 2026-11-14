package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BedrockPacketBatchCodecTest {
    @Test
    void roundTripsMultiplePacketsAndSubClientHeaderFields() {
        List<BedrockGamePacket> packets = List.of(
                new BedrockGamePacket(new BedrockPacketHeader(193, 0, 0),
                        new byte[] {0, 0, 8, (byte) 0xB1}),
                new BedrockGamePacket(new BedrockPacketHeader(143, 1, 2),
                        new byte[] {1, 2, 3, 4, 5})
        );

        ByteBuffer encoded = BedrockPacketBatchCodec.encodeUncompressed(packets);
        assertTrue(encoded.isReadOnly());

        List<BedrockGamePacket> decoded = BedrockPacketBatchCodec.decodeUncompressed(encoded);
        assertEquals(2, decoded.size());
        assertEquals(packets.get(0).header(), decoded.get(0).header());
        assertArrayEquals(packets.get(0).payload(), decoded.get(0).payload());
        assertEquals(packets.get(1).header(), decoded.get(1).header());
        assertArrayEquals(packets.get(1).payload(), decoded.get(1).payload());
        assertFalse(encoded.hasRemaining());
    }

    @Test
    void encodesPacketLengthAndHeaderAsUnsignedVarints() {
        ByteBuffer encoded = BedrockPacketBatchCodec.encodeUncompressed(List.of(
                new BedrockGamePacket(new BedrockPacketHeader(193, 0, 0),
                        new byte[] {0, 0, 8, (byte) 0xB1})
        ));

        assertEquals(7, encoded.remaining());
        assertEquals(5, Byte.toUnsignedInt(encoded.get(0)));
        assertEquals(0xC1, Byte.toUnsignedInt(encoded.get(1)));
        assertEquals(0x01, Byte.toUnsignedInt(encoded.get(2)));
    }

    @Test
    void packetDefensivelyCopiesPayload() {
        byte[] source = {1, 2, 3};
        BedrockGamePacket packet = new BedrockGamePacket(
                new BedrockPacketHeader(1, 0, 0), source
        );
        source[0] = 99;
        byte[] returned = packet.payload();
        returned[1] = 88;

        assertArrayEquals(new byte[] {1, 2, 3}, packet.payload());
    }

    @Test
    void rejectsEmptyOversizedAndTruncatedBatches() {
        assertThrows(IllegalArgumentException.class,
                () -> BedrockPacketBatchCodec.decodeUncompressed(ByteBuffer.allocate(0)));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockPacketBatchCodec.decodeUncompressed(
                        ByteBuffer.wrap(new byte[] {5, (byte) 0xC1, 1, 0, 0, 0}),
                        5, 5, 10
                ));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockPacketBatchCodec.decodeUncompressed(
                        ByteBuffer.wrap(new byte[] {5, (byte) 0xC1})
                ));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockPacketBatchCodec.decodeUncompressed(
                        ByteBuffer.wrap(new byte[] {(byte) 0x80})
                ));
    }

    @Test
    void rejectsReservedHeaderBitsAndInvalidSubClientValues() {
        assertThrows(IllegalArgumentException.class,
                () -> BedrockPacketHeader.fromWireValue(0x4000));
        assertThrows(IllegalArgumentException.class,
                () -> new BedrockPacketHeader(1024, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new BedrockPacketHeader(1, 4, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new BedrockPacketHeader(1, 0, 4));
    }

    @Test
    void enforcesPacketCountLimit() {
        ByteBuffer encoded = BedrockPacketBatchCodec.encodeUncompressed(List.of(
                new BedrockGamePacket(new BedrockPacketHeader(1, 0, 0), new byte[0]),
                new BedrockGamePacket(new BedrockPacketHeader(2, 0, 0), new byte[0])
        ));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockPacketBatchCodec.decodeUncompressed(encoded, 100, 50, 1));
    }
}
