package ru.nexora.network.nethernet;

import java.util.Objects;

/** SDP offer after client identity and detached fingerprint verification. */
public record ValidatedClientOffer(
        String sanitizedSdp,
        NetherNetClientIdentity identity
) {
    public ValidatedClientOffer {
        Objects.requireNonNull(sanitizedSdp, "sanitizedSdp");
        Objects.requireNonNull(identity, "identity");
        if (!sanitizedSdp.startsWith("v=0") || sanitizedSdp.contains("a=identity:")) {
            throw new IllegalArgumentException("Offer SDP must be valid and have its identity line removed");
        }
    }
}
