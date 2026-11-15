package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Decoder for protocol-2168+ ResourcePackClientResponse packet payloads. */
public final class BedrockResourcePackClientResponsePacketCodec {
    public static final int PACKET_ID = 8;

    private static final String[] STATUS_NAMES = {
            null, "cancel", "downloading", "downloadingfinished", "resourcepackstackfinished"
    };

    private BedrockResourcePackClientResponsePacketCodec() {
    }

    public static BedrockResourcePackClientResponse decodePayload(ByteBuffer payload) {
        Objects.requireNonNull(payload, "payload");
        int wireStatus = BedrockBinary.readVarUInt32(payload);
        BedrockResourcePackClientResponse.Status status =
                BedrockResourcePackClientResponse.Status.fromWireValue(wireStatus);
        String statusName = BedrockBinary.readString(payload, 64);
        if (!STATUS_NAMES[wireStatus].equals(statusName)) {
            throw new IllegalArgumentException("Resource pack response status name does not match its code");
        }
        List<String> packIds = new ArrayList<>();
        if (status == BedrockResourcePackClientResponse.Status.SEND_PACKS) {
            int count = BedrockBinary.readVarUInt32(payload);
            if (count > 1024) {
                throw new IllegalArgumentException("Too many requested resource packs");
            }
            for (int i = 0; i < count; i++) {
                packIds.add(BedrockBinary.readString(payload, 256));
            }
        }
        if (payload.hasRemaining()) {
            throw new IllegalArgumentException("Trailing bytes in ResourcePackClientResponse payload");
        }
        return new BedrockResourcePackClientResponse(status, packIds);
    }
}
