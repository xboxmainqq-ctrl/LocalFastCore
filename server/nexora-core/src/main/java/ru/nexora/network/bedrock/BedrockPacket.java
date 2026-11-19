package ru.nexora.network.bedrock;

import java.io.ByteArrayOutputStream;
import java.util.Objects;

/** One decoded Bedrock game packet inside a batch. */
public record BedrockPacket(
        int packetId,
        int senderSubClientId,
        int targetSubClientId,
        byte[] payload
) {
    public BedrockPacket {
        if (packetId < 0 || packetId > 0x3ff) throw new IllegalArgumentException("packetId must be 0..1023");
        if (senderSubClientId < 0 || senderSubClientId > 3) {
            throw new IllegalArgumentException("senderSubClientId must be 0..3");
        }
        if (targetSubClientId < 0 || targetSubClientId > 3) {
            throw new IllegalArgumentException("targetSubClientId must be 0..3");
        }
        Objects.requireNonNull(payload, "payload");
        payload = payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    /** Payload length without allocating the defensive copy returned by payload(). */
    int payloadLength() {
        return payload.length;
    }

    /** Writes the immutable packet payload without creating an intermediate copy. */
    void writePayloadTo(ByteArrayOutputStream output) {
        Objects.requireNonNull(output, "output");
        output.write(payload, 0, payload.length);
    }
}
