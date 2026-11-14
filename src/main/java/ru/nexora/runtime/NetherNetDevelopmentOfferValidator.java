package ru.nexora.runtime;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * Explicitly unauthenticated admission mode for local transport development.
 *
 * <p>This must never be used on a public or production server. It accepts
 * offers without a client identity assertion and therefore provides no
 * player authentication, authorization, or anti-impersonation protection.</p>
 */
public final class NetherNetDevelopmentOfferValidator implements NetherNetOfferValidator {
    private final Predicate<String> networkIdPolicy;

    public NetherNetDevelopmentOfferValidator(boolean explicitlyEnableDevelopmentMode) {
        this(explicitlyEnableDevelopmentMode, ignored -> true);
    }

    public NetherNetDevelopmentOfferValidator(boolean explicitlyEnableDevelopmentMode,
                                              Predicate<String> networkIdPolicy) {
        if (!explicitlyEnableDevelopmentMode) {
            throw new IllegalArgumentException(
                    "Unauthenticated development mode must be explicitly enabled");
        }
        this.networkIdPolicy = Objects.requireNonNull(networkIdPolicy, "networkIdPolicy");
    }

    @Override
    public boolean requiresIdentity() {
        return false;
    }

    @Override
    public void validate(String networkId, NetherNetSdpDocument offer) {
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(offer, "offer");
        if (offer.identityEnvelopeJson() != null) {
            throw new SecurityException(
                    "Development validator only accepts identity-free test offers");
        }
        if (!networkIdPolicy.test(networkId)) {
            throw new SecurityException("Development NetworkID policy rejected the peer");
        }
    }
}
