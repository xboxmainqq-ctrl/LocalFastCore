package ru.nexora.network;

import java.io.IOException;
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

    public Optional<PlayerTransferRecord> commit(UUID transferId, long fencingToken,
                                                 long nowEpochMillis) throws IOException {
        return transfers.commit(transferId, fencingToken, nowEpochMillis);
    }

    public Optional<PlayerTransferRecord> abort(UUID transferId, long fencingToken,
                                                 long nowEpochMillis) throws IOException {
        return transfers.abort(transferId, fencingToken, nowEpochMillis);
    }

    public Optional<PlayerTransferRecord> get(UUID transferId) throws IOException {
        return transfers.get(transferId);
    }
}
