package ru.nexora.network.bedrock;

import ru.nexora.network.PlayerHandoff;
import ru.nexora.network.PlayerTransferLifecycle;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Function;

/**
 * Source participant adapter for the live Bedrock session manager.
 *
 * The caller supplies the authenticated XUID associated with a transfer. The
 * destination side is deliberately separate: it must restore the exact
 * checkpoint revision and implement fencing before it can safely activate.
 */
public final class BedrockPlayerTransferSource implements PlayerTransferLifecycle.Source {
    private final BedrockSessionManager sessions;
    private final String nodeId;
    private final Function<PlayerHandoff, String> xuidResolver;

    public BedrockPlayerTransferSource(BedrockSessionManager sessions, String nodeId,
                                       Function<PlayerHandoff, String> xuidResolver) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        if (nodeId == null || !nodeId.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("Invalid source node ID");
        }
        this.nodeId = nodeId;
        this.xuidResolver = Objects.requireNonNull(xuidResolver, "xuidResolver");
    }

    @Override
    public long freezeAndCheckpoint(PlayerHandoff handoff) throws IOException {
        validateSource(handoff);
        String xuid = resolveXuid(handoff);
        return sessions.freezePlayerForTransfer(xuid);
    }

    @Override
    public void unfreeze(PlayerHandoff handoff) throws IOException {
        validateSource(handoff);
        String xuid = resolveXuid(handoff);
        sessions.unfreezePlayerAfterTransferAbort(xuid);
    }

    private void validateSource(PlayerHandoff handoff) {
        Objects.requireNonNull(handoff, "handoff");
        if (!nodeId.equals(handoff.sourceNode())) {
            throw new SecurityException("Transfer source node does not match this Bedrock server");
        }
    }

    private String resolveXuid(PlayerHandoff handoff) {
        String xuid = xuidResolver.apply(handoff);
        if (xuid == null || !xuid.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new SecurityException("Transfer does not resolve to a valid authenticated XUID");
        }
        return xuid;
    }
}
