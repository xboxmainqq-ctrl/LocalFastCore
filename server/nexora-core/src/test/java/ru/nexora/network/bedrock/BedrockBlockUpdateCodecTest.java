package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;
import ru.nexora.world.BlockType;

import static org.junit.jupiter.api.Assertions.*;

class BedrockBlockUpdateCodecTest {
    @Test
    void encodesSignedCoordinatesHashedBlockIdFlagsAndLayer() {
        BedrockPacket packet = BedrockBlockUpdateCodec.createPacket(-2, 64, 3, BlockType.AIR);

        assertEquals(0x15, packet.packetId());
        Reader reader = new Reader(packet.payload());
        assertEquals(-2, reader.varInt32());
        assertEquals(64, reader.varInt32());
        assertEquals(3, reader.varInt32());
        assertEquals(Integer.toUnsignedLong(BedrockBlockRuntimeIds.forBlock(BlockType.AIR)), reader.varUInt32());
        assertEquals(3, reader.varUInt32());
        assertEquals(0, reader.varUInt32());
        assertTrue(reader.exhausted());
    }

    private static final class Reader {
        private final byte[] data;
        private int offset;
        Reader(byte[] data) { this.data = data; }

        long varUInt32() {
            long result = 0;
            int shift = 0;
            while (true) {
                int next = Byte.toUnsignedInt(data[offset++]);
                result |= (long) (next & 0x7f) << shift;
                if ((next & 0x80) == 0) return result;
                shift += 7;
                if (shift > 28) throw new AssertionError("VarUInt32 too long");
            }
        }

        int varInt32() {
            long value = varUInt32();
            return (int) ((value >>> 1) ^ -(value & 1));
        }

        boolean exhausted() { return offset == data.length; }
    }
}
