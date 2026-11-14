package ru.nexora.runtime;

import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * WebRTC host adapter used by the HTTP signaling endpoint.
 *
 * <p>The implementation owns the actual PeerConnection, ICE gathering and
 * SCTP data channels. It returns a complete WebRTC SDP answer with all ICE
 * candidates; the HTTP signaling layer adds the signed server a=identity
 * assertion before returning the answer to the client. The implementation
 * publishes established channels as a {@link TransportConnection}.</p>
 */
public interface NetherNetPeerHost extends AutoCloseable {
    /**
     * Registers the listener for peers whose two NetherNet data channels have
     * been announced. The callback may run on a WebRTC I/O thread.
     */
    void setConnectionListener(Consumer<TransportConnection> listener);

    /**
     * Accepts an already validated offer with the a=identity line removed.
     * The returned answer must contain complete ICE candidates (full ICE).
     */
    CompletionStage<String> createAnswer(String networkId, String webRtcOfferSdp);

    /** Cancels and releases a peer whose signaling request did not complete. */
    default void cancel(String networkId) {
    }

    /** Releases all peer connections and native WebRTC resources. */
    @Override
    void close();
}
