package ru.nexora.network;

import java.util.Objects;
import java.util.UUID;

/** Durable, versioned transfer ledger entry with a monotonically increasing fencing token. */
public record PlayerTransferRecord(
        UUID transferId,
        String playerXuid,
        String sourceNode,
        String targetNode,
        long playerRevision,
        State state,
        long fencingToken,
        long leaseExpiresAtEpochMillis,
        long version,
        long updatedAtEpochMillis
) {
    public enum State { PREPARED, CLAIMED, COMMITTED, ABORTED }

    public PlayerTransferRecord {
        Objects.requireNonNull(transferId, "transferId");
        if (playerXuid == null || !playerXuid.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("Invalid player XUID");
        validateNode(sourceNode, "sourceNode");
        validateNode(targetNode, "targetNode");
        if (sourceNode.equals(targetNode)) throw new IllegalArgumentException("Transfer nodes must differ");
        if (playerRevision < 0 || fencingToken < 0 || leaseExpiresAtEpochMillis < 0
                || version < 0 || updatedAtEpochMillis < 0) {
            throw new IllegalArgumentException("Negative transfer metadata");
        }
        Objects.requireNonNull(state, "state");
        if (state == State.PREPARED && fencingToken != 0)
            throw new IllegalArgumentException("Prepared transfer must not have a fencing token");
        if ((state == State.CLAIMED || state == State.COMMITTED) && fencingToken == 0)
            throw new IllegalArgumentException("Claimed transfer requires a fencing token");
    }

    public static PlayerTransferRecord prepared(UUID id, String xuid, String source, String target,
                                                long playerRevision, long nowEpochMillis) {
        return new PlayerTransferRecord(id, xuid, source, target, playerRevision,
                State.PREPARED, 0, 0, 0, nowEpochMillis);
    }

    public boolean leaseExpired(long nowEpochMillis) {
        return leaseExpiresAtEpochMillis <= nowEpochMillis;
    }

    public PlayerTransferRecord claimed(long nextFence, long leaseUntil, long now) {
        return new PlayerTransferRecord(transferId, playerXuid, sourceNode, targetNode, playerRevision,
                State.CLAIMED, nextFence, leaseUntil, version + 1, now);
    }

    public PlayerTransferRecord committed(long now) {
        return new PlayerTransferRecord(transferId, playerXuid, sourceNode, targetNode, playerRevision,
                State.COMMITTED, fencingToken, now, version + 1, now);
    }

    public PlayerTransferRecord aborted(long now) {
        return new PlayerTransferRecord(transferId, playerXuid, sourceNode, targetNode, playerRevision,
                State.ABORTED, fencingToken, now, version + 1, now);
    }

    private static void validateNode(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,64}"))
            throw new IllegalArgumentException("Invalid " + field);
    }
}
