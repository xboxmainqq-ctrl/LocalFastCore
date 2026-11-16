package ru.nexora.network.bedrock;

import java.nio.ByteBuffer;

/** Strict protocol-2225 TakeItemActor request decoder and server acknowledgement encoder. */
public final class BedrockTakeItemActorCodec {
    public static final int TAKE_ITEM_ACTOR_PACKET_ID = 0x11;

    private BedrockTakeItemActorCodec() {
    }

    public static DecodedPickup decode(BedrockPacket packet) {
        if (packet == null || packet.packetId() != TAKE_ITEM_ACTOR_PACKET_ID) {
            throw new IllegalArgumentException("Expected TakeItemActor packet 0x11");
        }
        Reader in = new Reader(packet.payload());
        long itemRuntimeId = in.varUInt64();
        long playerRuntimeId = in.varUInt64();
        in.requireExhausted();
        if (itemRuntimeId == 0 || playerRuntimeId == 0) {
            throw new IllegalArgumentException("TakeItemActor IDs must be non-zero");
        }
        return new DecodedPickup(itemRuntimeId, playerRuntimeId);
    }

    public static BedrockPacket createAcknowledgement(long itemRuntimeId, long playerRuntimeId) {
        if (itemRuntimeId <= 0 || playerRuntimeId <= 0) {
            throw new IllegalArgumentException("TakeItemActor IDs must be positive");
        }
        Writer out = new Writer();
        out.varUInt64(itemRuntimeId);
        out.varUInt64(playerRuntimeId);
        return new BedrockPacket(TAKE_ITEM_ACTOR_PACKET_ID, 0, 0, out.bytes());
    }

    public record DecodedPickup(long itemRuntimeId, long playerRuntimeId) {
    }

    private static final class Reader {
        private final ByteBuffer buffer;
        Reader(byte[] payload) {
            if (payload == null || payload.length > 20) throw new IllegalArgumentException("Invalid TakeItemActor payload size");
            buffer = ByteBuffer.wrap(payload);
        }
        long varUInt64() {
            long value = 0;
            for (int i = 0; i < 10; i++) {
                if (!buffer.hasRemaining()) throw new IllegalArgumentException("Truncated TakeItemActor VarUInt64");
                int current = Byte.toUnsignedInt(buffer.get());
                if (i == 9 && (current & 0xfe) != 0) {
                    throw new IllegalArgumentException("TakeItemActor VarUInt64 overflow");
                }
                value |= (long) (current & 0x7f) << (7 * i);
                if ((current & 0x80) == 0) {
                    if (value < 0) throw new IllegalArgumentException("TakeItemActor ID exceeds signed range");
                    return value;
                }
            }
            throw new IllegalArgumentException("TakeItemActor VarUInt64 too long");
        }
        void requireExhausted() {
            if (buffer.hasRemaining()) throw new IllegalArgumentException("Trailing TakeItemActor bytes");
        }
    }

    private static final class Writer {
        private final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(20);
        void varUInt64(long value) {
            if (value <= 0) throw new IllegalArgumentException("TakeItemActor ID must be positive");
            do {
                int next = (int) (value & 0x7f);
                value >>>= 7;
                if (value != 0) next |= 0x80;
                out.write(next);
            } while (value != 0);
        }
        byte[] bytes() { return out.toByteArray(); }
    }
}
