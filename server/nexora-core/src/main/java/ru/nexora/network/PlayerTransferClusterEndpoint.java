package ru.nexora.network;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Destination-side handler for authenticated PLAYER_TRANSFER_ACCEPT cluster
 * messages. The shared ledger and checkpoint store remain authoritative; the
 * message carries only a transfer ID, never an untrusted player-state payload.
 */
public final class PlayerTransferClusterEndpoint {
    public static final String ACCEPT_MESSAGE_TYPE = "PLAYER_TRANSFER_ACCEPT";

    private final String localNodeId;
    private final DurableHandoffCoordinator ledger;
    private final PlayerTransferLifecycle.Destination destination;
    private final long leaseMillis;
    private final LongSupplier epochMillis;

    public PlayerTransferClusterEndpoint(
            String localNodeId,
            DurableHandoffCoordinator ledger,
            PlayerTransferLifecycle.Destination destination,
            long leaseMillis,
            LongSupplier epochMillis
    ) {
        if (localNodeId == null || !localNodeId.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("Invalid local node ID");
        }
        this.localNodeId = localNodeId;
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.destination = Objects.requireNonNull(destination, "destination");
        if (leaseMillis <= 0) throw new IllegalArgumentException("leaseMillis must be positive");
        this.leaseMillis = leaseMillis;
        this.epochMillis = Objects.requireNonNull(epochMillis, "epochMillis");
    }

    /** Handles one authenticated transport message; throw to make the TCP ACK fail. */
    public void handle(ClusterMessage message) throws IOException {
        Objects.requireNonNull(message, "message");
        if (!ACCEPT_MESSAGE_TYPE.equals(message.type())
                || !localNodeId.equals(message.targetNode())) {
            throw new SecurityException("Unexpected player-transfer cluster message");
        }
        UUID transferId = decodeTransferId(message.payload());
        PlayerTransferRecord prepared = ledger.get(transferId).orElseThrow(
                () -> new IOException("Transfer record is not present in the shared ledger"));
        if (!message.sourceNode().equals(prepared.sourceNode())
                || !localNodeId.equals(prepared.targetNode())) {
            throw new SecurityException("Cluster message does not match durable transfer participants");
        }
        if (prepared.state() == PlayerTransferRecord.State.COMMITTED) return;
        if (prepared.state() != PlayerTransferRecord.State.PREPARED) {
            throw new IOException("Transfer is not in PREPARED state: " + prepared.state());
        }

        long claimTime = epochMillis.getAsLong();
        Optional<PlayerTransferRecord> claimed = ledger.claim(
                transferId, localNodeId, claimTime, leaseMillis);
        if (claimed.isEmpty()) throw new IOException("Destination could not claim player transfer");
        long fence = claimed.orElseThrow().fencingToken();
        PlayerHandoff handoff = new PlayerHandoff(
                transferId, 0L, prepared.sourceNode(), prepared.targetNode(), new byte[0]);

        try {
            if (!destination.restoreAndActivate(handoff, fence)) {
                throw new IOException("Destination rejected the exact player checkpoint");
            }
            Optional<PlayerTransferRecord> committed = ledger.commit(
                    transferId, fence, epochMillis.getAsLong());
            if (committed.isPresent()) return;
            throw new IOException("Destination activation completed but durable commit was not confirmed");
        } catch (Exception failure) {
            try {
                destination.deactivateIfOwned(handoff, fence);
            } catch (Exception deactivateFailure) {
                failure.addSuppressed(deactivateFailure);
            }
            try {
                ledger.abort(transferId, fence, epochMillis.getAsLong());
            } catch (Exception abortFailure) {
                failure.addSuppressed(abortFailure);
            }
            if (failure instanceof IOException ioFailure) throw ioFailure;
            throw new IOException("Destination player-transfer acceptance failed", failure);
        }
    }

    public static byte[] encodeTransferId(UUID transferId) {
        Objects.requireNonNull(transferId, "transferId");
        return ByteBuffer.allocate(16)
                .putLong(transferId.getMostSignificantBits())
                .putLong(transferId.getLeastSignificantBits())
                .array();
    }

    private static UUID decodeTransferId(byte[] payload) throws IOException {
        if (payload == null || payload.length != 16) {
            throw new IOException("Invalid player-transfer cluster payload");
        }
        ByteBuffer input = ByteBuffer.wrap(payload);
        return new UUID(input.getLong(), input.getLong());
    }
}
