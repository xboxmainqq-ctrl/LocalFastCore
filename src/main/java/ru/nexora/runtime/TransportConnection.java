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
     * Attempts to enqueue an application payload using the selected delivery
     * semantics. The implementation must apply bounded outbound buffering.
     *
     * <p>{@link TransportSendResult#ACCEPTED} means the transport accepted the
     * message for asynchronous delivery, not that the peer received it.
     * {@link TransportSendResult#BACKPRESSURE} means the message was not
     * queued; callers should avoid busy-loop retries and apply their own
     * scheduling/drop policy. {@link TransportSendResult#CLOSED} means the
     * connection no longer accepts messages.</p>
     *
     * <p>The implementation must not modify the caller's buffer position or
     * contents. It must take ownership of the bytes needed for asynchronous
     * delivery before returning {@code ACCEPTED}.</p>
     *
     * @throws NullPointerException if payload or deliveryMode is null
     * @throws IllegalArgumentException if the payload exceeds the transport's
     *         documented per-message limit
     */
    TransportSendResult send(ByteBuffer payload, DeliveryMode deliveryMode);

    /** Closes the peer connection and releases transport resources. */
    @Override
    void close();
}
