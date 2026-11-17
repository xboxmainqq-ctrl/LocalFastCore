package ru.nexora.network.bedrock;

/**
 * Protocol-2225 chunk-radius negotiation.
 *
 * The client requests a radius; the server clamps it to both the client's
 * advertised capability and the server's bounded view-distance budget.
 */
public final class BedrockChunkRadiusCodec {
    public static final int REQUEST_PACKET_ID = 0x45;
    public static final int UPDATED_PACKET_ID = 0x46;
    public static final int DEFAULT_RADIUS = 1;
    public static final int MAX_SERVER_RADIUS = 8;

    private BedrockChunkRadiusCodec() {
    }

    public static Request decodeRequest(BedrockPacket packet) {
        if (packet == null || packet.packetId() != REQUEST_PACKET_ID) {
            throw new IllegalArgumentException("Expected RequestChunkRadius packet");
        }
        byte[] payload = packet.payload();
        if (payload == null || payload.length < 2) {
            throw new IllegalArgumentException("RequestChunkRadius payload is truncated");
        }

        VarInt radius = readVarInt32(payload, 0);
        if (radius.value() < 0) {
            throw new IllegalArgumentException("Requested chunk radius must not be negative");
        }
        if (radius.nextOffset() != payload.length - 1) {
            throw new IllegalArgumentException("RequestChunkRadius payload has an invalid length");
        }
        int clientMaximum = Byte.toUnsignedInt(payload[radius.nextOffset()]);
        return new Request(radius.value(), clientMaximum);
    }

    public static int negotiateRadius(Request request) {
        if (request == null) throw new IllegalArgumentException("request must not be null");
        int requested = Math.max(1, request.radius());
        int clientMaximum = Math.max(1, request.clientMaximumRadius());
        return Math.min(MAX_SERVER_RADIUS, Math.min(requested, clientMaximum));
    }

    public static BedrockPacket createUpdatedPacket(int radius) {
        if (radius < 1 || radius > MAX_SERVER_RADIUS) {
            throw new IllegalArgumentException("Negotiated chunk radius must be between 1 and "
                    + MAX_SERVER_RADIUS);
        }
        Writer writer = new Writer();
        writer.varInt32(radius);
        return new BedrockPacket(UPDATED_PACKET_ID, 0, 0, writer.toByteArray());
    }

    public record Request(int radius, int clientMaximumRadius) {
        public Request {
            if (radius < 0) throw new IllegalArgumentException("radius must not be negative");
            if (clientMaximumRadius < 0 || clientMaximumRadius > 255) {
                throw new IllegalArgumentException("clientMaximumRadius must be in 0..255");
            }
        }
    }

    private static VarInt readVarInt32(byte[] payload, int offset) {
        long encoded = 0;
        int shift = 0;
        int cursor = offset;
        while (cursor < payload.length && cursor - offset < 5) {
            int next = Byte.toUnsignedInt(payload[cursor++]);
            encoded |= (long) (next & 0x7f) << shift;
            if ((next & 0x80) == 0) {
                if (encoded > 0xffff_ffffL) {
                    throw new IllegalArgumentException("RequestChunkRadius VarInt32 overflow");
                }
                int value = (int) ((encoded >>> 1) ^ -(encoded & 1L));
                return new VarInt(value, cursor);
            }
            shift += 7;
        }
        throw new IllegalArgumentException("RequestChunkRadius VarInt32 is malformed");
    }

    private record VarInt(int value, int nextOffset) {
    }

    private static final class Writer {
        private final java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(5);

        void varInt32(int value) {
            long encoded = Integer.toUnsignedLong((value << 1) ^ (value >> 31));
            do {
                int next = (int) (encoded & 0x7f);
                encoded >>>= 7;
                if (encoded != 0) next |= 0x80;
                output.write(next);
            } while (encoded != 0);
        }

        byte[] toByteArray() {
            return output.toByteArray();
        }
    }
}
