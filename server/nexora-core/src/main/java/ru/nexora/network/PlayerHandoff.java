package ru.nexora.network;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable transfer envelope. The source must freeze authoritative player
 * state before issuing it; the destination must validate and claim it once.
 */
public final class PlayerHandoff {
    private final UUID transferId;
    private final long playerId;
    private final String sourceNode;
    private final String targetNode;
    private final byte[] statePayload;

    public PlayerHandoff(UUID transferId, long playerId, String sourceNode,
                         String targetNode, byte[] statePayload) {
        this.transferId = Objects.requireNonNull(transferId, "transferId");
        if (playerId < 0) throw new IllegalArgumentException("playerId < 0");
        if (sourceNode == null || sourceNode.isBlank()) throw new IllegalArgumentException("sourceNode is blank");
        if (targetNode == null || targetNode.isBlank()) throw new IllegalArgumentException("targetNode is blank");
        if (sourceNode.equals(targetNode)) throw new IllegalArgumentException("source and target are equal");
        this.playerId = playerId;
        this.sourceNode = sourceNode;
        this.targetNode = targetNode;
        this.statePayload = Arrays.copyOf(Objects.requireNonNull(statePayload, "statePayload"), statePayload.length);
    }

    public UUID transferId() { return transferId; }
    public long playerId() { return playerId; }
    public String sourceNode() { return sourceNode; }
    public String targetNode() { return targetNode; }
    public byte[] statePayload() { return Arrays.copyOf(statePayload, statePayload.length); }
}
