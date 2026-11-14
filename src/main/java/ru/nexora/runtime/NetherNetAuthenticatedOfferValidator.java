package ru.nexora.runtime;

import java.util.Objects;

/**
 * Adapts the cryptographic identity verifier and server admission policy to
 * the signaling server's mandatory offer-validation boundary.
 */
public final class NetherNetAuthenticatedOfferValidator implements NetherNetOfferValidator {
    private final NetherNetIdentityValidator identityValidator;
    private final NetherNetPlayerAdmissionPolicy admissionPolicy;

    public NetherNetAuthenticatedOfferValidator(
            NetherNetIdentityValidator identityValidator,
            NetherNetPlayerAdmissionPolicy admissionPolicy) {
        this.identityValidator = Objects.requireNonNull(identityValidator, "identityValidator");
        this.admissionPolicy = Objects.requireNonNull(admissionPolicy, "admissionPolicy");
    }

    @Override
    public void validate(String networkId, NetherNetSdpDocument offer) throws Exception {
        NetherNetIdentityValidator.VerifiedIdentity identity = identityValidator.validate(offer);
        admissionPolicy.authorize(networkId, identity);
    }
}
