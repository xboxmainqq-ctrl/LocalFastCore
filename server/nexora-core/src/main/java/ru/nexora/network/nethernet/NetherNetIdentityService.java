package ru.nexora.network.nethernet;

/**
 * Cryptographic identity boundary for NetherNet SDP negotiation.
 *
 * A production implementation must validate the client's GameServerToken and
 * detached fingerprint JWS, remove the a=identity line before WebRTC parsing,
 * and add the server's signed identity assertion to the final answer.
 */
public interface NetherNetIdentityService {
    ValidatedClientOffer validateClientOfferAndStripIdentity(String networkId, String offerSdp) throws Exception;

    String appendServerIdentityAssertion(String answerSdp) throws Exception;
}
