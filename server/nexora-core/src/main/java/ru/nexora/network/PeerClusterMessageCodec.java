package ru.nexora.network;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/**
 * Pairwise-key codec that binds the authenticated message source to the
 * configured peer key. Unlike a cluster-wide shared secret, a peer cannot
 * authenticate as a different node without that node's pairwise key.
 */
public final class PeerClusterMessageCodec implements ClusterMessageCodec {
    private final String localNodeId;
    private final Map<String, ClusterMessageCodec> peerCodecs;

    public PeerClusterMessageCodec(String localNodeId, Map<String, ? extends ClusterMessageCodec> peerCodecs) {
        if (localNodeId == null || !localNodeId.matches("[A-Za-z0-9._-]{1,64}"))
            throw new IllegalArgumentException("Invalid local node ID");
        this.localNodeId = localNodeId;
        Objects.requireNonNull(peerCodecs, "peerCodecs");
        if (peerCodecs.isEmpty()) throw new IllegalArgumentException("At least one peer codec is required");
        java.util.HashMap<String, ClusterMessageCodec> copy = new java.util.HashMap<>();
        peerCodecs.forEach((peer, codec) -> {
            if (peer == null || !peer.matches("[A-Za-z0-9._-]{1,64}") || peer.equals(localNodeId))
                throw new IllegalArgumentException("Invalid peer ID");
            copy.put(peer, Objects.requireNonNull(codec, "peer codec"));
        });
        this.peerCodecs = Map.copyOf(copy);
    }

    @Override
    public byte[] encode(ClusterMessage message) throws IOException {
        Objects.requireNonNull(message, "message");
        if (!localNodeId.equals(message.sourceNode()))
            throw new IOException("Cluster message source does not match local node");
        ClusterMessageCodec peerCodec = peerCodecs.get(message.targetNode());
        if (peerCodec == null) throw new IOException("No pairwise key configured for target peer");
        return peerCodec.encode(message);
    }

    @Override
    public ClusterMessage decode(byte[] envelope, String expectedTargetNode, long nowEpochMillis)
            throws IOException {
        IOException failure = null;
        for (Map.Entry<String, ClusterMessageCodec> peer : peerCodecs.entrySet()) {
            try {
                ClusterMessage decoded = peer.getValue().decode(envelope, expectedTargetNode, nowEpochMillis);
                if (peer.getKey().equals(decoded.sourceNode())) return decoded;
                throw new IOException("Authenticated source does not match pairwise key identity");
            } catch (IOException rejected) {
                if (failure == null) failure = rejected;
                else failure.addSuppressed(rejected);
            }
        }
        throw new IOException("Cluster message did not authenticate as a configured peer", failure);
    }
}
