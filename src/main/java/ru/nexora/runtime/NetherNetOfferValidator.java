package ru.nexora.runtime;

/**
 * Admission boundary for an incoming NetherNet offer.
 *
 * <p>Implementations must verify the GameServerToken and detached fingerprint
 * JWS before returning normally. Throwing rejects the offer before a WebRTC
 * peer is allocated.</p>
 */
@FunctionalInterface
public interface NetherNetOfferValidator {
    /** Whether the offer must carry a cryptographically verified client identity. */
    default boolean requiresIdentity() {
        return true;
    }

    void validate(String networkId, NetherNetSdpDocument offer) throws Exception;

    /**
     * Validates an offer and returns its trusted identity when supported.
     * Production authenticated validators must override this method.
     */
    default NetherNetIdentityValidator.VerifiedIdentity validateAndGetIdentity(
            String networkId, NetherNetSdpDocument offer) throws Exception {
        validate(networkId, offer);
        return null;
    }
}