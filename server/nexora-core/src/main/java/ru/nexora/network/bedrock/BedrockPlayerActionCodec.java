package ru.nexora.network.bedrock;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Bounded decoder for protocol-2225 PlayerAction packets. */
public final class BedrockPlayerActionCodec {
    public static final int PLAYER_ACTION_PACKET_ID = 0x24;
    public static final int ACTION_DROP_ITEM = 4;
    public static final int ACTION_RESPAWN = 7;
    public static final int ACTION_START_USING_ITEM = 37;

    private BedrockPlayerActionCodec() {
    }

    public static PlayerAction decode(BedrockPacket packet) {
        if (packet.packetId() != PLAYER_ACTION_PACKET_ID) {
            throw new IllegalArgumentException("Expected PlayerAction packet");
        }
        Reader in = new Reader(packet.payload());
        long runtimeEntityId = in.varUInt64();
        int action = in.varInt32();
        int x = in.varInt32();
        int y = in.varInt32();
        int z = in.varInt32();
        int resultX = in.varInt32();
        int resultY = in.varInt32();
        int resultZ = in.varInt32();
        int face = in.varInt32();
        if (runtimeEntityId <= 0 || action < 0 || action > 37
                || Math.abs((long) x) > 30_000_000 || Math.abs((long) z) > 30_000_000
                || y < -2048 || y > 2048 || face < -1 || face > 6
                || Math.abs((long) resultX) > 30_000_000
                || Math.abs((long) resultZ) > 30_000_000
                || resultY < -2048 || resultY > 2048) {
            throw new IllegalArgumentException("PlayerAction fields are outside supported bounds");
        }
        in.requireEnd();
        return new PlayerAction(runtimeEntityId, action, x, y, z, resultX, resultY, resultZ, face);
    }

    public record PlayerAction(
            long runtimeEntityId, int action,
            int x, int y, int z, int resultX, int resultY, int resultZ, int face
    ) {
    }

    private static final class Reader {
        private final ByteBuffer buffer;
        Reader(byte[] payload) { buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN); }
        void require(int count) {
            if (count < 0 || buffer.remaining() < count) throw new IllegalArgumentException("Truncated PlayerAction packet");
        }
        long varUInt64() {
            long value = 0;
            for (int i = 0; i < 10; i++) {
                require(1);
                int next = Byte.toUnsignedInt(buffer.get());
                if (i == 9 && (next & 0xfe) != 0) throw new IllegalArgumentException("PlayerAction VarUInt64 overflow");
                value |= (long) (next & 0x7f) << (7 * i);
                if ((next & 0x80) == 0) return value;
            }
            throw new IllegalArgumentException("PlayerAction VarUInt64 is too long");
        }
        int varUInt32() {
            long value = 0;
            for (int i = 0; i < 5; i++) {
                require(1);
                int next = Byte.toUnsignedInt(buffer.get());
                if (i == 4 && (next & 0xf0) != 0) throw new IllegalArgumentException("PlayerAction VarUInt32 overflow");
                value |= (long) (next & 0x7f) << (7 * i);
                if ((next & 0x80) == 0) {
                    if (value > Integer.MAX_VALUE) throw new IllegalArgumentException("PlayerAction VarUInt32 too large");
                    return (int) value;
                }
            }
            throw new IllegalArgumentException("PlayerAction VarUInt32 is too long");
        }
        int varInt32() {
            int value = varUInt32();
            return (value >>> 1) ^ -(value & 1);
        }
        void requireEnd() {
            if (buffer.hasRemaining()) throw new IllegalArgumentException("Trailing PlayerAction bytes");
        }
    }
}
