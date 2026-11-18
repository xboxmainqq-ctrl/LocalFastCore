package ru.nexora.network;

import ru.nexora.network.bedrock.BedrockSessionManager;
import ru.nexora.persistence.PlayerStateBundle;

import java.io.IOException;
import java.util.Objects;

/**
 * Bridges a claimed transfer to the destination Bedrock login pipeline.
 *
 * The destination player must reconnect/authenticate to this node. This adapter
 * reserves the exact XUID/revision/fence and the login handler consumes it before
 * admitting the session. It does not pretend to migrate an existing WebRTC socket.
 */
public final class BedrockPendingTransferActivator
        implements CheckpointedPlayerTransferDestination.Activator {
    private final BedrockSessionManager sessions;

    public BedrockPendingTransferActivator(BedrockSessionManager sessions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    @Override
    public boolean restoreAndActivate(PlayerHandoff handoff, PlayerStateBundle bundle,
                                      PlayerTransferFence fence) throws IOException {
        Objects.requireNonNull(handoff, "handoff");
        Objects.requireNonNull(bundle, "bundle");
        Objects.requireNonNull(fence, "fence");
        if (!bundle.profile().xuid().equals(fence.playerXuid())) {
            throw new SecurityException("Checkpoint XUID does not match transfer ownership fence");
        }
        if (!handoff.targetNode().equals(fence.targetNode())) {
            throw new SecurityException("Transfer target does not match destination ownership fence");
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
        sessions.cancelPendingTransferRestore(
                fence.playerXuid(), fence.transferId(), fence.fencingToken());
    }
}
