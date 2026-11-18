package ru.nexora.network.bedrock;

import ru.nexora.network.CheckpointedPlayerTransferDestination;
import ru.nexora.network.PlayerHandoff;
import ru.nexora.network.PlayerTransferFence;
import ru.nexora.network.PendingPlayerTransferRestoreRegistry;
import ru.nexora.persistence.PlayerStateBundle;

import java.util.Objects;

/**
 * Bridges a validated durable destination claim into the authenticated Bedrock
 * login pipeline. Activation here means reserving the exact checkpoint for the
 * player's next authenticated login; it does not pretend that a remote client
 * has already been redirected or connected.
 */
public final class BedrockPendingTransferActivator
        implements CheckpointedPlayerTransferDestination.Activator {
    private final BedrockSessionManager sessions;

    public BedrockPendingTransferActivator(BedrockSessionManager sessions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    @Override
    public boolean restoreAndActivate(PlayerHandoff handoff, PlayerStateBundle bundle,
                                      PlayerTransferFence fence) throws Exception {
        Objects.requireNonNull(handoff, "handoff");
        Objects.requireNonNull(bundle, "bundle");
        Objects.requireNonNull(fence, "fence");
        if (!handoff.transferId().equals(fence.transferId())
                || !handoff.targetNode().equals(fence.targetNode())
                || !bundle.profile().xuid().equals(fence.playerXuid())) {
            throw new SecurityException("Transfer checkpoint identity does not match destination claim");
        }
        fence.assertCurrent();
        sessions.registerPendingTransferRestore(new PendingPlayerTransferRestoreRegistry.PendingRestore(
                fence.playerXuid(), fence.playerRevision(), fence));
        return true;
    }

    @Override
    public void deactivate(PlayerHandoff handoff, PlayerTransferFence fence) {
        Objects.requireNonNull(handoff, "handoff");
        Objects.requireNonNull(fence, "fence");
        if (!handoff.transferId().equals(fence.transferId())
                || !handoff.targetNode().equals(fence.targetNode())) {
            throw new SecurityException("Cannot deactivate a different player transfer");
        }
        sessions.deactivateTransferRestore(fence.playerXuid(), fence.transferId(), fence.fencingToken());
    }
}
