package ru.nexora.network;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable-ledger-backed handoff state facade. It coordinates transfer ownership
 * metadata only; gameplay freeze/activation and authenticated transport remain
 * responsibilities of the future live handoff protocol.
 */
public final class DurableHandoffCoordinator {
    private final PlayerTransferCoordinator transfers;

    public DurableHandoffCoordinator(PlayerTransferCoordinator transfers) {
        this.transfers = Objects.requireNonNull(transfers, "transfers");
    }

    public void prepare(PlayerHandoff handoff, String playerXuid,
                        long playerRevision, long nowEpochMillis) throws IOException {
        Objects.requireNonNull(handoff, "handoff");
        PlayerTransferRecord record = PlayerTransferRecord.prepared(
                handoff.transferId(), playerXuid, handoff.sourceNode(), handoff.targetNode(),
                playerRevision, nowEpochMillis);
        if (!transfers.prepare(record)) {
            throw new IllegalStateException("Transfer already exists: " + handoff.transferId());
        }
    }

    public Optional<PlayerTransferRecord> claim(UUID transferId, String destinationNode,
                                                long nowEpochMillis, long leaseMillis) throws IOException {
        return transfers.claim(transferId, destinationNode, nowEpochMillis, leaseMillis);
    }

    /** Cancels a PREPARED transfer only if no destination claim has won the CAS. */
    public Optional<PlayerTransferRecord> abortPrepared(UUID transferId, long nowEpochMillis) throws IOException {
        return transfers.abortPrepared(transferId, nowEpochMillis);
    }

    public Optional<PlayerTransferRecord> commit(UUID transferId, long fencingToken,
                                                 long nowEpochMillis) throws IOException {
        return transfers.commit(transferId, fencingToken, nowEpochMillis);
    }

    public Optional<PlayerTransferRecord> abort(UUID transferId, long fencingToken,
                                                 long nowEpochMillis) throws IOException {
        return transfers.abort(transferId, fencingToken, nowEpochMillis);
    }

    /** Lists durable claims/commits owned by this destination for bounded restart recovery. */
    public List<PlayerTransferRecord> listRecoverableForTarget(String targetNode, int limit) throws IOException {
        return transfers.listRecoverableForTarget(targetNode, limit);
    }

    public Optional<PlayerTransferRecord> findLatestForPlayer(String playerXuid) throws IOException {
        return transfers.findLatestForPlayer(playerXuid);
    }

    public Optional<PlayerTransferRecord> get(UUID transferId) throws IOException {
        return transfers.get(transferId);
    }
}
