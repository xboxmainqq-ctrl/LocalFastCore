package ru.nexora.runtime;

/**
 * Immediate result of attempting to enqueue a transport message.
 *
 * <p>{@link #ACCEPTED} means the transport accepted ownership of a copy/view
 * for asynchronous delivery; it does not mean the remote peer received it.
 * {@link #BACKPRESSURE} means the message was not queued and may be retried
 * later according to the caller's scheduling policy. {@link #CLOSED} means
 * the connection is no longer accepting messages.</p>
 */
public enum TransportSendResult {
    ACCEPTED,
    BACKPRESSURE,
    CLOSED
}
