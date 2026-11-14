package ru.nexora.runtime;

/**
 * Admission boundary for an incoming NetherNet offer.
 *
 * <p>Implementations must verify the GameServerToken and detached fingerprint
 * JWS before returning normally. Parsing the envelope alone is not validation.
 * Throwing rejects the offer before a WebRTC peer is allocated.</p>
 */
@FunctionalInterface
public interface NetherNetOfferValidator {
    void validate(String networkId, NetherNetSdpDocument offer) throws Exception;
}
