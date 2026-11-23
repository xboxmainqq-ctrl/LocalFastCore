package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;
import ru.nexora.world.FlatWorldGenerator;
import ru.nexora.world.WorldChunk;

import static org.junit.jupiter.api.Assertions.*;

class BedrockLevelChunkCodecTest {
    @Test
    void encodesNukkitMotLevelDbChunkWithOneBiomePalettePerTransmittedSubchunk() {
        WorldChunk chunk = new WorldChunk(-2, 3);
        new FlatWorldGenerator().generate(chunk, 1L);

        BedrockPacket packet = BedrockLevelChunkCodec.createPacket(chunk);
        assertEquals(0x3a, packet.packetId());

        Reader reader = new Reader(packet.payload());
        assertEquals(-2, reader.varInt32());
        assertEquals(3, reader.varInt32());
        assertEquals(0, reader.varInt32()); // Overworld dimension
        assertEquals(9, reader.varUInt32()); // four negative sections + positive sections Y=0..4
        assertFalse(reader.bool()); // cache disabled
        assertEquals(0, reader.varUInt32()); // empty blobIds array
        byte[] data = reader.byteArray();
        assertFalse(reader.bool()); // clientBiomeUpdate
        assertTrue(reader.exhausted());

        Reader subChunks = new Reader(data);
        for (int sectionY = -4; sectionY < 3; sectionY++) {
            assertEquals(9, subChunks.byteValue());
            assertEquals(0, subChunks.byteValue()); // Nukkit-MOT empty subchunk has zero layers
            assertEquals(sectionY & 0xff, subChunks.byteValue());
        }

        // Section Y=3 contains air, bedrock, stone and dirt: a two-bit packed palette.
        assertEquals(9, subChunks.byteValue());
        assertEquals(2, subChunks.byteValue()); // Nukkit-MOT fresh LevelDB sections have two layers
        assertEquals(3, subChunks.byteValue());
        assertEquals(5, subChunks.byteValue()); // V2, runtime IDs
        subChunks.skip(256 * Integer.BYTES);
        assertEquals(4, subChunks.varInt32());
        assertEquals(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.AIR), subChunks.varInt32());
        assertEquals(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.BEDROCK), subChunks.varInt32());
        assertEquals(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.STONE), subChunks.varInt32());
        assertEquals(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.DIRT), subChunks.varInt32());
        assertEquals(1, subChunks.byteValue()); // second layer: singleton air
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
        assertEquals(1, subChunks.byteValue()); // second layer: singleton air
        assertEquals(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.AIR), subChunks.varInt32());

        for (int section = 0; section < 9; section++) {
            assertEquals(1, subChunks.byteValue()); // one biome palette per transmitted subchunk
            assertEquals(1, subChunks.varInt32());
        }
        assertEquals(0, subChunks.byteValue()); // no border blocks
        // Nukkit-MOT's empty block-entity collection contributes no NBT bytes.
        assertTrue(subChunks.exhausted());
    }

    @Test
    void serializesNegativeSectionsAndMatchingBiomePalette() {
        WorldChunk chunk = new WorldChunk(0, 0);
        chunk.setBlock(1, -60, 2, ru.nexora.world.BlockType.BEDROCK);
        BedrockPacket packet = BedrockLevelChunkCodec.createPacket(chunk);
        Reader reader = new Reader(packet.payload());
        reader.varInt32();
        reader.varInt32();
        assertEquals(0, reader.varInt32()); // Overworld dimension
        assertEquals(1, reader.varUInt32()); // only section Y=-4 is non-empty
        assertFalse(reader.bool());
        assertEquals(0, reader.varUInt32());
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
        assertEquals(1, subChunks.byteValue()); // second layer: singleton air
        assertEquals(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.AIR), subChunks.varInt32());
        for (int section = 0; section < 1; section++) {
            assertEquals(1, subChunks.byteValue());
            assertEquals(1, subChunks.varInt32());
        }
        assertEquals(0, subChunks.byteValue()); // no border blocks
        assertTrue(subChunks.exhausted());
    }

    @Test
    void emptyChunkContainsNoSubchunkBiomePalettes() {
        BedrockPacket packet = BedrockLevelChunkCodec.createPacket(new WorldChunk(0, 0));
        Reader reader = new Reader(packet.payload());

        reader.varInt32();
        reader.varInt32();
        assertEquals(0, reader.varInt32()); // Overworld dimension
        assertEquals(0, reader.varUInt32()); // empty LevelDB chunk has no block subchunks
        assertFalse(reader.bool());
        assertEquals(0, reader.varUInt32());
        byte[] data = reader.byteArray();
        reader.bool();
        assertTrue(reader.exhausted());

        Reader chunkData = new Reader(data);
        assertEquals(0, chunkData.byteValue()); // no border blocks; empty NBT collection is zero bytes
        assertTrue(chunkData.exhausted());
        assertEquals(1, data.length); // empty chunk has no subchunks, biome palettes, or block entities
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

        void skip(int length) {
            if (length < 0 || offset + length > data.length) throw new AssertionError("Truncated test payload");
            offset += length;
        }

        int remaining() { return data.length - offset; }

        byte[] byteArray() {
            int length = varUInt32();
            if (length < 0 || offset + length > data.length) throw new AssertionError("Truncated test byte array");
            byte[] result = java.util.Arrays.copyOfRange(data, offset, offset + length);
            offset += length;
            return result;
        }

        boolean exhausted() { return offset == data.length; }
    }
}
