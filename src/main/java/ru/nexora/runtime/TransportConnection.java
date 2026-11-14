package ru.nexora.runtime;

import java.nio.ByteBuffer;

/**
 * An established, transport-level peer connection.
 *
 * <p>This interface deliberately contains no Bedrock login or player identity
 * assumptions. A transport connection is not an authenticated player.
 * Implementations must document buffer ownership and thread-safety.</p>
 */
public interface TransportConnection extends AutoCloseable {
    /** Stable identifier unique among connections owned by this process. */
    String id();

    /** Whether the underlying transport is currently open. */
    boolean isOpen();

    /**
     * Sends an application payload using the selected delivery semantics.
     * The buffer is read-only from the implementation's perspective.
     */
    void send(ByteBuffer payload, DeliveryMode deliveryMode);

    /** Closes the peer connection and releases transport resources. */
    @Override
    void close();
}
