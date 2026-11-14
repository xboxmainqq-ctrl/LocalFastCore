package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BedrockSubChunkRequestPacketCodecTest {
    @Test
    void decodesProtocol2225RequestOffsetsAndCenterPosition() {
        ByteBuffer payload = ByteBuffer.allocate(64);
        BedrockBinary.writeVarInt32(payload, 0); // overworld
        BedrockBinary.writeVarUInt32(payload, 2);
        payload.put((byte) 1).put((byte) -2).put((byte) 3);
        payload.put((byte) -4).put((byte) 5).put((byte) -6);
        BedrockBinary.writeLittleEndianInt32(payload, 100);
        BedrockBinary.writeLittleEndianInt32(payload, -64);
        BedrockBinary.writeLittleEndianInt32(payload, 200);
        payload.flip();

        var request = BedrockSubChunkRequestPacketCodec.decodePayload(payload);
        assertEquals(0, request.dimension());
        assertEquals(new BedrockSubChunkRequestPacketCodec.SubChunkPosition(100, -64, 200),
                request.center());
        assertEquals(List.of(
                new BedrockSubChunkRequestPacketCodec.Offset(1, -2, 3),
                new BedrockSubChunkRequestPacketCodec.Offset(-4, 5, -6)
        ), request.offsets());
        assertEquals(List.of(
                new BedrockSubChunkRequestPacketCodec.SubChunkPosition(101, -66, 203),
                new BedrockSubChunkRequestPacketCodec.SubChunkPosition(96, -59, 194)
        ), request.absolutePositions());
        assertFalse(payload.hasRemaining());
    }

    @Test
    void rejectsInvalidCountsTruncationAndTrailingBytes() {
        ByteBuffer zeroCount = ByteBuffer.allocate(8);
        BedrockBinary.writeVarInt32(zeroCount, 0);
        BedrockBinary.writeVarUInt32(zeroCount, 0);
        zeroCount.flip();
        assertThrows(IllegalArgumentException.class,
                () -> BedrockSubChunkRequestPacketCodec.decodePayload(zeroCount));

        ByteBuffer truncated = ByteBuffer.allocate(8);
        BedrockBinary.writeVarInt32(truncated, 0);
        BedrockBinary.writeVarUInt32(truncated, 1);
        truncated.put((byte) 0);
        truncated.flip();
        assertThrows(IllegalArgumentException.class,
                () -> BedrockSubChunkRequestPacketCodec.decodePayload(truncated));

        ByteBuffer trailing = ByteBuffer.allocate(32);
        BedrockBinary.writeVarInt32(trailing, 0);
        BedrockBinary.writeVarUInt32(trailing, 1);
        trailing.put((byte) 0).put((byte) 0).put((byte) 0);
        BedrockBinary.writeLittleEndianInt32(trailing, 0);
        BedrockBinary.writeLittleEndianInt32(trailing, 0);
        BedrockBinary.writeLittleEndianInt32(trailing, 0);
        trailing.put((byte) 99);
        trailing.flip();
        assertThrows(IllegalArgumentException.class,
                () -> BedrockSubChunkRequestPacketCodec.decodePayload(trailing));
    }

    @Test
    void rejectsOffsetsOutsideSignedByteRange() {
        assertThrows(IllegalArgumentException.class,
                () -> new BedrockSubChunkRequestPacketCodec.Offset(128, 0, 0));
    }
}
