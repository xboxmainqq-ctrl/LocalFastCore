package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BedrockSubChunkPacketCodecTest {
    @Test
    void serializesProtocol2225SuccessAndAllAirEntries() {
        byte[] subchunk = new byte[] {9, 1, 0, 1};
        var entries = List.of(
                BedrockSubChunkPacketCodec.Entry.success(
                        new BedrockSubChunkPacketCodec.Offset(1, 0, -1), subchunk),
                BedrockSubChunkPacketCodec.Entry.allAir(
                        new BedrockSubChunkPacketCodec.Offset(0, -1, 0))
        );

        BedrockGamePacket packet = BedrockSubChunkPacketCodec.encodePacket(
                0, new BedrockSubChunkPacketCodec.Position(12, -4, -8), entries);
        assertEquals(174, packet.header().packetId());

        ByteBuffer in = packet.payloadBuffer();
        assertFalse(readBoolean(in)); // cache disabled
        assertEquals(0, BedrockBinary.readVarInt32(in));
        assertEquals(12, BedrockBinary.readLittleEndianInt32(in));
        assertEquals(-4, BedrockBinary.readLittleEndianInt32(in));
        assertEquals(-8, BedrockBinary.readLittleEndianInt32(in));
        assertEquals(2, BedrockBinary.readVarUInt32(in));

        assertEquals(1, in.get());
        assertEquals(0, in.get());
        assertEquals(-1, in.get());
        assertEquals(1, Byte.toUnsignedInt(in.get())); // Success in protocol 2225
        int length = BedrockBinary.readVarUInt32(in);
        byte[] actual = new byte[length];
        in.get(actual);
        assertArrayEquals(subchunk, actual);
        assertEquals(0, Byte.toUnsignedInt(in.get())); // height map NoData
        assertEquals(0, Byte.toUnsignedInt(in.get())); // render height map NoData

        assertEquals(0, in.get());
        assertEquals(-1, in.get());
        assertEquals(0, in.get());
        assertEquals(6, Byte.toUnsignedInt(in.get())); // SuccessAllAir in protocol 2225
        assertEquals(0, BedrockBinary.readVarUInt32(in)); // empty serialized subchunk
        assertEquals(0, Byte.toUnsignedInt(in.get()));
        assertEquals(0, Byte.toUnsignedInt(in.get()));
        assertFalse(in.hasRemaining());
    }

    @Test
    void rejectsInvalidEntriesAndOversizedBatches() {
        var offset = new BedrockSubChunkPacketCodec.Offset(0, 0, 0);
        assertThrows(IllegalArgumentException.class,
                () -> BedrockSubChunkPacketCodec.Entry.success(offset, new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockSubChunkPacketCodec.Entry.error(offset,
                        BedrockSubChunkPacketCodec.RESULT_SUCCESS));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockSubChunkPacketCodec.encodePayload(0,
                        new BedrockSubChunkPacketCodec.Position(0, 0, 0), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new BedrockSubChunkPacketCodec.Offset(128, 0, 0));
    }

    private static boolean readBoolean(ByteBuffer input) {
        assertTrue(input.hasRemaining());
        int value = Byte.toUnsignedInt(input.get());
        assertTrue(value <= 1);
        return value != 0;
    }
}
