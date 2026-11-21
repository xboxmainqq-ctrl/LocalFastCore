package ru.nexora.network.bedrock;

/**
 * Protocol-2225 ServerboundLoadingScreenPacket (packet 312 / 0x138).
 * The client reports opening and closing its world-loading screen.
 */
public final class BedrockLoadingScreenCodec {
    public static final int PACKET_ID = 0x138;
    public static final int START_LOADING_SCREEN = 1;
    public static final int END_LOADING_SCREEN = 2;

    private BedrockLoadingScreenCodec() {
    }

    public static LoadingScreen decode(BedrockPacket packet) {
        if (packet == null || packet.packetId() != PACKET_ID) {
            throw new IllegalArgumentException("Expected ServerboundLoadingScreen packet");
        }
        byte[] payload = packet.payload();
        VarInt type = readVarInt32(payload, 0);
        int offset = type.nextOffset();
        if (offset >= payload.length) {
            throw new IllegalArgumentException("Loading screen optional-ID flag is missing");
        }
        int present = Byte.toUnsignedInt(payload[offset++]);
        if (present > 1) {
            throw new IllegalArgumentException("Loading screen optional-ID flag must be 0 or 1");
        }
        Integer screenId = null;
        if (present == 1) {
            if (payload.length - offset != 4) {
                throw new IllegalArgumentException("Loading screen ID must contain exactly four bytes");
            }
            screenId = Byte.toUnsignedInt(payload[offset])
                    | (Byte.toUnsignedInt(payload[offset + 1]) << 8)
                    | (Byte.toUnsignedInt(payload[offset + 2]) << 16)
                    | (Byte.toUnsignedInt(payload[offset + 3]) << 24);
            offset += 4;
        }
        if (offset != payload.length) {
            throw new IllegalArgumentException("Loading screen packet has trailing bytes");
        }
        if (type.value() != START_LOADING_SCREEN && type.value() != END_LOADING_SCREEN) {
            throw new IllegalArgumentException("Unknown loading screen packet type " + type.value());
        }
        return new LoadingScreen(type.value(), screenId);
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
                    throw new IllegalArgumentException("Loading screen VarInt32 overflow");
                }
                return new VarInt((int) ((encoded >>> 1) ^ -(encoded & 1L)), cursor);
            }
            shift += 7;
        }
        throw new IllegalArgumentException("Loading screen VarInt32 is malformed");
    }

    public record LoadingScreen(int type, Integer screenId) {
        public boolean isStart() {
            return type == START_LOADING_SCREEN;
        }

        public boolean isEnd() {
            return type == END_LOADING_SCREEN;
        }
    }

    private record VarInt(int value, int nextOffset) {
    }
}
