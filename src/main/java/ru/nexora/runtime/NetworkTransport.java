package ru.nexora.runtime;

import java.util.function.Consumer;

/**
 * Transport-neutral entry point for accepting Bedrock client connections.
 *
 * <p>Implementations own their signaling and wire transport. They must not
 * decode Bedrock game packets or mutate world state. The listener must be
 * configured before {@link #start()}.</p>
 */
public interface NetworkTransport extends RuntimeModule {
    /**
     * Registers the callback for newly established transport connections.
     * The callback may be invoked from an I/O thread and must hand work off
     * before touching tick-owned game state.
     */
    void setConnectionListener(Consumer<TransportConnection> listener);

    /** Describes the delivery modes provided by this transport. */
    TransportCapabilities capabilities();
}
