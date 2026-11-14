package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

/**
 * An established, transport-level peer connection.
 *
 * <p>This interface deliberately contains no Bedrock login or player identity
 * assumptions. A transport connection is not an authenticated player.
 * Listener callbacks may run on transport I/O threads and must not mutate
 * tick-owned game state directly.</p>
 */
public interface TransportConnection extends AutoCloseable {
    /** Stable identifier unique among connections owned by this process. */
    String id();

    /** Whether the underlying transport is currently open. */
    boolean isOpen();

    /**
     * Registers the inbound message listener. Each callback represents one
     * complete application message after transport-specific reassembly.
     * The listener should be installed immediately after accepting a connection.
     */
    void setMessageListener(Consumer<TransportMessage> listener);

    /** Registers a callback invoked once when the connection closes. */
    void setCloseListener(Consumer<TransportCloseEvent> listener);

    /**
     * Sends an application payload using the selected delivery semantics.
     * The buffer is read-only from the implementation's perspective.
     */
    void send(ByteBuffer payload, DeliveryMode deliveryMode);

    /** Closes the peer connection and releases transport resources. */
    @Override
    void close();
}
