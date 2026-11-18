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

    /** Cancels a transfer before any destination claim has acquired ownership. */
    public PlayerTransferRecord abortedPrepared(long now) {
        if (state != State.PREPARED) throw new IllegalStateException("Only PREPARED transfers can be cancelled");
        return new PlayerTransferRecord(transferId, playerXuid, sourceNode, targetNode, playerRevision,
                State.ABORTED, 0, now, version + 1, now);
    }

    /** Validates a monotonic successor transition without relying on the caller's adapter. */
    boolean isLegalSuccessorOf(PlayerTransferRecord current) {
        Objects.requireNonNull(current, "current");
        if (!transferId.equals(current.transferId)
                || !playerXuid.equals(current.playerXuid)
                || !sourceNode.equals(current.sourceNode)
                || !targetNode.equals(current.targetNode)
                || playerRevision != current.playerRevision
                || version != current.version + 1
                || updatedAtEpochMillis < current.updatedAtEpochMillis) {
            return false;
        }
        return switch (current.state) {
            case PREPARED -> (state == State.CLAIMED
                    && current.fencingToken == 0
                    && fencingToken == 1
                    && leaseExpiresAtEpochMillis > updatedAtEpochMillis)
                    || (state == State.ABORTED
                    && current.fencingToken == 0
                    && fencingToken == 0
                    && leaseExpiresAtEpochMillis == updatedAtEpochMillis);
            case CLAIMED -> {
                if (state == State.CLAIMED) {
                    yield current.fencingToken < Long.MAX_VALUE
                            && fencingToken == current.fencingToken + 1
                            && current.leaseExpired(updatedAtEpochMillis)
                            && leaseExpiresAtEpochMillis > updatedAtEpochMillis;
                }
                boolean terminal = state == State.COMMITTED || state == State.ABORTED;
                yield terminal && fencingToken == current.fencingToken
                        && (state == State.ABORTED || !current.leaseExpired(updatedAtEpochMillis))
                        && leaseExpiresAtEpochMillis == updatedAtEpochMillis;
            }
            case COMMITTED, ABORTED -> false;
        };
    }

    private static void validateNode(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,64}"))
            throw new IllegalArgumentException("Invalid " + field);
    }
}
