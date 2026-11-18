package ru.nexora.network;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * Coordinates the live participant hooks around the durable transfer ledger.
 *
 * The source remains frozen unless the ledger confirms ABORTED. A destination
 * receives a fencing token and must reject all ownership writes made with an
 * older token. This class is a protocol coordinator, not a consensus system:
 * the ledger must be shared/durable across nodes before this is used cross-host.
 */
public final class PlayerTransferLifecycle {
    public enum AcceptResult {
        CLAIM_REJECTED,
        ACTIVATION_FAILED_AND_ABORTED,
        COMMITTED,
        COMMIT_UNCONFIRMED
    }

    public interface Source {
        /** Stop authoritative gameplay and persist a canonical checkpoint. */
        long freezeAndCheckpoint(PlayerHandoff handoff) throws Exception;
        /** Resume source gameplay only after the ledger confirms ABORTED. */
        void unfreeze(PlayerHandoff handoff) throws Exception;
    }

    public interface Destination {
        /**
         * Restore and activate the player while enforcing the supplied fencing
         * token on every ownership-sensitive write.
         */
        boolean restoreAndActivate(PlayerHandoff handoff, long fencingToken) throws Exception;
        /** Stop/remove this destination's session only if it still owns this fence. */
        void deactivateIfOwned(PlayerHandoff handoff, long fencingToken) throws Exception;
    }

    private final DurableHandoffCoordinator ledger;
    private final Source source;
    private final Destination destination;
    private final long leaseMillis;

    public PlayerTransferLifecycle(DurableHandoffCoordinator ledger, Source source,
                                   Destination destination, long leaseMillis) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.source = Objects.requireNonNull(source, "source");
        this.destination = Objects.requireNonNull(destination, "destination");
        if (leaseMillis <= 0) throw new IllegalArgumentException("leaseMillis must be positive");
        this.leaseMillis = leaseMillis;
    }

    /**
     * Freezes and checkpoints first, then publishes PREPARED. If publication
     * fails, the source is resumed because no destination claim can exist.
     */
    public void prepare(PlayerHandoff handoff, String playerXuid, long nowEpochMillis)
            throws Exception {
        Objects.requireNonNull(handoff, "handoff");
        long revision = source.freezeAndCheckpoint(handoff);
        boolean prepared = false;
        try {
            ledger.prepare(handoff, playerXuid, revision, nowEpochMillis);
            prepared = true;
        } finally {
            if (!prepared) source.unfreeze(handoff);
        }
    }

    /**
     * Claims the transfer, restores/activates under its fence, then commits.
     * If activation fails, the destination is fenced off before ABORTED is
     * recorded; only a confirmed abort permits the source to resume.
     */
    public AcceptResult accept(PlayerHandoff handoff, long nowEpochMillis) throws Exception {
        Objects.requireNonNull(handoff, "handoff");
        Optional<PlayerTransferRecord> claimed = ledger.claim(
                handoff.transferId(), handoff.targetNode(), nowEpochMillis, leaseMillis);
        if (claimed.isEmpty()) return AcceptResult.CLAIM_REJECTED;

        long fence = claimed.orElseThrow().fencingToken();
        boolean activated;
        try {
            activated = destination.restoreAndActivate(handoff, fence);
        } catch (Exception activationFailure) {
            try {
                destination.deactivateIfOwned(handoff, fence);
                Optional<PlayerTransferRecord> aborted = ledger.abort(
                        handoff.transferId(), fence, nowEpochMillis);
                if (aborted.isPresent()) source.unfreeze(handoff);
            } catch (Exception rollbackFailure) {
                activationFailure.addSuppressed(rollbackFailure);
            }
            throw activationFailure;
        }

        if (!activated) {
            destination.deactivateIfOwned(handoff, fence);
            Optional<PlayerTransferRecord> aborted = ledger.abort(
                    handoff.transferId(), fence, nowEpochMillis);
            if (aborted.isPresent()) {
                source.unfreeze(handoff);
                return AcceptResult.ACTIVATION_FAILED_AND_ABORTED;
            }
            return AcceptResult.COMMIT_UNCONFIRMED;
        }

        Optional<PlayerTransferRecord> committed = ledger.commit(
                handoff.transferId(), fence, nowEpochMillis);
        if (committed.isPresent()) return AcceptResult.COMMITTED;

        // Never resume the source on an uncertain commit. Remove the destination
        // session if this process still owns the same fence; recovery reconciles
        // the durable record before either node resumes gameplay.
        destination.deactivateIfOwned(handoff, fence);
        return AcceptResult.COMMIT_UNCONFIRMED;
    }

    /**
     * Explicit rollback of a claimed transfer. The destination is deactivated
     * first; source gameplay resumes only if the ledger confirms ABORTED.
     */
    public boolean abortClaimed(PlayerHandoff handoff, long fencingToken,
                                long nowEpochMillis) throws Exception {
        Objects.requireNonNull(handoff, "handoff");
        destination.deactivateIfOwned(handoff, fencingToken);
        Optional<PlayerTransferRecord> aborted = ledger.abort(
                handoff.transferId(), fencingToken, nowEpochMillis);
        if (aborted.isEmpty()) return false;
        source.unfreeze(handoff);
        return true;
    }
}
