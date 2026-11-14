package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.util.Objects;

/** Codec for protocol 2225 PlayStatus packet payload (big-endian int32). */
public final class BedrockPlayStatusPacketCodec {
    public static final int PACKET_ID = 2;

    private BedrockPlayStatusPacketCodec() {
    }

    public static ByteBuffer encodePayload(BedrockPlayStatus status) {
        Objects.requireNonNull(status, "status");
        return ByteBuffer.allocate(Integer.BYTES)
                .putInt(status.wireValue())
                .flip()
                .asReadOnlyBuffer();
    }

    public static BedrockPlayStatus decodePayload(ByteBuffer payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.remaining() != Integer.BYTES) {
            throw new IllegalArgumentException("Bedrock PlayStatus payload must be exactly 4 bytes");
        }
        return BedrockPlayStatus.fromWireValue(payload.getInt());
    }
}
