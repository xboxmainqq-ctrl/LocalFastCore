package ru.nexora.runtime;

import java.util.Objects;

/**
 * A NetherNet data-channel connection whose signaling offer identity was
 * cryptographically verified and bound to the DTLS fingerprint before peer
 * allocation. This is not a generic property of arbitrary transports.
 */
public interface NetherNetAuthenticatedTransportConnection extends TransportConnection {
    NetherNetIdentityValidator.VerifiedIdentity authenticatedIdentity();

    static NetherNetAuthenticatedTransportConnection require(TransportConnection connection) {
        Objects.requireNonNull(connection, "connection");
        if (!(connection instanceof NetherNetAuthenticatedTransportConnection authenticated)) {
            throw new SecurityException("Transport connection has no verified NetherNet identity");
        }
        return authenticated;
    }
}
