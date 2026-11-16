package ru.nexora.network.bedrock;

import ru.nexora.world.BlockType;

import java.io.ByteArrayOutputStream;

/** Protocol-2225 single-block UpdateBlock packet encoder. */
public final class BedrockBlockUpdateCodec {
    public static final int UPDATE_BLOCK_PACKET_ID = 0x15;
    public static final int FLAG_NEIGHBORS_AND_NETWORK = 3;

    private BedrockBlockUpdateCodec() {
    }

    public static BedrockPacket createPacket(int x, int y, int z, BlockType type) {
        if (type == null) throw new IllegalArgumentException("Block type must not be null");
        Writer out = new Writer();
        out.varInt32(x);
        out.varInt32(y);
        out.varInt32(z);
        out.varUInt32(Integer.toUnsignedLong(BedrockBlockRuntimeIds.forBlock(type)));
        out.varUInt32(FLAG_NEIGHBORS_AND_NETWORK);
        out.varUInt32(0); // Main block layer
        return new BedrockPacket(UPDATE_BLOCK_PACKET_ID, 0, 0, out.toByteArray());
    }

    private static final class Writer {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream(24);

        void varInt32(int value) {
            varUInt32(Integer.toUnsignedLong((value << 1) ^ (value >> 31)));
        }

        void varUInt32(long value) {
            if (value < 0 || value > 0xffff_ffffL) {
                throw new IllegalArgumentException("VarUInt32 out of range");
            }
            do {
                int next = (int) (value & 0x7f);
                value >>>= 7;
                if (value != 0) next |= 0x80;
                output.write(next);
            } while (value != 0);
        }

        byte[] toByteArray() {
            return output.toByteArray();
        }
    }
}
