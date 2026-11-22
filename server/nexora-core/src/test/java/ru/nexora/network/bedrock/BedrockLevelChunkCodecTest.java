package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;
import ru.nexora.world.FlatWorldGenerator;
import ru.nexora.world.WorldChunk;

import static org.junit.jupiter.api.Assertions.*;

class BedrockLevelChunkCodecTest {
    @Test
    void encodesAProtocol2225ChunkWithBiomeDataForEachSubChunk() {
        WorldChunk chunk = new WorldChunk(-2, 3);
        new FlatWorldGenerator().generate(chunk, 1L);

        BedrockPacket packet = BedrockLevelChunkCodec.createPacket(chunk);
        assertEquals(0x3a, packet.packetId());

        Reader reader = new Reader(packet.payload());
        assertEquals(-2, reader.varInt32());
        assertEquals(3, reader.varInt32());
        assertEquals(0, reader.varInt32()); // Overworld dimension
        assertEquals(24, reader.varUInt32()); // full Overworld column: Y=-4 through Y=19
        assertFalse(reader.bool()); // requestSubChunks=false; no request limit follows
        assertFalse(reader.bool()); // cache disabled
        assertEquals(0, reader.varUInt32()); // empty blobIds array, always length-prefixed
        byte[] data = reader.byteArray();
        assertFalse(reader.bool()); // clientBiomeUpdate
        assertTrue(reader.exhausted());

        Reader subChunks = new Reader(data);
        for (int sectionY = -4; sectionY < 0; sectionY++) {
            assertEquals(9, subChunks.byteValue());
            assertEquals(2, subChunks.byteValue());
            assertEquals(sectionY & 0xff, subChunks.byteValue());
            assertEquals(1, subChunks.byteValue());
            assertEquals(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.AIR), subChunks.varInt32());
            assertEquals(1, subChunks.byteValue());
            assertEquals(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.AIR), subChunks.varInt32());
        }
        for (int sectionY = 0; sectionY < 3; sectionY++) {
            assertEquals(9, subChunks.byteValue());
            assertEquals(2, subChunks.byteValue()); // Empty intermediate section has both vanilla layers
            assertEquals(sectionY, subChunks.byteValue());
            assertEquals(1, subChunks.byteValue());
            assertEquals(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.AIR), subChunks.varInt32());
            assertEquals(1, subChunks.byteValue());
            assertEquals(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.AIR), subChunks.varInt32());
        }

        // Section Y=3 contains air, bedrock, stone and dirt: a two-bit packed palette.
        assertEquals(9, subChunks.byteValue());
        assertEquals(2, subChunks.byteValue());
        assertEquals(3, subChunks.byteValue());
        assertEquals(5, subChunks.byteValue()); // V2, runtime IDs
        subChunks.skip(256 * Integer.BYTES);
        assertEquals(4, subChunks.varInt32());
        assertEquals(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.AIR), subChunks.varInt32());
        assertEquals(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.BEDROCK), subChunks.varInt32());
        assertEquals(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.STONE), subChunks.varInt32());
        assertEquals(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.DIRT), subChunks.varInt32());
        assertEquals(1, subChunks.byteValue()); // second liquid storage layer
        assertEquals(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.AIR), subChunks.varInt32());

        // Section Y=4 contains grass and air: a one-bit packed palette.
        assertEquals(9, subChunks.byteValue());
        assertEquals(2, subChunks.byteValue());
        assertEquals(4, subChunks.byteValue());
        assertEquals(3, subChunks.byteValue()); // V1, runtime IDs
        subChunks.skip(128 * Integer.BYTES);
        assertEquals(2, subChunks.varInt32());
        assertEquals(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.GRASS_BLOCK), subChunks.varInt32());
        assertEquals(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.AIR), subChunks.varInt32());
        assertEquals(1, subChunks.byteValue()); // singleton liquid storage header
        assertEquals(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.AIR), subChunks.varInt32());

        // Remaining sections through Y=19 are explicit empty two-layer subchunks.
        for (int sectionY = 5; sectionY < 20; sectionY++) {
            assertEquals(9, subChunks.byteValue());
            assertEquals(2, subChunks.byteValue());
            assertEquals(sectionY, subChunks.byteValue());
            assertEquals(1, subChunks.byteValue());
            assertEquals(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.AIR), subChunks.varInt32());
            assertEquals(1, subChunks.byteValue());
            assertEquals(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.AIR), subChunks.varInt32());
        }

        for (int section = 0; section < 24; section++) {
            assertEquals(1, subChunks.byteValue()); // singleton plains biome palette
            assertEquals(1, subChunks.varInt32());
        }
        assertEquals(0, subChunks.byteValue()); // no border blocks
        assertEquals(0, subChunks.byteValue()); // empty block-entity NBT list: TAG_End
        assertTrue(subChunks.exhausted());
    }

    @Test
    void serializesBlocksInNegativeWorldSections() {
        WorldChunk chunk = new WorldChunk(0, 0);
        chunk.setBlock(1, -60, 2, ru.nexora.world.BlockType.BEDROCK);
        BedrockPacket packet = BedrockLevelChunkCodec.createPacket(chunk);
        Reader reader = new Reader(packet.payload());
        reader.varInt32();
        reader.varInt32();
        assertEquals(0, reader.varInt32()); // Overworld dimension
        assertEquals(24, reader.varUInt32()); // full Overworld column
        assertFalse(reader.bool()); // requestSubChunks=false; no request limit follows
        assertFalse(reader.bool()); // cache disabled
        assertEquals(0, reader.varUInt32()); // empty blobIds array, always length-prefixed
        byte[] data = reader.byteArray();
        Reader subChunks = new Reader(data);
        assertEquals(9, subChunks.byteValue());
        assertEquals(2, subChunks.byteValue());
        assertEquals(0xfc, subChunks.byteValue()); // signed subchunk Y=-4
        assertEquals(3, subChunks.byteValue()); // V1 packed runtime palette
        subChunks.skip(128 * Integer.BYTES);
        assertEquals(2, subChunks.varInt32());
        assertEquals(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.AIR), subChunks.varInt32());
        assertEquals(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.BEDROCK), subChunks.varInt32());
        assertEquals(1, subChunks.byteValue()); // mandatory second liquid layer
        assertEquals(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.AIR), subChunks.varInt32());
    }

    @Test
    void emptyChunkSendsFullOverworldColumnAndBiomePalettes() {
        BedrockPacket packet = BedrockLevelChunkCodec.createPacket(new WorldChunk(0, 0));
        Reader reader = new Reader(packet.payload());

        reader.varInt32();
        reader.varInt32();
        assertEquals(0, reader.varInt32()); // Overworld dimension
        assertEquals(24, reader.varUInt32());
        assertFalse(reader.bool()); // requestSubChunks=false; no request limit follows
        assertFalse(reader.bool()); // cache disabled
        assertEquals(0, reader.varUInt32()); // empty blobIds array, always length-prefixed
        byte[] data = reader.byteArray();
        reader.bool();
        assertTrue(reader.exhausted());
        assertEquals(24 * 15 + 24 * 2 + 2, data.length); // subchunks, biome palettes, border count, empty NBT TAG_End
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
