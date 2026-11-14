package ru.nexora.runtime;

import java.util.concurrent.CompletionStage;

/**
 * WebRTC host adapter used by the HTTP signaling endpoint.
 *
 * <p>The implementation owns the actual PeerConnection, ICE gathering and
 * SCTP data channels. It must return a complete SDP answer, including the
 * server a=identity assertion. It must also publish the established channels
 * as a {@link TransportConnection} to the runtime's NetworkTransport.</p>
 */
public interface NetherNetPeerHost extends AutoCloseable {
    /**
     * Accepts an already validated offer with the a=identity line removed.
     * The returned answer must contain complete ICE candidates (full ICE).
     */
    CompletionStage<String> createAnswer(String networkId, String webRtcOfferSdp);

    /** Releases all peer connections and native WebRTC resources. */
    @Override
    void close();
}
