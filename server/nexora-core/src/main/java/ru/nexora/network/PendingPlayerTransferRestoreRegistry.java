package ru.nexora.network;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-process rendezvous between the cluster transfer coordinator and the
 * authenticated Bedrock login pipeline on the destination node.
 *
 * Registration is explicit and keyed by verified XUID. A pending restore is
 * not consumed until the login pipeline has successfully restored its exact
 * checkpoint revision. The durable ledger remains authoritative; this map is
 * only a short-lived local handoff to the session thread.
 */
public final class PendingPlayerTransferRestoreRegistry {
    public record PendingRestore(String playerXuid, long checkpointRevision,
                                 PlayerTransferFence fence) {
        public PendingRestore {
            if (playerXuid == null || !playerXuid.matches("[A-Za-z0-9_-]{1,64}")) {
                throw new IllegalArgumentException("Invalid player XUID");
            }
            if (checkpointRevision < 0) {
                throw new IllegalArgumentException("Checkpoint revision must not be negative");
            }
            Objects.requireNonNull(fence, "fence");
            if (!playerXuid.equals(fence.playerXuid())
                    || checkpointRevision != fence.playerRevision()) {
                throw new IllegalArgumentException("Pending restore does not match its ownership fence");
            }
        }
    }

    private final ConcurrentMap<String, PendingRestore> pending = new ConcurrentHashMap<>();

    /** Registers one claimed transfer for the exact authenticated player. */
    public void register(PendingRestore restore) {
        Objects.requireNonNull(restore, "restore");
        try {
            restore.fence().assertCurrent();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot register a stale transfer restore", exception);
        }
        while (true) {
            PendingRestore previous = pending.putIfAbsent(restore.playerXuid(), restore);
            if (previous == null) return;
            try {
                previous.fence().assertCurrent();
            } catch (java.io.IOException stale) {
                // Remove only the exact revoked/expired registration. A newer
                // or committed owner must continue to block replacement.
                pending.remove(restore.playerXuid(), previous);
                continue;
            }
            throw new IllegalStateException("A transfer restore is already pending for this player");
        }
    }

    /** Returns a pending restore without consuming it, for safe login preparation. */
    public Optional<PendingRestore> find(String playerXuid) {
        return Optional.ofNullable(pending.get(Objects.requireNonNull(playerXuid, "playerXuid")));
    }

    /** Removes only the exact registration used by a successfully restored login. */
    public boolean consume(PendingRestore restore) {
        Objects.requireNonNull(restore, "restore");
        java.util.concurrent.atomic.AtomicBoolean consumed = new java.util.concurrent.atomic.AtomicBoolean();
        pending.computeIfPresent(restore.playerXuid(), (key, current) -> {
            if (current == restore) {
                consumed.set(true);
                return null;
            }
            return current;
        });
        return consumed.get();
    }

    /** Removes a registration only when the same transfer and fencing token still match. */
    public boolean cancel(String playerXuid, java.util.UUID transferId, long fencingToken) {
        Objects.requireNonNull(playerXuid, "playerXuid");
        Objects.requireNonNull(transferId, "transferId");
        PendingRestore current = pending.get(playerXuid);
        return current != null && current.fence().transferId().equals(transferId)
                && current.fence().fencingToken() == fencingToken
                && pending.remove(playerXuid, current);
    }

    public int size() {
        return pending.size();
    }
}
