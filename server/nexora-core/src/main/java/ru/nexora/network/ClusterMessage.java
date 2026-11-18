package ru.nexora.network;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Immutable bounded message exchanged between authenticated cluster nodes. */
public final class ClusterMessage {
    public static final int MAX_PAYLOAD_BYTES = 1_048_576;
    private final UUID messageId;
    private final String sourceNode;
    private final String targetNode;
    private final String type;
    private final long createdAtEpochMillis;
    private final byte[] payload;

    public ClusterMessage(UUID messageId, String sourceNode, String targetNode, String type,
                          long createdAtEpochMillis, byte[] payload) {
        this.messageId = Objects.requireNonNull(messageId, "messageId");
        if (sourceNode == null || !sourceNode.matches("[A-Za-z0-9._-]{1,64}"))
            throw new IllegalArgumentException("Invalid source node");
        if (targetNode == null || !targetNode.matches("[A-Za-z0-9._-]{1,64}"))
            throw new IllegalArgumentException("Invalid target node");
        if (sourceNode.equals(targetNode)) throw new IllegalArgumentException("Cluster message nodes must differ");
        if (type == null || !type.matches("[A-Z][A-Z0-9_.-]{0,63}"))
            throw new IllegalArgumentException("Invalid cluster message type");
        if (createdAtEpochMillis < 0) throw new IllegalArgumentException("Negative message timestamp");
        Objects.requireNonNull(payload, "payload");
        if (payload.length > MAX_PAYLOAD_BYTES) throw new IllegalArgumentException("Cluster payload too large");
        this.sourceNode = sourceNode;
        this.targetNode = targetNode;
        this.type = type;
        this.createdAtEpochMillis = createdAtEpochMillis;
        this.payload = Arrays.copyOf(payload, payload.length);
    }

    public UUID messageId() { return messageId; }
    public String sourceNode() { return sourceNode; }
    public String targetNode() { return targetNode; }
    public String type() { return type; }
    public long createdAtEpochMillis() { return createdAtEpochMillis; }
    public byte[] payload() { return Arrays.copyOf(payload, payload.length); }
}
