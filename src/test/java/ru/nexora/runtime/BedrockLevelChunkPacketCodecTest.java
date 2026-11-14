package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BedrockLevelChunkPacketCodecTest {
    @Test
    void serializesAllProtocol2225LevelChunkFieldsInSchemaOrder() {
        byte[] chunkData = new byte[] {0, 1, (byte) 0xFF, 4, 0};
        var settings = new BedrockLevelChunkPacketCodec.Settings(
                new BedrockLevelChunkPacketCodec.ChunkPosition(-2, 7),
                0, 4, null, false, List.of(), chunkData, false);

        ByteBuffer in = BedrockLevelChunkPacketCodec.encodePayload(settings);
        assertEquals(-2, BedrockBinary.readVarInt32(in));
        assertEquals(7, BedrockBinary.readVarInt32(in));
        assertEquals(0, BedrockBinary.readVarInt32(in));
        assertEquals(4, BedrockBinary.readVarUInt32(in));
        assertFalse(readBoolean(in)); // optional request limit absent
        assertFalse(readBoolean(in)); // cache disabled
        assertEquals(0, BedrockBinary.readVarUInt32(in)); // no cache metadata
        int length = BedrockBinary.readVarUInt32(in);
        assertEquals(chunkData.length, length);
        byte[] decoded = new byte[length];
        in.get(decoded);
        assertArrayEquals(chunkData, decoded);
        assertFalse(readBoolean(in)); // client biome update disabled
        assertFalse(in.hasRemaining());
    }

    @Test
    void emitsSkeletonChunkWithRequestModeSentinelAndLimit() {
        BedrockGamePacket packet = BedrockLevelChunkPacketCodec.encodeSkeletonPacket(
                new BedrockLevelChunkPacketCodec.ChunkPosition(3, -5), 0, -1);
        assertEquals(58, packet.header().packetId());

        ByteBuffer in = packet.payloadBuffer();
        assertEquals(3, BedrockBinary.readVarInt32(in));
        assertEquals(-5, BedrockBinary.readVarInt32(in));
        assertEquals(0, BedrockBinary.readVarInt32(in));
        assertEquals(-1, BedrockBinary.readVarUInt32(in)); // uint32 0xffffffff
        assertTrue(readBoolean(in));
        assertEquals(-1, BedrockBinary.readVarInt32(in));
        assertFalse(readBoolean(in));
        assertEquals(0, BedrockBinary.readVarUInt32(in));
        assertEquals(0, BedrockBinary.readVarUInt32(in)); // empty binary chunk string
        assertFalse(readBoolean(in));
        assertFalse(in.hasRemaining());
    }

    @Test
    void rejectsInvalidChunkSettings() {
        var position = new BedrockLevelChunkPacketCodec.ChunkPosition(0, 0);
        assertThrows(IllegalArgumentException.class, () ->
                new BedrockLevelChunkPacketCodec.Settings(
                        position, 9, 0, null, false, List.of(), new byte[0], false));
        assertThrows(IllegalArgumentException.class, () ->
                new BedrockLevelChunkPacketCodec.Settings(
                        position, 0, -2, null, false, List.of(), new byte[0], false));
        assertThrows(IllegalArgumentException.class, () ->
                new BedrockLevelChunkPacketCodec.Settings(
                        position, 0, 2, 8, false, List.of(), new byte[0], false));
        assertThrows(IllegalArgumentException.class, () ->
                new BedrockLevelChunkPacketCodec.Settings(
                        position, 0, 0, null, false,
                        List.of(new BedrockLevelChunkPacketCodec.CacheMetadata(1)),
                        new byte[0], false));
        assertThrows(IllegalArgumentException.class, () ->
                new BedrockLevelChunkPacketCodec.Settings(
                        position, 0, -1, -1, false, List.of(), new byte[] {1}, false));
    }

    private static boolean readBoolean(ByteBuffer input) {
        assertTrue(input.hasRemaining());
        int value = Byte.toUnsignedInt(input.get());
        assertTrue(value <= 1);
        return value != 0;
    }
}
