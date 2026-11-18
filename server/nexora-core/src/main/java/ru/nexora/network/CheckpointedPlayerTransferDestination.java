package ru.nexora.network;

import ru.nexora.persistence.PlayerStateBundle;
import ru.nexora.persistence.PlayerStateCheckpointService;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Destination protocol adapter that validates the durable claim and restores
 * only the exact checkpoint revision named by that claim.
 *
 * The supplied Activator owns the Bedrock/session-specific work. It must call
 * fence.assertCurrent() before every authoritative mutation and stop accepting
 * gameplay if the fence becomes invalid.
 */
public final class CheckpointedPlayerTransferDestination implements PlayerTransferLifecycle.Destination {
    public interface Activator {
        boolean restoreAndActivate(PlayerHandoff handoff, PlayerStateBundle bundle,
                                   PlayerTransferFence fence) throws Exception;
        void deactivate(PlayerHandoff handoff, PlayerTransferFence fence) throws Exception;
    }

    private final DurableHandoffCoordinator ledger;
    private final PlayerStateCheckpointService checkpoints;
    private final String nodeId;
    private final Activator activator;
    private final LongSupplier epochMillis;

    public CheckpointedPlayerTransferDestination(
            DurableHandoffCoordinator ledger,
            PlayerStateCheckpointService checkpoints,
            String nodeId,
            Activator activator,
            LongSupplier epochMillis
    ) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        if (nodeId == null || !nodeId.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("Invalid destination node ID");
        }
        this.nodeId = nodeId;
        this.activator = Objects.requireNonNull(activator, "activator");
        this.epochMillis = Objects.requireNonNull(epochMillis, "epochMillis");
    }

    @Override
    public boolean restoreAndActivate(PlayerHandoff handoff, long fencingToken) throws Exception {
        Objects.requireNonNull(handoff, "handoff");
        if (!nodeId.equals(handoff.targetNode())) {
            throw new SecurityException("Transfer target node does not match this Bedrock server");
        }
        Optional<PlayerTransferRecord> stored = ledger.get(handoff.transferId());
        if (stored.isEmpty()) return false;
        PlayerTransferRecord claim = stored.orElseThrow();
        long now = epochMillis.getAsLong();
        if (claim.state() != PlayerTransferRecord.State.CLAIMED
                || claim.fencingToken() != fencingToken
                || !claim.targetNode().equals(nodeId)
                || !claim.sourceNode().equals(handoff.sourceNode())
                || !claim.targetNode().equals(handoff.targetNode())
                || claim.leaseExpired(now)) {
            return false;
        }

        PlayerTransferFence fence = new PlayerTransferFence(ledger, claim, epochMillis);
        PlayerStateCheckpointService.LoadedCheckpoint checkpoint = checkpoints
                .loadAtRevision(claim.playerXuid(), claim.playerRevision()).orElse(null);
        if (checkpoint == null) return false;

        fence.assertCurrent();
        boolean activated = activator.restoreAndActivate(handoff, checkpoint.bundle(), fence);
        if (!activated) return false;
        // Detect a claim that expired or was reclaimed during activation. The
        // activator must also enforce the fence continuously while applying state.
        fence.assertCurrent();
        return true;
    }

    @Override
    public void deactivateIfOwned(PlayerHandoff handoff, long fencingToken) throws Exception {
        Objects.requireNonNull(handoff, "handoff");
        Optional<PlayerTransferRecord> stored = ledger.get(handoff.transferId());
        if (stored.isEmpty()) return;
        PlayerTransferRecord claim = stored.orElseThrow();
        if ((claim.state() != PlayerTransferRecord.State.CLAIMED
                && claim.state() != PlayerTransferRecord.State.ABORTED)
                || claim.fencingToken() != fencingToken
                || !claim.targetNode().equals(nodeId)
                || !claim.sourceNode().equals(handoff.sourceNode())
                || !claim.targetNode().equals(handoff.targetNode())) {
            // A committed destination or a superseded fence must never be torn down.
            return;
        }
        // The stored state/token check above proves this rollback still names
        // the current owner. Deactivate even after lease expiry so an in-memory
        // pending-login reservation cannot block a later fencing-token reclaim.
        PlayerTransferFence fence = new PlayerTransferFence(ledger, claim, epochMillis);
        activator.deactivate(handoff, fence);
    }
}
