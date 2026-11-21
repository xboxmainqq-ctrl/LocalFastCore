package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;
import ru.nexora.world.FlatWorldGenerator;
import ru.nexora.world.WorldChunk;

import static org.junit.jupiter.api.Assertions.*;

class BedrockLevelChunkCodecTest {
    @Test
    void encodesAProtocol2225ChunkWithNegativeSubChunksAndMatchingBiomePalettes() {
        WorldChunk chunk = new WorldChunk(-2, 3);
        new FlatWorldGenerator().generate(chunk, 1L);

        BedrockPacket packet = BedrockLevelChunkCodec.createPacket(chunk);
        assertEquals(0x3a, packet.packetId());

        Reader reader = new Reader(packet.payload());
        assertEquals(-2, reader.varInt32());
        assertEquals(3, reader.varInt32());
        assertEquals(0, reader.varInt32()); // Overworld
        int subChunkCount = reader.varUInt32();
        assertEquals(9, subChunkCount); // four negative plus five positive sub-chunks
        assertFalse(reader.bool()); // optional client request subchunk limit is absent
        assertFalse(reader.bool()); // cacheEnabled = false
        assertEquals(0, reader.varUInt32()); // empty blob hash array

        byte[] data = reader.byteArray();
        assertFalse(reader.bool()); // clientBiomeUpdate
        assertTrue(reader.exhausted());

        Reader subChunks = new Reader(data);
        for (int sectionY = -4; sectionY < 0; sectionY++) {
            assertEquals(9, subChunks.byteValue());
            assertEquals(0, subChunks.byteValue());
            assertEquals(sectionY & 0xff, subChunks.byteValue());
        }
        for (int sectionY = 0; sectionY < 3; sectionY++) {
            assertEquals(9, subChunks.byteValue());
            assertEquals(1, subChunks.byteValue());
            assertEquals(sectionY, subChunks.byteValue());
            assertEquals(1, subChunks.byteValue()); // singleton air palette
            assertEquals(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.AIR), subChunks.varInt32());
        }

        // Section Y=3 contains air, bedrock, stone and dirt: a two-bit packed palette.
        assertEquals(9, subChunks.byteValue());
        assertEquals(1, subChunks.byteValue());
        assertEquals(3, subChunks.byteValue());
        assertEquals(5, subChunks.byteValue()); // V2, runtime IDs
        subChunks.skip(256 * Integer.BYTES);
        assertEquals(4, subChunks.varInt32());
        assertEquals(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.AIR), subChunks.varInt32());
        assertEquals(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.BEDROCK), subChunks.varInt32());
        assertEquals(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.STONE), subChunks.varInt32());
        assertEquals(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.DIRT), subChunks.varInt32());

        // Section Y=4 contains grass and air: a one-bit packed palette.
        assertEquals(9, subChunks.byteValue());
        assertEquals(1, subChunks.byteValue());
        assertEquals(4, subChunks.byteValue());
        assertEquals(3, subChunks.byteValue()); // V1, runtime IDs
        subChunks.skip(128 * Integer.BYTES);
        assertEquals(2, subChunks.varInt32());
        assertEquals(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.GRASS_BLOCK), subChunks.varInt32());
        assertEquals(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.AIR), subChunks.varInt32());

        // Exactly one biome storage follows each serialized block subchunk.
        for (int section = 0; section < subChunkCount; section++) {
            assertEquals(1, subChunks.byteValue()); // singleton plains biome palette
            assertEquals(1, subChunks.varInt32());
        }
        assertEquals(0, subChunks.byteValue()); // no border blocks
        assertEquals(0, subChunks.byteValue()); // empty block entity list: network NBT TAG_End
        assertTrue(subChunks.exhausted());
    }

    @Test
    void emptyChunkSendsOneBiomePaletteForEachOfItsFourNegativeSubChunks() {
        BedrockPacket packet = BedrockLevelChunkCodec.createPacket(new WorldChunk(0, 0));
        Reader reader = new Reader(packet.payload());

        reader.varInt32();
        reader.varInt32();
        reader.varInt32();
        int subChunkCount = reader.varUInt32();
        assertEquals(4, subChunkCount);
        assertFalse(reader.bool()); // optional client request subchunk limit is absent
        assertFalse(reader.bool()); // cacheEnabled = false
        assertEquals(0, reader.varUInt32()); // empty blob hash array
        byte[] data = reader.byteArray();
        reader.bool();
        assertTrue(reader.exhausted());

        Reader chunkData = new Reader(data);
        for (int sectionY = -4; sectionY < 0; sectionY++) {
            assertEquals(9, chunkData.byteValue());
            assertEquals(0, chunkData.byteValue());
            assertEquals(sectionY & 0xff, chunkData.byteValue());
        }
        for (int section = 0; section < subChunkCount; section++) {
            assertEquals(1, chunkData.byteValue());
            assertEquals(1, chunkData.varInt32());
        }
        assertEquals(0, chunkData.byteValue()); // border blocks
        assertEquals(0, chunkData.byteValue()); // block entities
        assertTrue(chunkData.exhausted());
        assertEquals(12 + 24 * 2 + 2, data.length);
    }

    private static final class Reader {
        private final byte[] data;
        private int offset;

        Reader(byte[] data) { this.data = data; }

        int varUInt32() {
            int result = 0;
            int shift = 0;
            while (true) {
                int next = Byte.toUnsignedInt(data[offset++]);
                result |= (next & 0x7f) << shift;
                if ((next & 0x80) == 0) return result;
                shift += 7;
                if (shift > 28) throw new AssertionError("VarUInt32 too long");
            }
        }

        int varInt32() {
            int value = varUInt32();
            return (value >>> 1) ^ -(value & 1);
        }

        int byteValue() { return Byte.toUnsignedInt(data[offset++]); }

        boolean bool() { return data[offset++] != 0; }

        void skip(int length) { offset += length; }

        byte[] byteArray() {
            int length = varUInt32();
            byte[] result = java.util.Arrays.copyOfRange(data, offset, offset + length);
            offset += length;
            return result;
        }

        boolean exhausted() { return offset == data.length; }
    }
}
