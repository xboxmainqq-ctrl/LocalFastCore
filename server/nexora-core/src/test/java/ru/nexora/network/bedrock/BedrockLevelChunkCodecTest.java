package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;
import ru.nexora.world.FlatWorldGenerator;
import ru.nexora.world.WorldChunk;

import static org.junit.jupiter.api.Assertions.*;

class BedrockLevelChunkCodecTest {
    @Test
    void encodesAProtocol2225ChunkWithNegativeSubChunksAndThreeDimensionalBiomes() {
        WorldChunk chunk = new WorldChunk(-2, 3);
        new FlatWorldGenerator().generate(chunk, 1L);

        BedrockPacket packet = BedrockLevelChunkCodec.createPacket(chunk);
        assertEquals(0x3a, packet.packetId());

        Reader reader = new Reader(packet.payload());
        assertEquals(-2, reader.varInt32());
        assertEquals(3, reader.varInt32());
        assertEquals(0, reader.varInt32()); // Overworld
        assertEquals(9, reader.varUInt32()); // four negative plus five positive sub-chunks
        assertFalse(reader.bool()); // no sub-chunk request
        assertFalse(reader.bool()); // cache disabled
        assertEquals(0, reader.varUInt32()); // no blob hashes

        byte[] data = reader.byteArray();
        assertFalse(reader.bool()); // clientBiomeUpdate
        assertTrue(reader.exhausted());

        assertArrayEquals(new byte[] {9, 0, (byte) 0xfc, 9, 0, (byte) 0xfd,
                        9, 0, (byte) 0xfe, 9, 0, (byte) 0xff},
                java.util.Arrays.copyOf(data, 12));
        assertTrue(data.length > 24 * 2); // 24 singleton biome palettes are present
        assertEquals(0, data[data.length - 1]); // no border blocks
    }

    @Test
    void emptyChunkStillAdvertisesFourNegativeSubChunksAndAllBiomeSections() {
        BedrockPacket packet = BedrockLevelChunkCodec.createPacket(new WorldChunk(0, 0));
        Reader reader = new Reader(packet.payload());

        reader.varInt32();
        reader.varInt32();
        reader.varInt32();
        assertEquals(4, reader.varUInt32());
        reader.bool();
        reader.bool();
        reader.varUInt32();
        byte[] data = reader.byteArray();
        reader.bool();
        assertTrue(reader.exhausted());
        assertEquals(12 + 24 * 2 + 1, data.length);
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

        boolean bool() { return data[offset++] != 0; }

        byte[] byteArray() {
            int length = varUInt32();
            byte[] result = java.util.Arrays.copyOfRange(data, offset, offset + length);
            offset += length;
            return result;
        }

        boolean exhausted() { return offset == data.length; }
    }
}
