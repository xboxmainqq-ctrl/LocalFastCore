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
    public static final String ABORT_MESSAGE_TYPE = "PLAYER_TRANSFER_ABORT";

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
        if (!localNodeId.equals(message.targetNode())) {
            throw new SecurityException("Unexpected player-transfer target node");
        }
        if (ABORT_MESSAGE_TYPE.equals(message.type())) {
            handleAbort(message);
            return;
        }
        if (!ACCEPT_MESSAGE_TYPE.equals(message.type())) {
            throw new SecurityException("Unexpected player-transfer cluster message type");
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

    private void handleAbort(ClusterMessage message) throws IOException {
        AbortPayload abort = decodeAbortPayload(message.payload());
        PlayerTransferRecord current = ledger.get(abort.transferId()).orElseThrow(
                () -> new IOException("Transfer record is not present in the shared ledger"));
        if (!message.sourceNode().equals(current.sourceNode())
                || !localNodeId.equals(current.targetNode())) {
            throw new SecurityException("Abort message does not match durable transfer participants");
        }
        if (current.state() == PlayerTransferRecord.State.COMMITTED) return;
        if (current.state() == PlayerTransferRecord.State.PREPARED) {
            if (abort.fencingToken() != 0) throw new SecurityException("Prepared transfer has no claim fence");
            ledger.abortPrepared(abort.transferId(), epochMillis.getAsLong());
            return;
        }
        if (current.fencingToken() != abort.fencingToken() || abort.fencingToken() <= 0) {
            throw new SecurityException("Abort message carries a stale fencing token");
        }
        if (current.state() == PlayerTransferRecord.State.CLAIMED) {
            Optional<PlayerTransferRecord> aborted = ledger.abort(
                    abort.transferId(), abort.fencingToken(), epochMillis.getAsLong());
            if (aborted.isEmpty()) {
                PlayerTransferRecord latest = ledger.get(abort.transferId()).orElseThrow(
                        () -> new IOException("Transfer disappeared during abort"));
                if (latest.state() == PlayerTransferRecord.State.COMMITTED) return;
                if (latest.state() != PlayerTransferRecord.State.ABORTED
                        || latest.fencingToken() != abort.fencingToken()) return;
            }
        }
        PlayerHandoff handoff = new PlayerHandoff(abort.transferId(), 0L,
                current.sourceNode(), current.targetNode(), new byte[0]);
        destination.deactivateIfOwned(handoff, abort.fencingToken());
    }

    public static byte[] encodeAbortPayload(UUID transferId, long fencingToken) {
        Objects.requireNonNull(transferId, "transferId");
        if (fencingToken < 0) throw new IllegalArgumentException("fencingToken must not be negative");
        return ByteBuffer.allocate(24)
                .putLong(transferId.getMostSignificantBits())
                .putLong(transferId.getLeastSignificantBits())
                .putLong(fencingToken)
                .array();
    }

    private static AbortPayload decodeAbortPayload(byte[] payload) throws IOException {
        if (payload == null || payload.length != 24) throw new IOException("Invalid player-transfer abort payload");
        ByteBuffer input = ByteBuffer.wrap(payload);
        UUID transferId = new UUID(input.getLong(), input.getLong());
        long fencingToken = input.getLong();
        if (fencingToken < 0) throw new IOException("Invalid player-transfer abort fencing token");
        return new AbortPayload(transferId, fencingToken);
    }

    private record AbortPayload(UUID transferId, long fencingToken) {
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
