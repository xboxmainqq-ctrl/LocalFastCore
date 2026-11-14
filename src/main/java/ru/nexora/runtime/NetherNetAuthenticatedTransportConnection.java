package ru.nexora.runtime;

import java.util.Optional;

/**
 * A NetherNet data-channel connection whose signaling offer may carry a
 * cryptographically verified identity bound to the DTLS fingerprint.
 * Development-only unauthenticated offers expose Optional.empty().
 */
public interface NetherNetAuthenticatedTransportConnection extends TransportConnection {
    Optional<NetherNetIdentityValidator.VerifiedIdentity> authenticatedIdentity();
}
