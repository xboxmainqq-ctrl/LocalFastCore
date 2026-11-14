package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * One complete application message delivered by a transport data channel.
 *
 * <p>The payload is exposed as a read-only view. Implementations must not
 * mutate the supplied bytes after publishing the message. Consumers that need
 * to retain the data beyond the callback should copy it into owned storage.</p>
 */
public record TransportMessage(ByteBuffer payload, DeliveryMode deliveryMode) {
    public TransportMessage {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(deliveryMode, "deliveryMode");
        payload = payload.slice().asReadOnlyBuffer();
    }

    /** Returns an independent read-only view with its own position. */
    @Override
    public ByteBuffer payload() {
        return payload.asReadOnlyBuffer();
    }

    public int sizeBytes() {
        return payload.remaining();
    }
}
