package ru.nexora.runtime;

import java.security.GeneralSecurityException;
import java.util.Objects;

/**
 * Complete validation chain for a client NetherNet offer identity:
 * bounded envelope parsing, GameServerToken issuer verification, and
 * detached ES384 proof over the offer's DTLS fingerprints.
 */
public final class NetherNetIdentityValidator {
    private final NetherNetGameServerTokenVerifier tokenVerifier;

    public NetherNetIdentityValidator(NetherNetGameServerTokenVerifier tokenVerifier) {
        this.tokenVerifier = Objects.requireNonNull(tokenVerifier, "tokenVerifier");
    }

    public VerifiedIdentity validate(NetherNetSdpDocument offer) throws Exception {
        Objects.requireNonNull(offer, "offer");
        if (offer.identityEnvelopeJson() == null) {
            throw new SecurityException("Authenticated client identity is required");
        }
        NetherNetIdentityAssertion assertion = NetherNetIdentityAssertion.parse(offer);
        NetherNetGameServerTokenVerifier.VerifiedClientIdentity tokenIdentity =
                tokenVerifier.verify(assertion);
        NetherNetFingerprintPayload fingerprintPayload =
                NetherNetFingerprintPayload.fromSdp(offer);
        boolean proofValid = NetherNetFingerprintJwsVerifier.verify(
                assertion.detachedFingerprintsJws(),
                fingerprintPayload,
                tokenIdentity.clientPublicKey());
        if (!proofValid) {
            throw new SecurityException("Client does not own the DTLS fingerprint key");
        }
        return new VerifiedIdentity(assertion.identityProviderDomain(),
                tokenIdentity.xuid(), tokenIdentity.displayName(),
                tokenIdentity.clientPublicKey());
    }

    public record VerifiedIdentity(String identityProviderDomain, String xuid,
                                   String displayName,
                                   java.security.interfaces.ECPublicKey clientPublicKey) {
        public VerifiedIdentity {
            Objects.requireNonNull(identityProviderDomain, "identityProviderDomain");
            Objects.requireNonNull(clientPublicKey, "clientPublicKey");
        }
    }
}
