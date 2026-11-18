package ru.nexora.network;

import java.util.Objects;
import java.util.Optional;

/**
 * Applies the latest durable transfer decision to Bedrock admission and
 * active-session processing. This is a transfer fence, not a global lease for
 * players that have never participated in a transfer.
 */
public final class PlayerTransferOwnershipGuard {
    public enum LoginDecision {
        LEGACY_ALLOWED,
        RESTORE_CANONICAL,
        RESTORE_TRANSFER_CHECKPOINT
    }

    private PlayerTransferOwnershipGuard() {
    }

    public static LoginDecision loginDecision(Optional<PlayerTransferRecord> latest, String localNodeId) {
        Objects.requireNonNull(latest, "latest");
        requireNodeId(localNodeId);
        if (latest.isEmpty()) return LoginDecision.LEGACY_ALLOWED;
        PlayerTransferRecord record = latest.orElseThrow();
        return switch (record.state()) {
            case PREPARED -> throw new SecurityException(
                    "Player transfer is prepared; login is blocked until the transfer is resolved");
            case CLAIMED -> {
                if (!localNodeId.equals(record.targetNode())) {
                    throw new SecurityException("Player is claimed by another server node");
                }
                yield LoginDecision.RESTORE_TRANSFER_CHECKPOINT;
            }
            case COMMITTED -> {
                if (!localNodeId.equals(record.targetNode())) {
                    throw new SecurityException("Player ownership has moved to another server node");
                }
                yield LoginDecision.RESTORE_CANONICAL;
            }
            case ABORTED -> {
                if (!localNodeId.equals(record.sourceNode())) {
                    throw new SecurityException("Aborted transfer restores ownership to its source node");
                }
                yield LoginDecision.RESTORE_CANONICAL;
            }
        };
    }

    /** True only for a node that may continue processing an already active session. */
    public static boolean mayProcessSession(Optional<PlayerTransferRecord> latest, String localNodeId) {
        Objects.requireNonNull(latest, "latest");
        requireNodeId(localNodeId);
        if (latest.isEmpty()) return true;
        PlayerTransferRecord record = latest.orElseThrow();
        return switch (record.state()) {
            case PREPARED, CLAIMED -> false;
            case COMMITTED -> localNodeId.equals(record.targetNode());
            case ABORTED -> localNodeId.equals(record.sourceNode());
        };
    }

    private static void requireNodeId(String nodeId) {
        if (nodeId == null || !nodeId.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("Invalid local node ID");
        }
    }
}
