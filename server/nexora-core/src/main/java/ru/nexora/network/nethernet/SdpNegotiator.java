package ru.nexora.network.nethernet;

/**
 * Creates a WebRTC SDP answer for one client offer.
 *
 * Implementations must validate the client's identity assertion, create the
 * PeerConnection, wait for ICE gathering to finish, and return a complete SDP
 * answer containing the required server identity assertion.
 */
@FunctionalInterface
public interface SdpNegotiator {
    String createAnswer(String networkId, String sdpOffer) throws Exception;
}
