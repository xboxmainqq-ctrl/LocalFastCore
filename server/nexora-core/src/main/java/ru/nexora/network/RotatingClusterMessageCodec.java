package ru.nexora.network;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Key-ring wrapper for controlled HMAC key rotation. New messages are signed
 * with the active key; inbound messages may validate against a bounded set of
 * active/previous keys during the configured rotation grace period.
 */
public final class RotatingClusterMessageCodec {
    private final AuthenticatedClusterMessageCodec active;
    private final List<AuthenticatedClusterMessageCodec> accepted;

    public RotatingClusterMessageCodec(
            AuthenticatedClusterMessageCodec active,
            List<AuthenticatedClusterMessageCodec> acceptedKeys
    ) {
        this.active = Objects.requireNonNull(active, "active");
        Objects.requireNonNull(acceptedKeys, "acceptedKeys");
        if (acceptedKeys.isEmpty() || acceptedKeys.size() > 4)
            throw new IllegalArgumentException("Accepted key ring must contain 1..4 keys");
        if (acceptedKeys.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("Accepted key ring contains null");
        this.accepted = List.copyOf(acceptedKeys);
        if (!this.accepted.contains(active))
            throw new IllegalArgumentException("Active codec must be in accepted key ring");
    }

    public byte[] encode(ClusterMessage message) throws IOException {
        return active.encode(message);
    }

    public ClusterMessage decode(byte[] envelope, String expectedTargetNode, long nowEpochMillis)
            throws IOException {
        IOException failure = null;
        for (AuthenticatedClusterMessageCodec candidate : accepted) {
            try {
                return candidate.decode(envelope, expectedTargetNode, nowEpochMillis);
            } catch (IOException rejected) {
                if (failure == null) failure = rejected;
                else failure.addSuppressed(rejected);
            }
        }
        throw new IOException("Cluster message did not authenticate with any accepted key", failure);
    }
}
