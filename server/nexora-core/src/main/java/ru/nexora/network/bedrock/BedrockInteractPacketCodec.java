package ru.nexora.network.bedrock;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Protocol-2225 serverbound InteractPacket decoder. */
public final class BedrockInteractPacketCodec {
    public static final int PACKET_ID = 0x21;
    public static final int ACTION_OPEN_INVENTORY = 6;
    public static final int ACTION_INTERACT_UPDATE = 4;

    private BedrockInteractPacketCodec() {
    }

    public static Decoded decode(BedrockPacket packet) {
        if (packet == null || packet.packetId() != PACKET_ID) {
            throw new IllegalArgumentException("Expected Interact packet 0x21");
        }
        Reader in = new Reader(packet.payload());
        int action = in.uint8();
        long targetRuntimeId = in.varUInt64();
        if (action == ACTION_INTERACT_UPDATE) {
            in.float32();
            in.float32();
            in.float32();
        }
        in.requireExhausted();
        return new Decoded(action, targetRuntimeId);
    }

    public record Decoded(int action, long targetRuntimeId) {
    }

    private static final class Reader {
        private final ByteBuffer buffer;

        private Reader(byte[] payload) {
            if (payload == null || payload.length > 64) {
                throw new IllegalArgumentException("Interact payload size is invalid");
            }
            buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        }

        int uint8() {
            require(1);
            return Byte.toUnsignedInt(buffer.get());
        }

        float float32() {
            require(Float.BYTES);
            float value = buffer.getFloat();
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite Interact position");
            return value;
        }

        long varUInt64() {
            long value = 0;
            for (int i = 0; i < 10; i++) {
                require(1);
                int next = Byte.toUnsignedInt(buffer.get());
                if (i == 9 && (next & 0xfe) != 0) {
                    throw new IllegalArgumentException("Interact runtime ID overflows VarUInt64");
                }
                value |= (long) (next & 0x7f) << (7 * i);
                if ((next & 0x80) == 0) {
                    if (value < 0) throw new IllegalArgumentException("Interact runtime ID exceeds signed range");
                    return value;
                }
            }
            throw new IllegalArgumentException("Interact runtime ID VarUInt64 is too long");
        }

        void requireExhausted() {
            if (buffer.hasRemaining()) throw new IllegalArgumentException("Trailing Interact packet bytes");
        }

        private void require(int length) {
            if (buffer.remaining() < length) throw new IllegalArgumentException("Truncated Interact packet");
        }
    }
}
